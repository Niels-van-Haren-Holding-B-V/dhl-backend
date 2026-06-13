package nl.callido.dhl.client

import nl.callido.dhl.dto.sim.ConflictResponse

class SimConflictException(val conflict: ConflictResponse?) : RuntimeException("locker returned 409: ${conflict?.reason}")
