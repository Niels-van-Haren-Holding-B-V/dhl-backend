package nl.callido.dhl.dto.locker

import java.util.UUID

data class CreateSessionResponse(val sessionId: UUID, val qrPayload: String)
