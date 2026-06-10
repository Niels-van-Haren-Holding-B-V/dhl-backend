package nl.callido.dhl.integration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import nl.callido.dhl.client.LockerSimBaseUrl
import nl.callido.dhl.client.LockerTokenProvider
import nl.callido.dhl.dto.locker.CreateSessionRequest
import nl.callido.dhl.dto.locker.CreateSessionResponse
import nl.callido.dhl.dto.locker.LockerActionRequest
import nl.callido.dhl.dto.locker.LockerActionResponse
import nl.callido.dhl.dto.locker.SessionStatusDto
import nl.callido.dhl.dto.locker.ValidationResultDto
import nl.callido.dhl.dto.sim.BindRequest
import nl.callido.dhl.dto.sim.DoorAction
import nl.callido.dhl.dto.sim.DoorRequest
import nl.callido.dhl.dto.sim.ResetRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.SimSessionState
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.dto.trips.TripDto
import nl.callido.dhl.service.outbox.OutboxPublisher
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.redpanda.RedpandaContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The case's minimum bar, end to end against real Postgres + Redpanda:
 *  - full hand-in happy path → registration + outbox row + Kafka message
 *  - duplicate confirm → reconciled response, still exactly one outbox row
 *  - two parallel continues → both 200, at least one `reconciled: true`
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["dhl.reaper.enabled=false"],
)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HandInFlowIntegrationTest {

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
            // No Boot 4 ConnectionDetails factory for RedpandaContainer — wire it by hand.
            registry.add("spring.kafka.bootstrap-servers") { redpanda.bootstrapServers }
            registry.add("dhl.security.courier-issuer") { "http://stub-issuer/courier" }
            registry.add("dhl.security.locker-issuer") { "http://stub-issuer/locker" }
            registry.add("dhl.locker-client.token-uri") { "http://stub-issuer/token" }
        }

        @Volatile
        private var serverPort = 0

        // Test-only Reactor usage: ReactiveJwtDecoder's contract is Mono.
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

        @JvmStatic fun stubCourierDecoder(): ReactiveJwtDecoder = stubDecoder("koerier")

        @JvmStatic fun stubLockerDecoder(): ReactiveJwtDecoder = stubDecoder("dhl-backend")

        @JvmStatic fun stubTokenProvider(): LockerTokenProvider = LockerTokenProvider { "stub-locker-token" }

        @JvmStatic fun stubBaseUrl(): LockerSimBaseUrl = LockerSimBaseUrl { "http://localhost:$serverPort" }
    }

    // @TestBean replaces the app beans reliably (definition-level override).
    // Keycloak is stubbed out; the app talks to ITSELF over real HTTP — the
    // security chains, locks, outbox and Kafka are the real thing.
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

    @BeforeEach
    fun capturePort() {
        serverPort = env.getProperty("local.server.port")!!.toInt()
    }

    // Lenient status handling: assertions check status/body, errors must not throw.
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

    @Test
    @Order(1)
    fun `endpoints reject anonymous calls`() {
        val response = http.get().uri("/api/trips").retrieve().toBodilessEntity()
        assertEquals(401, response.statusCode.value())
    }

    @Test
    @Order(2)
    fun `full hand-in happy path produces registration, outbox row and Kafka message`() {
        post("/api/sim/reset", ResetRequest(), SimStateSnapshot::class.java)

        val trips = get("/api/trips", Array<TripDto>::class.java).toList()
        val lockerStop = trips.single().stops.single { it.deliveryLocationType.name == "LOCKER" }
        val barcode = "DHL-IN-001"

        val session = post("/api/locker/sessions", CreateSessionRequest(lockerStop.id), CreateSessionResponse::class.java).body!!
        assertEquals("NOT_READY", get("/api/locker/sessions/${session.sessionId}", SessionStatusDto::class.java).status)

        // the machine scans the QR
        post("/api/sim/bind", BindRequest(session.qrPayload), SimSessionSnapshot::class.java)
        assertEquals("READY", get("/api/locker/sessions/${session.sessionId}", SessionStatusDto::class.java).status)

        val validation = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/validate",
            LockerActionRequest(barcode),
            ValidationResultDto::class.java,
        ).body!!
        assertTrue(validation.valid)
        assertEquals("S", validation.parcelSize?.name)

        val attempt = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/attempt",
            LockerActionRequest(barcode),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(SimSessionState.HAND_IN_DOOR_OPEN, attempt.simState)
        val compartmentNr = assertNotNull(attempt.compartment).nr

        post("/api/sim/door", DoorRequest(compartmentNr, DoorAction.CLOSE), SimSessionSnapshot::class.java)

        val confirm = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/confirm",
            LockerActionRequest(barcode),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(false, confirm.reconciled)
        assertEquals(SimSessionState.HAND_IN_COMPLETED, confirm.simState)

        // registration + outbox written in the same transaction
        assertEquals(1, countRegistrations(session.sessionId, barcode))
        assertEquals(1, countOutbox(barcode))
        assertEquals("HANDED_IN", jdbc.queryForObject("select status from parcel where barcode = ?", String::class.java, barcode))

        // duplicate confirm: locker says 409 (illegal transition) → reconciled truth, no second outbox row
        val duplicate = post(
            "/api/locker/sessions/${session.sessionId}/hand-in/confirm",
            LockerActionRequest(barcode),
            LockerActionResponse::class.java,
        ).body!!
        assertEquals(true, duplicate.reconciled)
        assertEquals(1, countOutbox(barcode))

        // outbox publisher pushes to Redpanda
        await().atMost(Duration.ofSeconds(15)).until {
            jdbc.queryForObject(
                "select count(*) from outbox where aggregate_id = ? and published_at is not null",
                Int::class.java,
                barcode,
            ) == 1
        }
        assertKafkaMessageFor(barcode)
    }

    @Test
    @Order(3)
    fun `two parallel continues - one wins, one gets the reconciled truth`() {
        val sessionId = UUID.fromString(
            jdbc.queryForObject("select id from locker_session order by created_at desc limit 1", String::class.java)!!,
        )
        val results = runBlocking {
            (1..2).map {
                async(Dispatchers.IO) {
                    post(
                        "/api/locker/sessions/$sessionId/hand-in/continue",
                        null,
                        LockerActionResponse::class.java,
                    )
                }
            }.awaitAll()
        }
        assertTrue(results.all { it.statusCode.is2xxSuccessful }, "both calls must answer 200")
        val reconciled = results.mapNotNull { it.body }.count { it.reconciled }
        assertEquals(1, reconciled, "exactly one of the two continues hits the conflict and reconciles")
    }

    private fun countRegistrations(sessionId: UUID, barcode: String): Int = jdbc.queryForObject(
        "select count(*) from delivery_registration where session_id = ? and barcode = ?",
        Int::class.java,
        sessionId,
        barcode,
    )!!

    private fun countOutbox(barcode: String): Int = jdbc.queryForObject(
        "select count(*) from outbox where aggregate_id = ?",
        Int::class.java,
        barcode,
    )!!

    private fun assertKafkaMessageFor(barcode: String) {
        val consumerProps = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "it-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        )
        KafkaConsumer(consumerProps, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf(OutboxPublisher.TOPIC))
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val records = consumer.poll(Duration.ofMillis(500))
                if (records.any { it.key() == barcode && it.value().contains("HANDED_IN") }) return
            }
        }
        throw AssertionError("no delivery-events message for $barcode within 15s")
    }
}
