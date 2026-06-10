package nl.callido.dhl.client

/** Locker unreachable, erroring, or the circuit breaker is open. Maps to 503. */
class LockerUnavailableException(cause: Throwable? = null) : RuntimeException("locker-sim unavailable", cause)
