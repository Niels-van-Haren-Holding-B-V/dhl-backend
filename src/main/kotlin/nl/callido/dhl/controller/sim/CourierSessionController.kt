package nl.callido.dhl.controller.sim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.callido.dhl.dto.sim.InitRequest
import nl.callido.dhl.dto.sim.InitResponse
import nl.callido.dhl.dto.sim.MutationRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.StatusResponse
import nl.callido.dhl.dto.sim.ValidateRequest
import nl.callido.dhl.dto.sim.ValidateResponse
import nl.callido.dhl.service.sim.LockerSimEngine
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Handlers hop to Dispatchers.IO: the engine is synchronized and SLOW_NETWORK genuinely
// sleeps — never on the event loop.
@RestController
@RequestMapping("/locker-api/courier")
@ConditionalOnBooleanProperty("dhl.locker-sim.serve")
class CourierSessionController(private val engine: LockerSimEngine) {

    @PostMapping("/session/init")
    suspend fun init(@RequestBody(required = false) req: InitRequest?): InitResponse = sim { engine.init() }

    @GetMapping("/session/status")
    suspend fun status(@RequestParam sessionId: String): StatusResponse = sim { engine.status(sessionId) }

    @PostMapping("/session/finished")
    suspend fun finished(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.finished(req) }

    @PostMapping("/hand-in/validate")
    suspend fun validate(@RequestBody req: ValidateRequest): ValidateResponse = sim { engine.validate(req) }

    @PostMapping("/hand-in/attempt")
    suspend fun attempt(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handInAttempt(req) }

    @PostMapping("/hand-in/confirm")
    suspend fun confirm(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handInConfirm(req) }

    @PostMapping("/hand-in/continue")
    suspend fun cont(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handInContinue(req) }

    @PostMapping("/hand-in/report-incorrect-compartment-size")
    suspend fun reportSize(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handInReportSize(req) }

    @PostMapping("/hand-in/report-compartment-issue")
    suspend fun reportIssue(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handInReportIssue(req) }

    @PostMapping("/hand-in/reopen-compartment")
    suspend fun reopen(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handInReopen(req) }

    @PostMapping("/hand-out/start")
    suspend fun handOutStart(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handOutStart(req) }

    @PostMapping("/hand-out/continue")
    suspend fun handOutContinue(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handOutContinue(req) }

    @PostMapping("/hand-out/confirm")
    suspend fun handOutConfirm(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handOutConfirm(req) }

    @PostMapping("/hand-out/report-missing")
    suspend fun handOutReportMissing(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handOutReportMissing(req) }

    @PostMapping("/hand-out/report-compartment-issue")
    suspend fun handOutReportIssue(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handOutReportIssue(req) }

    @PostMapping("/hand-out/abort")
    suspend fun handOutAbort(@RequestBody req: MutationRequest): SimSessionSnapshot = sim { engine.handOutAbort(req) }

    private suspend fun <T> sim(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
