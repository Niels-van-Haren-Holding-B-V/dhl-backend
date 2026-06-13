package nl.callido.dhl.service.sim

class SimEngineRejectedException(val code: String, override val message: String) : RuntimeException(message)
