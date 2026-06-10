package nl.callido.dhl.dto.sim

data class ConflictResponse(val reason: String, val snapshot: SimSessionSnapshot?)
