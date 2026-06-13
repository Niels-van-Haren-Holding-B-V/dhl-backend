package nl.callido.dhl.service.locker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory, single-replica ON PURPOSE: multi-replica must become a Postgres
 * advisory lock (pg_advisory_xact_lock) or a Redis lock.
 * Entries are never evicted; a production version would evict on session finish/expiry.
 */
@Component
class SessionLocks {
    private val locks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun <T> withSessionLock(sessionId: UUID, block: suspend () -> T): T =
        locks.computeIfAbsent(sessionId) { Mutex() }.withLock { block() }
}
