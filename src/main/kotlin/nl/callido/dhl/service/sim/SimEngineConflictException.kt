package nl.callido.dhl.service.sim

import nl.callido.dhl.dto.sim.SimSessionSnapshot

/** Stale version, illegal transition or FORCE_409 — surfaces as HTTP 409. */
class SimEngineConflictException(val reason: String, val snapshot: SimSessionSnapshot?) :
    RuntimeException("conflict: $reason")
