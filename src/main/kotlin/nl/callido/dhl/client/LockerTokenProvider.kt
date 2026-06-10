package nl.callido.dhl.client

/**
 * Supplies a bearer token for the `locker` realm. Suspend because the first
 * call (and every refresh) does a token request; abstracted so tests can
 * stub it.
 */
fun interface LockerTokenProvider {
    suspend fun token(): String
}
