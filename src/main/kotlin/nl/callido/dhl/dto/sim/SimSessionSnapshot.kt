package nl.callido.dhl.dto.sim

import java.time.Instant

data class SimSessionSnapshot(
    val sessionId: String,
    val state: SimSessionState,
    val version: Int,
    val boundAt: Instant?,
    val compartment: CompartmentDto?,
    val parcelPresent: Boolean? = null,
    val failure: String? = null,
)
