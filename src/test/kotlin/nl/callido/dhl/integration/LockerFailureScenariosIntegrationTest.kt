package nl.callido.dhl.integration

import nl.callido.dhl.client.LockerSimBaseUrl
import nl.callido.dhl.client.LockerTokenProvider
import nl.callido.dhl.common.ParcelSize
import nl.callido.dhl.dto.locker.CreateSessionRequest
import nl.callido.dhl.dto.locker.CreateSessionResponse
import nl.callido.dhl.dto.locker.LockerActionRequest
import nl.callido.dhl.dto.locker.LockerActionResponse
import nl.callido.dhl.dto.sim.BindRequest
import nl.callido.dhl.dto.sim.DoorAction
import nl.callido.dhl.dto.sim.DoorRequest
import nl.callido.dhl.dto.sim.FailureMode
import nl.callido.dhl.dto.sim.FailureRequest
import nl.callido.dhl.dto.sim.RejectionResponse
import nl.callido.dhl.dto.sim.ResetRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.SimSessionState
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.dto.trips.TripDto
import nl.callido.dhl.service.locker.SessionReaper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.web.client.RestClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.redpanda.RedpandaContainer
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real-life failure scenarios from the interview case, end to end through the
 * BFF's HTTP API against real Postgres + Redpanda — the locker engine is only
 * reached the way production reaches it. Every test starts from a full demo
 * reset, so they run independently:
 *  - vak te klein: undersized compartment → report-size → strictly bigger door
 *  - a second door never opens while one is open (DOOR_STILL_OPEN)
 *  - hand-out happy path → HANDED_OUT registered; report-missing → NOT_DELIVERED
 *  - the reaper expires an abandoned session and registers NOT_DELIVERED
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["dhl.reaper.enabled=true"],
)
@Testcontainers(disabledWithoutDocker = true)
class LockerFailureScenariosIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @Container
        @JvmStatic
        val redpanda = RedpandaContainer("redpandadata/redpanda:v24.1.2")

        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers") { redpanda.bootstrapServers }
            registry.add("dhl.security.courier-issuer") { "http://stub-issuer/courier" }
            registry.add("dhl.security.locker-issuer") { "http://stub-issuer/locker" }
            registry.add("dhl.locker-client.token-uri") { "http://stub-issuer/token" }
        }

        @Volatile
        private var serverPort = 0

        private fun stubDecoder(subject: String): ReactiveJwtDecoder = ReactiveJwtDecoder { token ->
            reactor.core.publisher.Mono.just(
                Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject(subject)
                    .claim("preferred_username", subject)
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build(),
            )
        }

        // subject = the bearer token value, so tests can act as different couriers
        @JvmStatic fun stubCourierDecoder(): ReactiveJwtDecoder = ReactiveJwtDecoder { token ->
            stubDecoder(token).decode(token)
        }

        @JvmStatic fun stubLockerDecoder(): ReactiveJwtDecoder = stubDecoder("dhl-backend")

        @JvmStatic fun stubTokenProvider(): LockerTokenProvider = LockerTokenProvider { "stub-locker-token" }

        @JvmStatic fun stubBaseUrl(): LockerSimBaseUrl = LockerSimBaseUrl { "http://localhost:$serverPort" }
    }

    @TestBean(name = "courierJwtDecoder", methodName = "stubCourierDecoder")
    lateinit var courierJwtDecoder: ReactiveJwtDecoder

    @TestBean(name = "lockerJwtDecoder", methodName = "stubLockerDecoder")
    lateinit var lockerJwtDecoder: ReactiveJwtDecoder

    @TestBean(name = "lockerTokenProvider", methodName = "stubTokenProvider")
    lateinit var lockerTokenProvider: LockerTokenProvider

    @TestBean(name = "lockerSimBaseUrl", methodName = "stubBaseUrl")
    lateinit var lockerSimBaseUrl: LockerSimBaseUrl

    @Autowired lateinit var env: Environment

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var reaper: SessionReaper

    @BeforeEach
    fun resetDemo() {
        serverPort = env.getProperty("local.server.port")!!.toInt()
        // full demo reset: sim AND seeded data — every test starts clean
        post("/api/sim/reset", ResetRequest(), SimStateSnapshot::class.java)
    }

    private val http: RestClient by lazy {
        RestClient.builder()
            .baseUrl("http://localhost:${env.getProperty("local.server.port")}")
            .defaultStatusHandler({ true }) { _, _ -> }
            .build()
    }

    private fun <T : Any> get(path: String, type: Class<T>): T = http.get().uri(path)
        .headers { it.setBearerAuth("test-token") }
        .retrieve().toEntity(type).body!!

    private fun <T : Any> post(path: String, body: Any?, type: Class<T>): ResponseEntity<T> {
        val spec = http.post().uri(path)
            .headers { it.setBearerAuth("test-token") }
            .contentType(MediaType.APPLICATION_JSON)
        return (body?.let { spec.body(it) } ?: spec).retrieve().toEntity(type)
    }

    private fun startBoundSession(): CreateSessionResponse {
        val trips = get("/api/trips", Array<TripDto>::class.java).toList()
        val lockerStop = trips.single().stops.single { it.deliveryLocationType.name == "LOCKER" }
        val session = post("/api/locker/sessions", CreateSessionRequest(lockerStop.id), CreateSessionResponse::class.java).body!!
        post("/api/sim/bind", BindRequest(session.qrPayload), SimSessionSnapshot::class.java)
        return session
    }

    private fun setFailure(mode: FailureMode, enabled: Boolean) {
        post("/api/sim/failures", FailureRequest(mode, enabled), SimStateSnapshot::class.java)
    }

    @Test
    fun `vak te klein - report-size makes the next attempt open a strictly bigger door`() {
        val session = startBoundSession()
        setFailure(FailureMode.SIZE_TOO_SMALL, true)

        // sabotage on: the machine hands out a door that is too small for an L parcel
        val tooSmall = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/attempt",
            LockerActionRequest("DHL-IN-002"),
            LockerActionResponse::class.java,
        ).body!!
        val firstDoor = assertNotNull(tooSmall.compartment)
        assertTrue(firstDoor.size < ParcelSize.L, "sabotaged attempt must open an undersized door, got ${firstDoor.size}")

        setFailure(FailureMode.SIZE_TOO_SMALL, false)
        val reported = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/report-size",
            null,
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.READY, reported.simState)

        // doors are physical: the too-small door is still open — close it first
        post("/api/sim/door", DoorRequest(firstDoor.nr, DoorAction.CLOSE), SimSessionSnapshot::class.java)

        val retry = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/attempt",
            LockerActionRequest("DHL-IN-002"),
            LockerActionResponse::class.java,
        ).body!!
        val secondDoor = assertNotNull(retry.compartment)
        assertTrue(
            secondDoor.size > firstDoor.size,
            "retry must escalate: ${firstDoor.size} -> ${secondDoor.size}",
        )
    }

    @Test
    fun `a second door never opens while another door is open`() {
        val session = startBoundSession()

        val first = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/attempt",
            LockerActionRequest("DHL-IN-001"),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.HAND_IN_DOOR_OPEN, first.simState)
        val openNr = assertNotNull(first.compartment).nr

        // the same session cannot open a second door: state machine blocks the
        // attempt and the response carries the current (reconciled) truth
        val second = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/attempt",
            LockerActionRequest("DHL-IN-002"),
            LockerActionResponse::class.java,
        )
        assertEquals(200, second.statusCode.value())
        assertEquals(true, second.body!!.reconciled, "second attempt must reconcile, not open a door")

        // the courier walks away with the door open; a NEW courier arrives.
        // Doors are physical — the abandoned door is still open, so the new
        // session's attempt is rejected with DOOR_STILL_OPEN
        post("/api/locker/sessions/${session.sessionId}/finish", null, LockerActionResponse::class.java)
        val session2 = startBoundSession()
        val rejected = http.post().uri("/api/locker/sessions/${session2.sessionId}/hand-in/attempt")
            .headers { it.setBearerAuth("test-token") }
            .contentType(MediaType.APPLICATION_JSON)
            .body(LockerActionRequest("DHL-IN-002"))
            .retrieve().toEntity(RejectionResponse::class.java)
        assertEquals(422, rejected.statusCode.value())
        assertEquals("DOOR_STILL_OPEN", rejected.body!!.code)

        // someone closes the abandoned door — now the flow continues
        post("/api/sim/door", DoorRequest(openNr, DoorAction.CLOSE), SimSessionSnapshot::class.java)
        val fresh = post(
            "/api/locker/sessions/${session2.sessionId}/hand-in/attempt",
            LockerActionRequest("DHL-IN-002"),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.HAND_IN_DOOR_OPEN, fresh.simState)

        val machine = get("/api/sim/state", SimStateSnapshot::class.java)
        assertEquals(
            1,
            machine.compartments.count { it.state.name == "DOOR_OPEN" },
            "the machine must never have more than one door open",
        )

        post("/api/sim/door", DoorRequest(assertNotNull(fresh.compartment).nr, DoorAction.CLOSE), SimSessionSnapshot::class.java)
    }

    @Test
    fun `hand-out happy path registers HANDED_OUT, report-missing registers NOT_DELIVERED`() {
        val session = startBoundSession()

        val start = post(
            "/api/locker/sessions/${session.sessionId}/hand-out/start",
            LockerActionRequest("DHL-OUT-001"),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.HAND_OUT_DOOR_OPEN, start.simState)
        assertEquals(true, start.parcelPresent)
        val nr = assertNotNull(start.compartment).nr

        post("/api/sim/door", DoorRequest(nr, DoorAction.CLOSE), SimSessionSnapshot::class.java)
        val confirm = post(
            "/api/locker/sessions/${session.sessionId}/hand-out/confirm",
            LockerActionRequest("DHL-OUT-001"),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.HAND_OUT_COMPLETED, confirm.simState)

        assertEquals(
            "HANDED_OUT",
            jdbc.queryForObject("select status from parcel where barcode = ?", String::class.java, "DHL-OUT-001"),
        )
        assertEquals(1, countOutbox("DHL-OUT-001"))

        // -- report-missing on a fresh demo state --
        post("/api/sim/reset", ResetRequest(), SimStateSnapshot::class.java)
        val session2 = startBoundSession()
        setFailure(FailureMode.PARCEL_MISSING, true)
        val missing = post(
            "/api/locker/sessions/${session2.sessionId}/hand-out/start",
            LockerActionRequest("DHL-OUT-001"),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(false, missing.parcelPresent, "PARCEL_MISSING must surface an empty compartment")

        val reportedMissing = post(
            "/api/locker/sessions/${session2.sessionId}/hand-out/report-missing",
            LockerActionRequest("DHL-OUT-001"),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.READY, reportedMissing.simState)
        setFailure(FailureMode.PARCEL_MISSING, false)

        assertEquals(
            "NOT_DELIVERED",
            jdbc.queryForObject("select status from parcel where barcode = ?", String::class.java, "DHL-OUT-001"),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from delivery_registration where barcode = ? and status = 'NOT_DELIVERED'",
                Int::class.java,
                "DHL-OUT-001",
            ),
        )
    }

    @Test
    fun `another courier cannot read or mutate a session they do not own`() {
        val session = startBoundSession() // owned by subject "test-token"

        // courier B mutates A's session → constant 403, nothing leaks
        val foreignAttempt = http.post().uri("/api/locker/sessions/${session.sessionId}/hand-in/attempt")
            .headers { it.setBearerAuth("courier-B") }
            .contentType(MediaType.APPLICATION_JSON)
            .body(LockerActionRequest("DHL-IN-001"))
            .retrieve().toEntity(Map::class.java)
        assertEquals(403, foreignAttempt.statusCode.value())
        assertEquals("Geen toegang tot deze sessie", foreignAttempt.body!!["message"])

        // status is gated by the same check
        val foreignStatus = http.get().uri("/api/locker/sessions/${session.sessionId}")
            .headers { it.setBearerAuth("courier-B") }
            .retrieve().toEntity(Map::class.java)
        assertEquals(403, foreignStatus.statusCode.value())

        // the session is untouched: still ACTIVE, no door opened, nothing registered
        assertEquals(
            "ACTIVE",
            jdbc.queryForObject("select status from locker_session where id = ?", String::class.java, session.sessionId),
        )
        val machine = get("/api/sim/state", SimStateSnapshot::class.java)
        assertEquals(0, machine.compartments.count { it.state.name == "DOOR_OPEN" })
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from delivery_registration where session_id = ?",
                Int::class.java,
                session.sessionId,
            ),
        )
        assertEquals(0, jdbc.queryForObject("select count(*) from outbox", Int::class.java))

        // the owner is unaffected and continues normally
        val owned = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/attempt",
            LockerActionRequest("DHL-IN-001"),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.HAND_IN_DOOR_OPEN, owned.simState)
        post("/api/sim/door", DoorRequest(assertNotNull(owned.compartment).nr, DoorAction.CLOSE), SimSessionSnapshot::class.java)
    }

    /**
     * Doubles as the ownership-gate counterpart: the reaper runs on a
     * scheduler thread with NO authenticated courier and must still be able
     * to finish expired sessions — it talks to the sim client and the
     * repositories directly and never passes the per-request ownership check.
     */
    @Test
    fun `the reaper expires an abandoned session and registers NOT_DELIVERED for open parcels`() {
        val session = startBoundSession()

        // the courier walks away: backdate the session past the reaper timeout
        jdbc.update(
            "update locker_session set last_activity_at = now() - interval '10 minutes' where id = ?",
            session.sessionId,
        )
        reaper.reap()

        assertEquals(
            "EXPIRED",
            jdbc.queryForObject("select status from locker_session where id = ?", String::class.java, session.sessionId),
        )
        // all three seeded parcels on the stop were still EXPECTED → all registered NOT_DELIVERED
        assertEquals(
            3,
            jdbc.queryForObject(
                "select count(*) from delivery_registration where session_id = ? and status = 'NOT_DELIVERED'",
                Int::class.java,
                session.sessionId,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject("select count(*) from parcel where status = 'EXPECTED'", Int::class.java),
        )
        // and every registration produced exactly one outbox event
        assertEquals(
            3,
            jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'DELIVERY_REGISTERED'",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `sessionless register is idempotent - the test that would have caught the NULL hole`() {
        // a doorstep registration carries no sessionId; calling it twice must
        // still produce exactly one registration row and one outbox row
        repeat(2) {
            val response = post(
                "/api/deliveries/register",
                mapOf("barcode" to "DHL-IN-001", "status" to "HANDED_IN"),
                Map::class.java,
            )
            assertEquals(200, response.statusCode.value())
        }
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from delivery_registration where barcode = ? and session_id is null",
                Int::class.java,
                "DHL-IN-001",
            ),
        )
        assertEquals(1, countOutbox("DHL-IN-001"))
        // session-scoped dedupe stays covered by the hand-in happy-path IT
    }

    @Test
    fun `announcing a parcel with impossible dimensions is a 400 and reserves nothing`() {
        val response = post(
            "/api/sim/parcels",
            mapOf("barcode" to "DHL-IN-999", "lengthCm" to -1, "widthCm" to 10, "heightCm" to 10, "weightG" to 500),
            Map::class.java,
        )
        assertEquals(400, response.statusCode.value())
        assertEquals("Ongeldig verzoek", response.body!!["message"])

        // rejected at the front door: no reservation, no parcel row
        val machine = get("/api/sim/state", SimStateSnapshot::class.java)
        assertEquals(0, machine.compartments.count { it.state.name == "RESERVED" })
        assertEquals(
            0,
            jdbc.queryForObject("select count(*) from parcel where barcode = ?", Int::class.java, "DHL-IN-999"),
        )
    }

    private fun countOutbox(barcode: String): Int = jdbc.queryForObject(
        "select count(*) from outbox where aggregate_id = ?",
        Int::class.java,
        barcode,
    )!!
}
