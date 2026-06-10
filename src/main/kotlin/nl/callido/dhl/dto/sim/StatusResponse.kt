package nl.callido.dhl.dto.sim

data class StatusResponse(
    val status: String, // NOT_READY | READY — the case's literal contract
    val state: SimSessionState,
    val version: Int,
)
