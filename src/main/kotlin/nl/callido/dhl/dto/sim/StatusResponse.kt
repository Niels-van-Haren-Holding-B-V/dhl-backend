package nl.callido.dhl.dto.sim

data class StatusResponse(
    val status: String, // NOT_READY | READY
    val state: SimSessionState,
    val version: Int,
)
