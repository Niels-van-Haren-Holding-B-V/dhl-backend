package nl.callido.dhl.dto.sim

import java.time.Instant

data class SimSessionDto(val id: String, val state: SimSessionState, val version: Int, val boundAt: Instant?)
