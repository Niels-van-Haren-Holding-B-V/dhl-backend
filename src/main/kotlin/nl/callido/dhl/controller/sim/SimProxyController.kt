package nl.callido.dhl.controller.sim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import nl.callido.dhl.client.LockerSimClient
import nl.callido.dhl.dto.sim.BindRequest
import nl.callido.dhl.dto.sim.DoorRequest
import nl.callido.dhl.dto.sim.FailureRequest
import nl.callido.dhl.dto.sim.ResetRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.dto.trips.ParcelAnnouncement
import nl.callido.dhl.service.sim.DemoResetService
import nl.callido.dhl.service.trips.ParcelIntakeConsumer
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.http.HttpStatus
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

/**
 * Thin authenticated passthrough so the parcel-machine page works with a
 * courier-realm token only — the locker realm never reaches the browser.
 * Demo-only surface, disabled in production-like setups via SIM_ENABLED.
 */
@RestController
@RequestMapping("/api/sim")
@ConditionalOnBooleanProperty("dhl.sim-passthrough.enabled")
class SimProxyController(
    private val client: LockerSimClient,
    private val demoReset: DemoResetService,
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {

    @PostMapping("/bind")
    suspend fun bind(@RequestBody req: BindRequest): SimSessionSnapshot = client.simBind(req)

    @GetMapping("/state")
    suspend fun state(): SimStateSnapshot = client.simState()

    @PostMapping("/door")
    suspend fun door(@RequestBody req: DoorRequest): SimSessionSnapshot = client.simDoor(req)

    @PostMapping("/failures")
    suspend fun failures(@RequestBody req: FailureRequest): SimStateSnapshot = client.simFailures(req)

    /**
     * Demo stand-in for the upstream planning system: publishes the
     * announcement to the `parcel-intake` topic, where the regular Kafka
     * ingestion picks it up. Deliberately 202: the parcel appears in the
     * trips a moment later — eventual consistency, like the real thing.
     */
    @PostMapping("/parcels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun announceParcel(@RequestBody req: ParcelAnnouncement): ParcelAnnouncement {
        withContext(Dispatchers.IO) {
            kafka.send(ParcelIntakeConsumer.TOPIC, req.barcode, objectMapper.writeValueAsString(req)).await()
        }
        return req
    }

    /** Full demo reset: the sim AND the seeded data (parcels, sessions, registrations). */
    @PostMapping("/reset")
    suspend fun reset(@RequestBody(required = false) req: ResetRequest?): SimStateSnapshot {
        withContext(Dispatchers.IO) { demoReset.resetDemoData() }
        return client.simReset(req ?: ResetRequest())
    }
}
