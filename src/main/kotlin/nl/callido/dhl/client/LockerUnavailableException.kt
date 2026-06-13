package nl.callido.dhl.client

class LockerUnavailableException(cause: Throwable? = null) : RuntimeException("locker-sim unavailable", cause)
