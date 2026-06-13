package nl.callido.dhl.client

fun interface LockerTokenProvider {
    suspend fun token(): String
}
