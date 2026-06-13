package nl.callido.dhl.client

class SimRejectedException(val code: String, override val message: String) : RuntimeException(message)
