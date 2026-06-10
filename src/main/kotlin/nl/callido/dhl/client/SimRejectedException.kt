package nl.callido.dhl.client

/** 422 from the locker: business rejection (defect compartment, unknown parcel, ...). */
class SimRejectedException(val code: String, override val message: String) : RuntimeException(message)
