package nl.callido.dhl.dto.locker

import nl.callido.dhl.domain.LockerSessionStatus
import nl.callido.dhl.dto.sim.SimSessionState
import java.util.UUID

data class SessionStatusDto(
    val sessionId: UUID,
    val status: String, // NOT_READY | READY, straight from the Locker API
    val simState: SimSessionState,
    val version: Int,
    val sessionStatus: LockerSessionStatus,
)
