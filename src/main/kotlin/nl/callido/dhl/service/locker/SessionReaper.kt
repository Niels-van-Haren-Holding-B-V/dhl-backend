package nl.callido.dhl.service.locker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import nl.callido.dhl.client.LockerSimClient
import nl.callido.dhl.config.DhlProperties
import nl.callido.dhl.domain.LockerSessionStatus
import nl.callido.dhl.domain.ParcelStatus
import nl.callido.dhl.dto.sim.MutationRequest
import nl.callido.dhl.repository.LockerSessionRepository
import nl.callido.dhl.repository.ParcelRepository
import nl.callido.dhl.service.delivery.DeliveryService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The "courier walked away" guard: sessions idle past the timeout are
 * finished at the locker, their unhandled parcels registered NOT_DELIVERED,
 * and locally marked EXPIRED. Stale sessions are reaped concurrently
 * (supervisorScope: one bad session never blocks the others). runBlocking
 * only bridges the blocking @Scheduled entry point, which also guarantees
 * runs never overlap.
 */
@Component
@ConditionalOnBooleanProperty("dhl.backend.enabled")
class SessionReaper(
    private val props: DhlProperties,
    private val sessions: LockerSessionRepository,
    private val parcels: ParcelRepository,
    private val client: LockerSimClient,
    private val deliveryService: DeliveryService,
    private val locks: SessionLocks,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun reap() {
        if (!props.reaper.enabled) return
        val cutoff = Instant.now().minus(props.reaper.timeout)
        val stale = sessions.findByStatusAndLastActivityAtBefore(LockerSessionStatus.ACTIVE, cutoff)
        if (stale.isEmpty()) return
        log.info("reaping {} stale locker session(s)", stale.size)
        runBlocking {
            supervisorScope {
                stale.forEach { session ->
                    launch { reapOne(session.id) }
                }
            }
        }
    }

    private suspend fun reapOne(id: UUID) = locks.withSessionLock(id) {
        val session = io { sessions.findById(id).orElse(null) } ?: return@withSessionLock
        if (session.status != LockerSessionStatus.ACTIVE) return@withSessionLock
        // Best effort at the locker: if it is unreachable or conflicted we
        // still expire locally — the truth for OUR domain is NOT_DELIVERED.
        runCatching {
            val version = client.status(session.externalSessionId).version
            client.finished(MutationRequest(session.externalSessionId, version))
        }.onFailure { log.warn("could not finish session {} at locker, expiring locally", id) }
        io {
            parcels.findByStopId(session.stopId)
                .filter { it.status == ParcelStatus.EXPECTED }
                .forEach { deliveryService.register(it.barcode, ParcelStatus.NOT_DELIVERED, session.id) }
            session.status = LockerSessionStatus.EXPIRED
            session.finishedAt = Instant.now()
            sessions.save(session)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
