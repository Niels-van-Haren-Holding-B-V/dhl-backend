package nl.callido.dhl.dto.sim

import java.time.Instant

data class SimSessionSnapshot(
    val sessionId: String,
    val state: SimSessionState,
    val version: Int,
    val boundAt: Instant?,
    /** The compartment relevant to the action just performed, if any. */
    val compartment: CompartmentDto?,
    /** False when a hand-out door opened but the parcel is not inside (PARCEL_MISSING). */
    val parcelPresent: Boolean? = null,
    /** Set when a failure mode interfered with the action (e.g. DOOR_STUCK). */
    val failure: String? = null,
)
