package nl.callido.dhl.client

/** Resolved per call so tests can point the client at a runtime-assigned port. */
fun interface LockerSimBaseUrl {
    fun get(): String
}
