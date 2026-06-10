package nl.callido.dhl.service.sim

import nl.callido.dhl.common.ParcelSize
import nl.callido.dhl.dto.sim.CompartmentState
import nl.callido.dhl.dto.sim.DoorAction
import nl.callido.dhl.dto.sim.FailureMode
import nl.callido.dhl.dto.sim.MutationRequest
import nl.callido.dhl.dto.sim.SimSessionState
import nl.callido.dhl.dto.sim.ValidateRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LockerSimEngineTest {

    private lateinit var engine: LockerSimEngine

    @BeforeEach
    fun fresh() {
        engine = LockerSimEngine()
    }

    @Test
    fun `full hand-in happy path walks the state machine and ticks the version`() {
        val init = engine.init()
        assertEquals("NOT_READY", engine.status(init.sessionId).status)

        val bound = engine.bind(init.qrCode)
        assertEquals(SimSessionState.READY, bound.state)
        assertEquals(1, bound.version)

        val validation = engine.validate(ValidateRequest(init.sessionId, "DHL-IN-001", ParcelSize.S))
        assertTrue(validation.valid)
        assertEquals(ParcelSize.S, validation.suggestedSize)

        val attempt = engine.handInAttempt(MutationRequest(init.sessionId, 1, "DHL-IN-001", ParcelSize.S))
        assertEquals(SimSessionState.HAND_IN_DOOR_OPEN, attempt.state)
        assertEquals(2, attempt.version)
        val compartment = assertNotNull(attempt.compartment)
        assertEquals(ParcelSize.S, compartment.size)
        assertEquals(CompartmentState.DOOR_OPEN, compartment.state)

        val closed = engine.door(compartment.nr, DoorAction.CLOSE)
        assertEquals(SimSessionState.HAND_IN_AWAITING_CONFIRM, closed.state)
        assertEquals(3, closed.version)

        val confirmed = engine.handInConfirm(MutationRequest(init.sessionId, 3, "DHL-IN-001"))
        assertEquals(SimSessionState.HAND_IN_COMPLETED, confirmed.state)

        val next = engine.handInContinue(MutationRequest(init.sessionId, 4))
        assertEquals(SimSessionState.READY, next.state)

        val done = engine.finished(MutationRequest(init.sessionId, 5))
        assertEquals(6, done.version)
        assertEquals(SimSessionState.FINISHED, engine.fullState().session!!.state)
    }

    @Test
    fun `stale version yields a conflict`() {
        val init = engine.init()
        engine.bind(init.qrCode)
        val e = assertThrows<SimEngineConflictException> {
            engine.handInAttempt(MutationRequest(init.sessionId, 0, "DHL-IN-001", ParcelSize.S))
        }
        assertTrue(e.reason.startsWith("STALE_VERSION"))
    }

    @Test
    fun `illegal transition yields a conflict`() {
        val init = engine.init()
        engine.bind(init.qrCode)
        val e = assertThrows<SimEngineConflictException> {
            engine.handInConfirm(MutationRequest(init.sessionId, 1, "DHL-IN-001"))
        }
        assertTrue(e.reason.startsWith("ILLEGAL_TRANSITION"))
    }

    @Test
    fun `FORCE_409 fires exactly once`() {
        val init = engine.init()
        engine.bind(init.qrCode)
        engine.setFailure(FailureMode.FORCE_409, true)
        assertThrows<SimEngineConflictException> {
            engine.handInAttempt(MutationRequest(init.sessionId, 1, "DHL-IN-001", ParcelSize.S))
        }
        // version untouched, failure consumed: same call now succeeds
        val attempt = engine.handInAttempt(MutationRequest(init.sessionId, 1, "DHL-IN-001", ParcelSize.S))
        assertEquals(SimSessionState.HAND_IN_DOOR_OPEN, attempt.state)
    }

    @Test
    fun `SIZE_TOO_SMALL hands out an undersized compartment until reported, then a bigger one`() {
        val init = engine.init()
        engine.bind(init.qrCode)
        engine.setFailure(FailureMode.SIZE_TOO_SMALL, true)

        val tooSmall = engine.handInAttempt(MutationRequest(init.sessionId, 1, "DHL-IN-002", ParcelSize.L))
        val small = assertNotNull(tooSmall.compartment)
        assertTrue(small.size < ParcelSize.L, "expected an undersized compartment, got ${small.size}")

        engine.setFailure(FailureMode.SIZE_TOO_SMALL, false)
        val reported = engine.handInReportSize(MutationRequest(init.sessionId, 2))
        assertEquals(SimSessionState.READY, reported.state)

        val retry = engine.handInAttempt(MutationRequest(init.sessionId, 3, "DHL-IN-002", ParcelSize.L))
        val bigger = assertNotNull(retry.compartment)
        assertTrue(bigger.size > small.size, "retry must use a bigger compartment than the reported ${small.size}")
    }

    @Test
    fun `hand-out of the preloaded parcel, including PARCEL_MISSING`() {
        val init = engine.init()
        engine.bind(init.qrCode)

        val start = engine.handOutStart(MutationRequest(init.sessionId, 1, LockerConfigurations.PRELOADED_HAND_OUT_BARCODE))
        assertEquals(SimSessionState.HAND_OUT_DOOR_OPEN, start.state)
        assertEquals(true, start.parcelPresent)
        val comp = assertNotNull(start.compartment)
        assertEquals(ParcelSize.M, comp.size) // preloaded in the first M compartment

        engine.door(comp.nr, DoorAction.CLOSE)
        val confirmed = engine.handOutConfirm(MutationRequest(init.sessionId, 3))
        assertEquals(SimSessionState.HAND_OUT_COMPLETED, confirmed.state)

        // second run: missing parcel
        engine.reset(null)
        val init2 = engine.init()
        engine.bind(init2.qrCode)
        engine.setFailure(FailureMode.PARCEL_MISSING, true)
        val missing = engine.handOutStart(MutationRequest(init2.sessionId, 1, LockerConfigurations.PRELOADED_HAND_OUT_BARCODE))
        assertEquals(false, missing.parcelPresent)
        val cleaned = engine.handOutReportMissing(MutationRequest(init2.sessionId, 2))
        assertEquals(SimSessionState.READY, cleaned.state)
    }

    @Test
    fun `generated configs fill every column exactly and decay toward big sizes`() {
        val state = engine.fullState()
        assertEquals("BIG", state.config)
        assertEquals(ParcelSize.entries.toSet(), state.compartments.map { it.size }.toSet())
        val xxl = state.compartments.filter { it.size == ParcelSize.XXL }
        assertTrue(xxl.all { it.backAddress != null }, "XXL compartments have back doors")
        assertFalse(state.compartments.any { it.size != ParcelSize.XXL && it.backAddress != null })

        for (config in LockerConfigurations.byName.values) {
            // every column packs to exactly the fictional machine height
            config.groupBy { it.column }.values.forEach { column ->
                assertEquals(
                    LockerConfigurations.COLUMN_HEIGHT_CM,
                    column.sumOf { LockerConfigurations.DOOR_PITCH_CM.getValue(it.size) },
                )
            }
            // commercial space management: counts decay toward big sizes.
            // The 5cm sliver fix may shift a door per column one size up,
            // hence the tolerance of one column count.
            val counts = config.groupingBy { it.size }.eachCount()
            val columns = config.maxOf { it.column }
            ParcelSize.entries.zipWithNext().forEach { (smaller, bigger) ->
                assertTrue(
                    (counts[smaller] ?: 0) + columns >= (counts[bigger] ?: 0),
                    "$smaller (${counts[smaller]}) should not be much rarer than $bigger (${counts[bigger]})",
                )
            }
        }
    }

    @Test
    fun `attempt picks the smallest free compartment that fits`() {
        val init = engine.init()
        engine.bind(init.qrCode)
        // XXS parcel: should land in an XXS compartment, not anything bigger
        val attempt = engine.handInAttempt(MutationRequest(init.sessionId, 1, "DHL-XXS-1", ParcelSize.XXS))
        assertEquals(ParcelSize.XXS, attempt.compartment!!.size)
    }
}
