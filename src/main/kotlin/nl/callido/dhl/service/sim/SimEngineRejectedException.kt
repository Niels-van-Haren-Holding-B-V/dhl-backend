package nl.callido.dhl.service.sim

/** Business rejection (unknown parcel, defect compartment, ...) — surfaces as HTTP 422. */
class SimEngineRejectedException(val code: String, override val message: String) : RuntimeException(message)
