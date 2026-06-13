package nl.callido.dhl.service.sim

import nl.callido.dhl.dto.sim.SimSessionState
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Expected table is duplicated (not derived from production) on purpose: any change to the production table fails exactly one named case.
class SessionStateMachineTest {

    private val expected: Map<SimSessionState, Set<SimEvent>> = mapOf(
        SimSessionState.CREATED to setOf(SimEvent.BIND, SimEvent.FINISH),
        SimSessionState.READY to setOf(
            SimEvent.HAND_IN_VALIDATE,
            SimEvent.HAND_IN_ATTEMPT,
            SimEvent.HAND_OUT_START,
            SimEvent.FINISH,
        ),
        SimSessionState.HAND_IN_DOOR_OPEN to setOf(
            SimEvent.DOOR_CLOSED,
            SimEvent.HAND_IN_REPORT_SIZE,
            SimEvent.HAND_IN_REPORT_ISSUE,
        ),
        SimSessionState.HAND_IN_AWAITING_CONFIRM to setOf(
            SimEvent.HAND_IN_CONFIRM,
            SimEvent.HAND_IN_REOPEN,
            SimEvent.HAND_IN_REPORT_ISSUE,
        ),
        SimSessionState.HAND_IN_COMPLETED to setOf(SimEvent.HAND_IN_CONTINUE, SimEvent.FINISH),
        SimSessionState.HAND_OUT_DOOR_OPEN to setOf(
            SimEvent.DOOR_CLOSED,
            SimEvent.HAND_OUT_REPORT_MISSING,
            SimEvent.HAND_OUT_REPORT_ISSUE,
            SimEvent.HAND_OUT_ABORT,
        ),
        SimSessionState.HAND_OUT_AWAITING_CONFIRM to setOf(
            SimEvent.HAND_OUT_CONFIRM,
            SimEvent.HAND_OUT_REPORT_ISSUE,
            SimEvent.HAND_OUT_ABORT,
        ),
        SimSessionState.HAND_OUT_COMPLETED to setOf(SimEvent.HAND_OUT_CONTINUE, SimEvent.FINISH),
        SimSessionState.FINISHED to emptySet(),
    )

    @TestFactory
    fun `every state-event combination matches the expected table`(): List<DynamicTest> = SimSessionState.entries.flatMap { state ->
        SimEvent.entries.map { event ->
            val legal = expected.getValue(state).contains(event)
            DynamicTest.dynamicTest("$state -> $event should be ${if (legal) "legal" else "illegal"}") {
                assertEquals(legal, SessionStateMachine.isAllowed(state, event))
            }
        }
    }

    @Test
    fun `every state has an entry in the transition table`() {
        assertTrue(SessionStateMachine.transitions.keys.containsAll(SimSessionState.entries))
    }

    @Test
    fun `finished is terminal`() {
        assertTrue(SessionStateMachine.transitions.getValue(SimSessionState.FINISHED).isEmpty())
    }
}
