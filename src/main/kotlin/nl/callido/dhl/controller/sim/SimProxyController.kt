package nl.callido.dhl.controller.sim

import jakarta.validation.Valid
import kotlinx.coroutines.Dispatchers
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
import nl.callido.dhl.service.trips.ParcelAnnouncementService
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sim")
@ConditionalOnBooleanProperty("dhl.sim-passthrough.enabled")
class SimProxyController(
    private val client: LockerSimClient,
    private val demoReset: DemoResetService,
    private val announcements: ParcelAnnouncementService,
) {

    @PostMapping("/bind")
    suspend fun bind(@RequestBody req: BindRequest): SimSessionSnapshot = client.simBind(req)

    @GetMapping("/state")
    suspend fun state(): SimStateSnapshot = client.simState()

    @PostMapping("/door")
    suspend fun door(@RequestBody req: DoorRequest): SimSessionSnapshot = client.simDoor(req)

    @PostMapping("/failures")
    suspend fun failures(@RequestBody req: FailureRequest): SimStateSnapshot = client.simFailures(req)

    @PostMapping("/parcels")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun announceParcel(@Valid @RequestBody req: ParcelAnnouncement): ParcelAnnouncement = announcements.announce(req)

    @PostMapping("/reset")
    suspend fun reset(@RequestBody(required = false) req: ResetRequest?): SimStateSnapshot {
        withContext(Dispatchers.IO) { demoReset.resetDemoData() }
        return client.simReset(req ?: ResetRequest())
    }
}
