package nl.callido.dhl.controller.sim

import jakarta.validation.Valid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import nl.callido.dhl.client.LockerSimClient
import nl.callido.dhl.client.SimRejectedException
import nl.callido.dhl.common.ParcelSize
import nl.callido.dhl.dto.sim.BindRequest
import nl.callido.dhl.dto.sim.DoorRequest
import nl.callido.dhl.dto.sim.FailureRequest
import nl.callido.dhl.dto.sim.ReserveRequest
import nl.callido.dhl.dto.sim.ResetRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.dto.trips.ParcelAnnouncement
import nl.callido.dhl.repository.ParcelRepository
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
 * Disabled in production-like setups via SIM_ENABLED.
 */
@RestController
@RequestMapping("/api/sim")
@ConditionalOnBooleanProperty("dhl.sim-passthrough.enabled")
class SimProxyController(
    private val client: LockerSimClient,
    private val demoReset: DemoResetService,
    private val kafka: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val parcels: ParcelRepository,
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
     * Stand-in for the upstream planning system. Validation and the capacity
     * reservation happen SYNCHRONOUSLY, so a duplicate barcode, impossible
     * dimensions or a full machine reject right here — a 202 means the
     * parcel is truly planned. The announcement still travels over the
     * parcel-intake topic; the consumer's reserve is idempotent.
     */
    @PostMapping("/parcels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun announceParcel(@Valid @RequestBody req: ParcelAnnouncement): ParcelAnnouncement {
        val size = ParcelSize.forDimensions(req.lengthCm, req.widthCm, req.heightCm)
            ?: throw SimRejectedException("NO_FITTING_SIZE", "no compartment size fits these dimensions")
        if (withContext(Dispatchers.IO) { parcels.findByBarcode(req.barcode) } != null) {
            throw SimRejectedException("DUPLICATE_BARCODE", "parcel ${req.barcode} already exists")
        }
        client.simReserve(ReserveRequest(req.barcode, size))
        withContext(Dispatchers.IO) {
            kafka.send(ParcelIntakeConsumer.TOPIC, req.barcode, objectMapper.writeValueAsString(req)).await()
        }
        return req
    }

    /** Full reset: the sim AND the seeded data (parcels, sessions, registrations). */
    @PostMapping("/reset")
    suspend fun reset(@RequestBody(required = false) req: ResetRequest?): SimStateSnapshot {
        withContext(Dispatchers.IO) { demoReset.resetDemoData() }
        return client.simReset(req ?: ResetRequest())
    }
}
