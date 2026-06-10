package nl.callido.dhl.service.locker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.callido.dhl.client.LockerSimClient
import nl.callido.dhl.client.SimConflictException
import nl.callido.dhl.domain.DeliveryLocationType
import nl.callido.dhl.domain.LockerSession
import nl.callido.dhl.domain.LockerSessionStatus
import nl.callido.dhl.domain.ParcelStatus
import nl.callido.dhl.dto.locker.CreateSessionResponse
import nl.callido.dhl.dto.locker.LockerActionResponse
import nl.callido.dhl.dto.locker.SessionStatusDto
import nl.callido.dhl.dto.locker.ValidationResultDto
import nl.callido.dhl.dto.sim.MutationRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.ValidateRequest
import nl.callido.dhl.repository.LockerSessionRepository
import nl.callido.dhl.repository.ParcelRepository
import nl.callido.dhl.repository.StopRepository
import nl.callido.dhl.service.delivery.DeliveryService
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * The proxy layer between the courier app and the Locker API. Owns the
 * cross-cutting rules: per-session serialization, 409-reconciliation
 * (never blind-retry a mutation), idempotent delivery registration and
 * activity tracking for the reaper.
 *
 * Fully suspend; blocking JPA work runs on Dispatchers.IO via [io].
 */
@Service
class LockerSessionService(
    private val client: LockerSimClient,
    private val sessions: LockerSessionRepository,
    private val stops: StopRepository,
    private val parcels: ParcelRepository,
    private val deliveryService: DeliveryService,
    private val locks: SessionLocks,
) {

    suspend fun create(stopId: UUID, courierId: String): CreateSessionResponse {
        val stop = io { stops.findById(stopId).orElseThrow { NoSuchElementException("stop $stopId not found") } }
        require(stop.deliveryLocationType == DeliveryLocationType.LOCKER) { "stop $stopId is not a locker stop" }
        val init = client.init()
        val now = Instant.now()
        val session = LockerSession(
            id = UUID.randomUUID(), stopId = stopId, courierId = courierId,
            externalSessionId = init.sessionId, status = LockerSessionStatus.ACTIVE,
            simVersion = 0, createdAt = now, lastActivityAt = now, finishedAt = null,
        )
        io { sessions.save(session) }
        return CreateSessionResponse(session.id, init.qrCode)
    }

    suspend fun status(id: UUID): SessionStatusDto {
        val session = io { load(id) }
        val remote = client.status(session.externalSessionId)
        // Status polls deliberately do NOT touch lastActivityAt: an open
        // browser tab must not keep a walked-away session alive forever.
        return SessionStatusDto(session.id, remote.status, remote.state, remote.version, session.status)
    }

    suspend fun validate(id: UUID, barcode: String): ValidationResultDto = locks.withSessionLock(id) {
        val session = io { load(id) }
        val parcel = io { parcels.findByBarcode(barcode) }
            ?: return@withSessionLock ValidationResultDto(barcode, valid = false, reason = "UNKNOWN_BARCODE")
        val result = client.validate(ValidateRequest(session.externalSessionId, barcode, parcel.size))
        io { touch(session) }
        ValidationResultDto(barcode, result.valid, result.reason, result.suggestedSize, parcel.size)
    }

    suspend fun handInAttempt(id: UUID, barcode: String): LockerActionResponse = mutation(id) { session, version ->
        val parcel = io { parcels.findByBarcode(barcode) } ?: throw NoSuchElementException("unknown barcode $barcode")
        client.handIn("attempt", MutationRequest(session.externalSessionId, version, barcode, parcel.size))
    }

    suspend fun handInConfirm(id: UUID, barcode: String): LockerActionResponse = mutation(
        id,
        afterSuccess = { deliveryService.register(barcode, ParcelStatus.HANDED_IN, it.id) },
    ) { session, version ->
        client.handIn("confirm", MutationRequest(session.externalSessionId, version, barcode))
    }

    suspend fun handInContinue(id: UUID): LockerActionResponse = simpleHandIn(id, "continue")
    suspend fun handInReportSize(id: UUID): LockerActionResponse = simpleHandIn(id, "report-incorrect-compartment-size")
    suspend fun handInReportIssue(id: UUID): LockerActionResponse = simpleHandIn(id, "report-compartment-issue")
    suspend fun handInReopen(id: UUID): LockerActionResponse = simpleHandIn(id, "reopen-compartment")

    suspend fun handOutStart(id: UUID, barcode: String): LockerActionResponse = mutation(id) { session, version ->
        client.handOut("start", MutationRequest(session.externalSessionId, version, barcode))
    }

    suspend fun handOutConfirm(id: UUID, barcode: String): LockerActionResponse = mutation(
        id,
        afterSuccess = { deliveryService.register(barcode, ParcelStatus.HANDED_OUT, it.id) },
    ) { session, version ->
        client.handOut("confirm", MutationRequest(session.externalSessionId, version, barcode))
    }

    suspend fun handOutContinue(id: UUID): LockerActionResponse = simpleHandOut(id, "continue")
    suspend fun handOutAbort(id: UUID): LockerActionResponse = simpleHandOut(id, "abort")

    suspend fun handOutReportMissing(id: UUID, barcode: String): LockerActionResponse = mutation(
        id,
        afterSuccess = { deliveryService.register(barcode, ParcelStatus.NOT_DELIVERED, it.id) },
    ) { session, version ->
        client.handOut("report-missing", MutationRequest(session.externalSessionId, version, barcode))
    }

    suspend fun finish(id: UUID): LockerActionResponse {
        val response = mutation(id) { session, version ->
            client.finished(MutationRequest(session.externalSessionId, version))
        }
        if (!response.reconciled) {
            locks.withSessionLock(id) {
                io {
                    val session = load(id)
                    session.status = LockerSessionStatus.FINISHED
                    session.finishedAt = Instant.now()
                    sessions.save(session)
                }
            }
        }
        return response
    }

    // ---- internals ----

    private suspend fun simpleHandIn(id: UUID, op: String): LockerActionResponse = mutation(id) { session, version ->
        client.handIn(op, MutationRequest(session.externalSessionId, version))
    }

    private suspend fun simpleHandOut(id: UUID, op: String): LockerActionResponse = mutation(id) { session, version ->
        client.handOut(op, MutationRequest(session.externalSessionId, version))
    }

    /**
     * One sim mutation, fully guarded:
     *  - per-session Mutex
     *  - fresh version fetched first (machine-side events also bump it)
     *  - on 409: refetch state and return it flagged `reconciled: true` —
     *    NEVER blind-retry the mutation, the locker already moved on
     */
    private suspend fun mutation(
        id: UUID,
        afterSuccess: ((LockerSession) -> Unit)? = null,
        call: suspend (LockerSession, Int) -> SimSessionSnapshot,
    ): LockerActionResponse = locks.withSessionLock(id) {
        val session = io { load(id) }
        check(session.status == LockerSessionStatus.ACTIVE) { "session $id is ${session.status}" }
        try {
            val version = client.status(session.externalSessionId).version
            val snapshot = call(session, version)
            session.simVersion = snapshot.version
            io {
                touch(session)
                afterSuccess?.invoke(session)
            }
            LockerActionResponse(
                sessionId = session.id,
                simState = snapshot.state,
                version = snapshot.version,
                reconciled = false,
                compartment = snapshot.compartment,
                parcelPresent = snapshot.parcelPresent,
                failure = snapshot.failure,
            )
        } catch (e: SimConflictException) {
            val truth = client.status(session.externalSessionId)
            session.simVersion = truth.version
            io { sessions.save(session) }
            LockerActionResponse(
                sessionId = session.id,
                simState = truth.state,
                version = truth.version,
                reconciled = true,
                reconcileReason = e.conflict?.reason,
            )
        }
    }

    private fun touch(session: LockerSession) {
        session.lastActivityAt = Instant.now()
        sessions.save(session)
    }

    private fun load(id: UUID): LockerSession = sessions.findById(id).orElseThrow { NoSuchElementException("locker session $id not found") }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
