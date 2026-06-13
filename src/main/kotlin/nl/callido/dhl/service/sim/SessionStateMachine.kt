package nl.callido.dhl.service.sim

import nl.callido.dhl.dto.sim.SimSessionState
import nl.callido.dhl.dto.sim.SimSessionState.CREATED
import nl.callido.dhl.dto.sim.SimSessionState.FINISHED
import nl.callido.dhl.dto.sim.SimSessionState.HAND_IN_AWAITING_CONFIRM
import nl.callido.dhl.dto.sim.SimSessionState.HAND_IN_COMPLETED
import nl.callido.dhl.dto.sim.SimSessionState.HAND_IN_DOOR_OPEN
import nl.callido.dhl.dto.sim.SimSessionState.HAND_OUT_AWAITING_CONFIRM
import nl.callido.dhl.dto.sim.SimSessionState.HAND_OUT_COMPLETED
import nl.callido.dhl.dto.sim.SimSessionState.HAND_OUT_DOOR_OPEN
import nl.callido.dhl.dto.sim.SimSessionState.READY

enum class SimEvent {
    BIND,
    HAND_IN_VALIDATE,
    HAND_IN_ATTEMPT,
    DOOR_CLOSED,
    HAND_IN_CONFIRM,
    HAND_IN_CONTINUE,
    HAND_IN_REPORT_SIZE,
    HAND_IN_REPORT_ISSUE,
    HAND_IN_REOPEN,
    HAND_OUT_START,
    HAND_OUT_CONTINUE,
    HAND_OUT_CONFIRM,
    HAND_OUT_REPORT_MISSING,
    HAND_OUT_REPORT_ISSUE,
    HAND_OUT_ABORT,
    FINISH,
}

object SessionStateMachine {

    val transitions: Map<SimSessionState, Set<SimEvent>> = mapOf(
        CREATED to setOf(SimEvent.BIND, SimEvent.FINISH),
        READY to setOf(
            SimEvent.HAND_IN_VALIDATE,
            SimEvent.HAND_IN_ATTEMPT,
            SimEvent.HAND_OUT_START,
            SimEvent.FINISH,
        ),
        HAND_IN_DOOR_OPEN to setOf(
            SimEvent.DOOR_CLOSED,
            SimEvent.HAND_IN_REPORT_SIZE,
            SimEvent.HAND_IN_REPORT_ISSUE,
        ),
        HAND_IN_AWAITING_CONFIRM to setOf(
            SimEvent.HAND_IN_CONFIRM,
            SimEvent.HAND_IN_REOPEN,
            SimEvent.HAND_IN_REPORT_ISSUE,
        ),
        HAND_IN_COMPLETED to setOf(SimEvent.HAND_IN_CONTINUE, SimEvent.FINISH),
        HAND_OUT_DOOR_OPEN to setOf(
            SimEvent.DOOR_CLOSED,
            SimEvent.HAND_OUT_REPORT_MISSING,
            SimEvent.HAND_OUT_REPORT_ISSUE,
            SimEvent.HAND_OUT_ABORT,
        ),
        HAND_OUT_AWAITING_CONFIRM to setOf(
            SimEvent.HAND_OUT_CONFIRM,
            SimEvent.HAND_OUT_REPORT_ISSUE,
            SimEvent.HAND_OUT_ABORT,
        ),
        HAND_OUT_COMPLETED to setOf(SimEvent.HAND_OUT_CONTINUE, SimEvent.FINISH),
        FINISHED to emptySet(),
    )

    fun isAllowed(state: SimSessionState, event: SimEvent): Boolean = transitions[state]?.contains(event) ?: false
}
