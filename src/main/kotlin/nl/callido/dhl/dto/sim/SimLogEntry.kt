package nl.callido.dhl.dto.sim

import java.time.Instant

data class SimLogEntry(val ts: Instant, val endpoint: String, val summary: String, val resultingState: SimSessionState?, val version: Int?)
