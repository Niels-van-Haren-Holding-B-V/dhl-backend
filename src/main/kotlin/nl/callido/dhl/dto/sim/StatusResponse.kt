package nl.callido.dhl.dto.sim

data class StatusResponse(val status: String, val state: SimSessionState, val version: Int)
