package nl.callido.dhl.integration

import nl.callido.dhl.client.LockerSimBaseUrl
import nl.callido.dhl.client.LockerTokenProvider
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.dto.trips.ParcelAnnouncement
import nl.callido.dhl.dto.trips.TripDto
import nl.callido.dhl.service.trips.ParcelIntakeConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
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
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kafka ingestion from "upstream planning": parcels announced on the
 * parcel-intake topic must appear as EXPECTED hand-in parcels on the LOCKER
 * stop — exercised through the BFF demo endpoint AND through raw Kafka, so we
 * prove the consumer and not just the controller. Also proves idempotency and
 * that a poison-pill message does not block the partition.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["dhl.reaper.enabled=false"],
)
@Testcontainers(disabledWithoutDocker = true)
class ParcelIntakeIntegrationTest {

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

        @JvmStatic fun stubCourierDecoder(): ReactiveJwtDecoder = stubDecoder("koerier")

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

    @BeforeEach
    fun capturePort() {
        serverPort = env.getProperty("local.server.port")!!.toInt()
    }

    private val http: RestClient by lazy {
        RestClient.builder()
            .baseUrl("http://localhost:${env.getProperty("local.server.port")}")
            .defaultStatusHandler({ true }) { _, _ -> }
            .build()
    }

    @Test
    fun `announced parcel lands as EXPECTED hand-in on the LOCKER stop, idempotent, poison-pill safe`() {
        // 1) a malformed message FIRST: must be skipped, never block the partition
        rawProducer().use { producer ->
            producer.send(ProducerRecord(ParcelIntakeConsumer.TOPIC, "poison", "this is not json")).get()
        }

        // 2) announce through the BFF demo endpoint (frontend path)
        val announcement = ParcelAnnouncement(
            barcode = "DHL-IN-KAFKA-1",
            lengthCm = 30,
            widthCm = 20,
            heightCm = 10,
            weightG = 900,
        )
        val response = http.post().uri("/api/sim/parcels")
            .headers { it.setBearerAuth("test-token") }
            .contentType(MediaType.APPLICATION_JSON)
            .body(announcement)
            .retrieve().toBodilessEntity()
        assertEquals(202, response.statusCode.value(), "announcement is accepted, not processed inline")

        // 3) ingestion is asynchronous: the parcel appears via the consumer
        await().atMost(Duration.ofSeconds(20)).until {
            jdbc.queryForObject(
                "select count(*) from parcel where barcode = ?",
                Int::class.java,
                "DHL-IN-KAFKA-1",
            ) == 1
        }
        val row = jdbc.queryForMap("select * from parcel where barcode = ?", "DHL-IN-KAFKA-1")
        assertEquals("EXPECTED", row["status"])
        assertEquals("HAND_IN", row["direction"])
        assertEquals(30, row["length_cm"])

        // pre-announcement: the machine reserved a fitting door, visible on the machine page
        val machine = http.get().uri("/api/sim/state")
            .headers { it.setBearerAuth("test-token") }
            .retrieve().toEntity(SimStateSnapshot::class.java).body!!
        val reserved = machine.compartments.single { it.barcode == "DHL-IN-KAFKA-1" }
        assertEquals("RESERVED", reserved.state.name)
        assertTrue(reserved.size.name in listOf("S", "M"), "a 30x20x10 parcel reserves the smallest fitting door")

        // it is attached to the seeded LOCKER stop and visible through /api/trips
        val trips = http.get().uri("/api/trips")
            .headers { it.setBearerAuth("test-token") }
            .retrieve().toEntity(Array<TripDto>::class.java).body!!.toList()
        val lockerStop = trips.single().stops.single { it.deliveryLocationType.name == "LOCKER" }
        assertEquals(1, lockerStop.parcels.count { it.barcode == "DHL-IN-KAFKA-1" })

        // 4) duplicate announcement is a no-op (replay safety). Send the dup,
        // then a sentinel; once the sentinel is ingested the dup was processed.
        rawProducer().use { producer ->
            producer.send(
                ProducerRecord(
                    ParcelIntakeConsumer.TOPIC,
                    announcement.barcode,
                    """{"barcode":"DHL-IN-KAFKA-1","lengthCm":30,"widthCm":20,"heightCm":10,"weightG":900}""",
                ),
            ).get()
            producer.send(
                ProducerRecord(
                    ParcelIntakeConsumer.TOPIC,
                    "DHL-IN-KAFKA-2",
                    """{"barcode":"DHL-IN-KAFKA-2","lengthCm":55,"widthCm":40,"heightCm":25,"weightG":4000}""",
                ),
            ).get()
        }
        await().atMost(Duration.ofSeconds(20)).until {
            jdbc.queryForObject("select count(*) from parcel where barcode = ?", Int::class.java, "DHL-IN-KAFKA-2") == 1
        }
        assertEquals(
            1,
            jdbc.queryForObject("select count(*) from parcel where barcode = ?", Int::class.java, "DHL-IN-KAFKA-1"),
            "duplicate announcement must not create a second parcel",
        )

        // 5) reset removes announced parcels again; seeded parcels survive
        http.post().uri("/api/sim/reset")
            .headers { it.setBearerAuth("test-token") }
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .retrieve().toBodilessEntity()
        assertEquals(
            0,
            jdbc.queryForObject("select count(*) from parcel where barcode like 'DHL-IN-KAFKA-%'", Int::class.java),
            "reset must remove parcels announced via Kafka intake",
        )
        assertEquals(3, jdbc.queryForObject("select count(*) from parcel", Int::class.java), "seed stays intact")
    }

    private fun rawProducer(): KafkaProducer<String, String> = KafkaProducer(
        mapOf<String, Any>(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to redpanda.bootstrapServers),
        StringSerializer(),
        StringSerializer(),
    )
}
