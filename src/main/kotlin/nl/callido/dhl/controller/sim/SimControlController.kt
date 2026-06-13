package nl.callido.dhl.controller.sim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.callido.dhl.dto.sim.BindRequest
import nl.callido.dhl.dto.sim.CompartmentDto
import nl.callido.dhl.dto.sim.DoorRequest
import nl.callido.dhl.dto.sim.FailureRequest
import nl.callido.dhl.dto.sim.ReserveRequest
import nl.callido.dhl.dto.sim.ResetRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.service.sim.LockerSimEngine
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/locker-api/sim")
@ConditionalOnBooleanProperty("dhl.locker-sim.serve")
class SimControlController(private val engine: LockerSimEngine) {

    @PostMapping("/bind")
    suspend fun bind(@RequestBody req: BindRequest): SimSessionSnapshot = sim { engine.bind(req.qrCode) }

    @GetMapping("/state")
    suspend fun state(): SimStateSnapshot = sim { engine.fullState() }

    @PostMapping("/door")
    suspend fun door(@RequestBody req: DoorRequest): SimSessionSnapshot = sim { engine.door(req.compartmentNr, req.action) }

    @PostMapping("/failures")
    suspend fun failures(@RequestBody req: FailureRequest): SimStateSnapshot = sim { engine.setFailure(req.mode, req.enabled) }

    @PostMapping("/reserve")
    suspend fun reserve(@RequestBody req: ReserveRequest): CompartmentDto = sim { engine.reserve(req.barcode, req.size) }

    @PostMapping("/reset")
    suspend fun reset(@RequestBody(required = false) req: ResetRequest?): SimStateSnapshot = sim { engine.reset(req?.config) }

    private suspend fun <T> sim(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
