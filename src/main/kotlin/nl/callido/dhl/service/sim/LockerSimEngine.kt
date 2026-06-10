package nl.callido.dhl.service.sim

import nl.callido.dhl.common.ParcelSize
import nl.callido.dhl.dto.sim.CompartmentState
import nl.callido.dhl.dto.sim.CompartmentDto
import nl.callido.dhl.dto.sim.DoorAction
import nl.callido.dhl.dto.sim.FailureMode
import nl.callido.dhl.dto.sim.InitResponse
import nl.callido.dhl.dto.sim.MutationRequest
import nl.callido.dhl.dto.sim.SimLogEntry
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.SimSessionState
import nl.callido.dhl.dto.sim.SimSessionDto
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.dto.sim.StatusResponse
import nl.callido.dhl.dto.sim.ValidateRequest
import nl.callido.dhl.dto.sim.ValidateResponse
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.EnumSet
import java.util.UUID

/**
 * In-memory simulation of one physical parcel machine. Deliberately stateful
 * and single-instance: a real machine is exactly that. All entry points are
 * synchronized; the interesting concurrency lives in the BFF, not here.
 */
@Component
class LockerSimEngine {

    private class SimSession(
        val id: String,
        val qrCode: String,
        var state: SimSessionState,
        var version: Int,
        var boundAt: Instant?,
    )

    private class Compartment(val spec: CompartmentSpec, var state: CompartmentState, var barcode: String?) {
        fun toDto() = CompartmentDto(
            nr = spec.nr, label = spec.label, column = spec.column, address = spec.address,
            backAddress = spec.backAddress, size = spec.size, enabled = spec.enabled,
            state = state, barcode = barcode,
        )
    }

    private data class Extras(
        val compartment: Compartment? = null,
        val parcelPresent: Boolean? = null,
        val failure: String? = null,
    )

    private var configName: String = LockerConfigurations.DEFAULT
    private var compartments: MutableList<Compartment> = mutableListOf()
    private var session: SimSession? = null
    private var activeNr: Int? = null
    private val failures: EnumSet<FailureMode> = EnumSet.noneOf(FailureMode::class.java)
    private val eventLog = ArrayDeque<SimLogEntry>()
    /** After report-incorrect-compartment-size: barcode → size that proved too small. */
    private val sizeHints = mutableMapOf<String, ParcelSize>()

    init {
        loadConfig(LockerConfigurations.DEFAULT)
    }

    // ---- courier API ----

    @Synchronized
    fun init(): InitResponse {
        maybeDelay()
        // A new courier session displaces a dangling previous one (demo-friendly).
        compartments.filter { it.state == CompartmentState.RESERVED || it.state == CompartmentState.DOOR_OPEN }
            .forEach { it.state = CompartmentState.FREE; it.barcode = null }
        activeNr = null
        val id = UUID.randomUUID().toString()
        val s = SimSession(id, "DHL-LOCKER:$id", SimSessionState.CREATED, 0, null)
        session = s
        log("session/init", "new session $id")
        return InitResponse(s.id, s.qrCode)
    }

    @Synchronized
    fun status(sessionId: String): StatusResponse {
        maybeDelay()
        val s = requireSession(sessionId)
        // Not logged: the BFF polls this; it would drown the event log.
        return StatusResponse(if (s.state == SimSessionState.CREATED) "NOT_READY" else "READY", s.state, s.version)
    }

    @Synchronized
    fun finished(req: MutationRequest): SimSessionSnapshot =
        mutate("session/finished", SimEvent.FINISH, req) { _ ->
            activeCompartmentOrNull()?.let {
                if (it.state == CompartmentState.DOOR_OPEN || it.state == CompartmentState.RESERVED) {
                    it.state = CompartmentState.FREE
                    it.barcode = null
                }
            }
            activeNr = null
            Extras()
        }.also { session?.state = SimSessionState.FINISHED }

    @Synchronized
    fun validate(req: ValidateRequest): ValidateResponse {
        maybeDelay()
        val s = requireSession(req.sessionId)
        if (!SessionStateMachine.isAllowed(s.state, SimEvent.HAND_IN_VALIDATE)) {
            throw SimEngineConflictException("ILLEGAL_TRANSITION ${s.state} -> HAND_IN_VALIDATE", snapshot(Extras()))
        }
        val response = when {
            !req.barcode.startsWith("DHL-") -> ValidateResponse(false, "UNKNOWN_BARCODE")
            req.size == null -> ValidateResponse(false, "NO_FITTING_SIZE")
            else -> {
                val candidate = selectCompartment(req.barcode, req.size, honourFailure = false)
                if (candidate == null) ValidateResponse(false, "NO_CAPACITY")
                else ValidateResponse(true, suggestedSize = candidate.spec.size)
            }
        }
        log("hand-in/validate", "barcode=${req.barcode} valid=${response.valid} ${response.reason ?: ""}".trim())
        return response
    }

    @Synchronized
    fun handInAttempt(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-in/attempt", SimEvent.HAND_IN_ATTEMPT, req) { s ->
            val barcode = req.barcode ?: throw SimEngineRejectedException("MISSING_BARCODE", "barcode is required")
            val size = req.size ?: throw SimEngineRejectedException("MISSING_SIZE", "size is required")
            val comp = selectCompartment(barcode, size, honourFailure = true)
                ?: throw SimEngineRejectedException("NO_COMPARTMENT_AVAILABLE", "no free compartment for size $size")
            comp.state = CompartmentState.DOOR_OPEN
            comp.barcode = barcode
            activeNr = comp.spec.nr
            s.state = SimSessionState.HAND_IN_DOOR_OPEN
            Extras(comp)
        }

    @Synchronized
    fun handInConfirm(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-in/confirm", SimEvent.HAND_IN_CONFIRM, req) { s ->
            if (FailureMode.COMPARTMENT_DEFECT in failures) {
                throw SimEngineRejectedException("COMPARTMENT_DEFECT", "compartment reported a hardware defect")
            }
            val comp = activeCompartment()
            req.barcode?.let { sizeHints.remove(it) }
            s.state = SimSessionState.HAND_IN_COMPLETED
            Extras(comp)
        }

    @Synchronized
    fun handInContinue(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-in/continue", SimEvent.HAND_IN_CONTINUE, req) { s ->
            activeNr = null
            s.state = SimSessionState.READY
            Extras()
        }

    @Synchronized
    fun handInReportSize(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-in/report-incorrect-compartment-size", SimEvent.HAND_IN_REPORT_SIZE, req) { s ->
            val comp = activeCompartment()
            comp.barcode?.let { sizeHints[it] = comp.spec.size }
            comp.state = CompartmentState.FREE
            comp.barcode = null
            activeNr = null
            s.state = SimSessionState.READY
            Extras(comp)
        }

    @Synchronized
    fun handInReportIssue(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-in/report-compartment-issue", SimEvent.HAND_IN_REPORT_ISSUE, req) { s ->
            val comp = activeCompartment()
            comp.state = CompartmentState.DEFECT
            comp.barcode = null
            activeNr = null
            s.state = SimSessionState.READY
            Extras(comp)
        }

    @Synchronized
    fun handInReopen(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-in/reopen-compartment", SimEvent.HAND_IN_REOPEN, req) { s ->
            val comp = activeCompartment()
            comp.state = CompartmentState.DOOR_OPEN
            s.state = SimSessionState.HAND_IN_DOOR_OPEN
            Extras(comp)
        }

    @Synchronized
    fun handOutStart(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-out/start", SimEvent.HAND_OUT_START, req) { s ->
            val barcode = req.barcode ?: throw SimEngineRejectedException("MISSING_BARCODE", "barcode is required")
            val comp = compartments.find { it.state == CompartmentState.OCCUPIED && it.barcode == barcode }
                ?: throw SimEngineRejectedException("UNKNOWN_PARCEL", "no occupied compartment holds $barcode")
            comp.state = CompartmentState.DOOR_OPEN
            activeNr = comp.spec.nr
            s.state = SimSessionState.HAND_OUT_DOOR_OPEN
            val present = FailureMode.PARCEL_MISSING !in failures
            if (!present) comp.barcode = null
            Extras(comp, parcelPresent = present)
        }

    @Synchronized
    fun handOutConfirm(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-out/confirm", SimEvent.HAND_OUT_CONFIRM, req) { s ->
            s.state = SimSessionState.HAND_OUT_COMPLETED
            Extras()
        }

    @Synchronized
    fun handOutContinue(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-out/continue", SimEvent.HAND_OUT_CONTINUE, req) { s ->
            activeNr = null
            s.state = SimSessionState.READY
            Extras()
        }

    @Synchronized
    fun handOutReportMissing(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-out/report-missing", SimEvent.HAND_OUT_REPORT_MISSING, req) { s ->
            val comp = activeCompartment()
            comp.state = CompartmentState.FREE
            comp.barcode = null
            activeNr = null
            s.state = SimSessionState.READY
            Extras(comp)
        }

    @Synchronized
    fun handOutReportIssue(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-out/report-compartment-issue", SimEvent.HAND_OUT_REPORT_ISSUE, req) { s ->
            val comp = activeCompartment()
            comp.state = CompartmentState.DEFECT
            activeNr = null
            s.state = SimSessionState.READY
            Extras(comp)
        }

    @Synchronized
    fun handOutAbort(req: MutationRequest): SimSessionSnapshot =
        mutate("hand-out/abort", SimEvent.HAND_OUT_ABORT, req) { s ->
            activeCompartmentOrNull()?.let { it.state = CompartmentState.OCCUPIED }
            activeNr = null
            s.state = SimSessionState.READY
            Extras()
        }

    // ---- machine-side (sim control) API ----

    @Synchronized
    fun bind(qrCode: String): SimSessionSnapshot {
        maybeDelay()
        val s = session?.takeIf { it.qrCode == qrCode }
            ?: throw SimEngineRejectedException("UNKNOWN_QR", "QR code does not match the active session")
        if (!SessionStateMachine.isAllowed(s.state, SimEvent.BIND)) {
            throw SimEngineConflictException("ILLEGAL_TRANSITION ${s.state} -> BIND", snapshot(Extras()))
        }
        s.state = SimSessionState.READY
        s.boundAt = Instant.now()
        s.version++
        log("sim/bind", "session ${s.id} bound")
        return snapshot(Extras())
    }

    @Synchronized
    fun door(compartmentNr: Int, action: DoorAction): SimSessionSnapshot {
        maybeDelay()
        val s = session ?: throw SimEngineRejectedException("NO_SESSION", "no active session")
        val comp = compartments.find { it.spec.nr == compartmentNr }
            ?: throw SimEngineRejectedException("UNKNOWN_COMPARTMENT", "no compartment $compartmentNr")
        if (action == DoorAction.LEAVE_OPEN) {
            // The courier walks away — nothing happens; the BFF reaper will clean up.
            log("sim/door", "compartment ${comp.spec.label} left open")
            return snapshot(Extras(comp))
        }
        if (FailureMode.DOOR_STUCK in failures) {
            log("sim/door", "compartment ${comp.spec.label} DOOR_STUCK")
            return snapshot(Extras(comp, failure = "DOOR_STUCK"))
        }
        if (comp.spec.nr != activeNr) {
            throw SimEngineRejectedException("WRONG_COMPARTMENT", "compartment $compartmentNr is not part of the current action")
        }
        return when (s.state) {
            SimSessionState.HAND_IN_DOOR_OPEN -> machineMutate("sim/door", SimEvent.DOOR_CLOSED) {
                comp.state = CompartmentState.OCCUPIED
                s.state = SimSessionState.HAND_IN_AWAITING_CONFIRM
                Extras(comp)
            }
            SimSessionState.HAND_OUT_DOOR_OPEN -> machineMutate("sim/door", SimEvent.DOOR_CLOSED) {
                comp.state = CompartmentState.FREE
                comp.barcode = null
                s.state = SimSessionState.HAND_OUT_AWAITING_CONFIRM
                Extras(comp)
            }
            else -> throw SimEngineConflictException("ILLEGAL_TRANSITION ${s.state} -> DOOR_CLOSED", snapshot(Extras()))
        }
    }

    @Synchronized
    fun setFailure(mode: FailureMode, enabled: Boolean): SimStateSnapshot {
        if (enabled) failures.add(mode) else failures.remove(mode)
        log("sim/failures", "$mode=${if (enabled) "on" else "off"}")
        return fullState()
    }

    @Synchronized
    fun reset(config: String?): SimStateSnapshot {
        val name = config ?: configName
        loadConfig(name)
        session = null
        activeNr = null
        failures.clear()
        sizeHints.clear()
        eventLog.clear()
        log("sim/reset", "reset to config $name")
        return fullState()
    }

    @Synchronized
    fun fullState(): SimStateSnapshot = SimStateSnapshot(
        config = configName,
        session = session?.let { SimSessionDto(it.id, it.state, it.version, it.boundAt) },
        compartments = compartments.map { it.toDto() },
        activeFailures = failures.toList(),
        eventLog = eventLog.toList(),
    )

    // ---- internals ----

    private fun loadConfig(name: String) {
        val specs = LockerConfigurations.byName[name]
            ?: throw SimEngineRejectedException("UNKNOWN_CONFIG", "no locker configuration named $name")
        configName = name
        compartments = specs.map { Compartment(it, CompartmentState.FREE, null) }.toMutableList()
        // The machine starts with one parcel waiting for hand-out, like a real
        // locker that received a consumer drop-off overnight.
        compartments.first { it.spec.enabled && it.spec.size == ParcelSize.M }.apply {
            state = CompartmentState.OCCUPIED
            barcode = LockerConfigurations.PRELOADED_HAND_OUT_BARCODE
        }
    }

    private fun mutate(endpoint: String, event: SimEvent, req: MutationRequest, block: (SimSession) -> Extras): SimSessionSnapshot {
        maybeDelay()
        val s = requireSession(req.sessionId)
        if (failures.remove(FailureMode.FORCE_409)) {
            log(endpoint, "FORCE_409 fired")
            throw SimEngineConflictException("FORCE_409", snapshot(Extras()))
        }
        if (req.version != s.version) {
            throw SimEngineConflictException("STALE_VERSION expected=${req.version} actual=${s.version}", snapshot(Extras()))
        }
        if (!SessionStateMachine.isAllowed(s.state, event)) {
            throw SimEngineConflictException("ILLEGAL_TRANSITION ${s.state} -> $event", snapshot(Extras()))
        }
        val extras = block(s)
        s.version++
        log(endpoint, summarize(req, extras))
        return snapshot(extras)
    }

    private fun machineMutate(endpoint: String, event: SimEvent, block: () -> Extras): SimSessionSnapshot {
        // Machine-side actions (scan, door sensor) carry no version: hardware
        // does not do optimistic locking. They still advance the version so
        // concurrent courier mutations turn into honest 409s.
        val s = session!!
        if (!SessionStateMachine.isAllowed(s.state, event)) {
            throw SimEngineConflictException("ILLEGAL_TRANSITION ${s.state} -> $event", snapshot(Extras()))
        }
        val extras = block()
        s.version++
        log(endpoint, summarize(null, extras))
        return snapshot(extras)
    }

    private fun selectCompartment(barcode: String, size: ParcelSize, honourFailure: Boolean): Compartment? {
        val hinted = sizeHints[barcode]
        val minSize = if (hinted != null && hinted >= size) {
            ParcelSize.entries.getOrNull(hinted.ordinal + 1) ?: return null
        } else size
        val free = compartments.filter { it.spec.enabled && it.state == CompartmentState.FREE }
        if (honourFailure && FailureMode.SIZE_TOO_SMALL in failures) {
            // Sabotage: deliberately hand out a compartment one or more sizes
            // too small so the report-incorrect-compartment-size flow can run.
            free.filter { it.spec.size < minSize }.maxByOrNull { it.spec.size }?.let { return it }
        }
        return free.filter { it.spec.size >= minSize }.minByOrNull { it.spec.size }
    }

    private fun requireSession(sessionId: String): SimSession =
        session?.takeIf { it.id == sessionId }
            ?: throw SimEngineRejectedException("UNKNOWN_SESSION", "session $sessionId is not active")

    private fun activeCompartment(): Compartment = activeCompartmentOrNull()
        ?: throw SimEngineRejectedException("NO_ACTIVE_COMPARTMENT", "no compartment involved in the current action")

    private fun activeCompartmentOrNull(): Compartment? = activeNr?.let { nr -> compartments.find { it.spec.nr == nr } }

    private fun snapshot(extras: Extras): SimSessionSnapshot {
        val s = session!!
        return SimSessionSnapshot(
            sessionId = s.id, state = s.state, version = s.version, boundAt = s.boundAt,
            compartment = extras.compartment?.toDto(),
            parcelPresent = extras.parcelPresent, failure = extras.failure,
        )
    }

    private fun summarize(req: MutationRequest?, extras: Extras): String = listOfNotNull(
        req?.barcode?.let { "barcode=$it" },
        extras.compartment?.let { "compartment=${it.spec.label}" },
        extras.parcelPresent?.let { "parcelPresent=$it" },
        extras.failure,
    ).joinToString(" ").ifEmpty { "ok" }

    private fun log(endpoint: String, summary: String) {
        eventLog.addLast(SimLogEntry(Instant.now(), endpoint, summary, session?.state, session?.version))
        while (eventLog.size > 50) eventLog.removeFirst()
    }

    private fun maybeDelay() {
        if (FailureMode.SLOW_NETWORK in failures) Thread.sleep(3000)
    }
}
