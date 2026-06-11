package nl.callido.dhl.service.locker

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes all locker mutations per session, so two impatient taps on the
 * courier app can never interleave halfway through a sim round-trip.
 * A coroutine [Mutex]: suspends instead of blocking a thread.
 *
 * In-memory ON PURPOSE: this only works single-replica. With more than one
 * backend pod this must become a Postgres advisory lock
 * (pg_advisory_xact_lock) or a Redis lock.
 *
 * Entries are never evicted: one Mutex per session id, a few dozen bytes
 * each, bounded by the demo's session count. A production version would
 * evict on session finish/expiry (or use the advisory lock above and have
 * no map at all).
 */
@Component
class SessionLocks {
    private val locks = ConcurrentHashMap<UUID, Mutex>()

    suspend fun <T> withSessionLock(sessionId: UUID, block: suspend () -> T): T =
        locks.computeIfAbsent(sessionId) { Mutex() }.withLock { block() }
}
