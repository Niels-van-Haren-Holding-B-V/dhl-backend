package nl.callido.dhl.client

import nl.callido.dhl.dto.sim.ConflictResponse

/** 409 from the locker: stale version or illegal transition. Triggers reconcile, never retry. */
class SimConflictException(val conflict: ConflictResponse?) : RuntimeException("locker returned 409: ${conflict?.reason}")
