package nl.callido.dhl.dto.locker

import nl.callido.dhl.dto.sim.CompartmentDto
import nl.callido.dhl.dto.sim.SimSessionState
import java.util.UUID

data class LockerActionResponse(
    val sessionId: UUID,
    val simState: SimSessionState,
    val version: Int,
    val reconciled: Boolean,
    val reconcileReason: String? = null,
    val compartment: CompartmentDto? = null,
    val parcelPresent: Boolean? = null,
    val failure: String? = null,
)
