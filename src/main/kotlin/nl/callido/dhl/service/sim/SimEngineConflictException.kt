package nl.callido.dhl.service.sim

import nl.callido.dhl.dto.sim.SimSessionSnapshot

class SimEngineConflictException(val reason: String, val snapshot: SimSessionSnapshot?) : RuntimeException("conflict: $reason")
