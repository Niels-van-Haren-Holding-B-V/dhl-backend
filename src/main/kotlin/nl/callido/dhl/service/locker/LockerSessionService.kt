package nl.callido.dhl.service.locker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import nl.callido.dhl.client.LockerSimClient
import nl.callido.dhl.client.SimConflictException
import nl.callido.dhl.domain.DeliveryLocationType
import nl.callido.dhl.domain.LockerSession
import nl.callido.dhl.domain.LockerSessionStatus
import nl.callido.dhl.domain.ParcelStatus
import nl.callido.dhl.dto.locker.CreateSessionResponse
import nl.callido.dhl.dto.locker.HandInAction
import nl.callido.dhl.dto.locker.HandOutAction
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
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
class LockerSessionService(
    private val client: LockerSimClient,
    private val sessions: LockerSessionRepository,
    private val stops: StopRepository,
    private val parcels: ParcelRepository,
    private val deliveryService: DeliveryService,
    private val locks: SessionLocks,
) {

    companion object {
        fun courierId(jwt: Jwt): String = jwt.getClaimAsString("preferred_username") ?: jwt.subject ?: "unknown"
    }

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
        val session = loadOwned(id)
        val remote = client.status(session.externalSessionId)
        // Status polls deliberately do NOT touch lastActivityAt: an open tab must not keep a walked-away session alive.
        return SessionStatusDto(session.id, remote.status, remote.state, remote.version, session.status)
    }

    suspend fun validate(id: UUID, barcode: String): ValidationResultDto = locks.withSessionLock(id) {
        val session = loadOwned(id)
        val parcel = io { parcels.findByBarcode(barcode) }
            ?: return@withSessionLock ValidationResultDto(barcode, valid = false, reason = "UNKNOWN_BARCODE")
        val result = client.validate(ValidateRequest(session.externalSessionId, barcode, parcel.size))
        io { touch(session) }
        ValidationResultDto(barcode, result.valid, result.reason, result.suggestedSize, parcel.size)
    }

    suspend fun handIn(id: UUID, action: HandInAction, barcode: String?): LockerActionResponse = when (action) {
        HandInAction.ATTEMPT -> mutate(id, client::handIn, "attempt", requireBarcode(barcode, action), withSize = true)
        HandInAction.CONFIRM -> mutate(id, client::handIn, "confirm", requireBarcode(barcode, action), registerAs = ParcelStatus.HANDED_IN)
        HandInAction.CONTINUE -> mutate(id, client::handIn, "continue")
        HandInAction.REPORT_SIZE -> mutate(id, client::handIn, "report-incorrect-compartment-size")
        HandInAction.REPORT_ISSUE -> mutate(id, client::handIn, "report-compartment-issue")
        HandInAction.REOPEN -> mutate(id, client::handIn, "reopen-compartment")
    }

    suspend fun handOut(id: UUID, action: HandOutAction, barcode: String?): LockerActionResponse = when (action) {
        HandOutAction.START -> mutate(id, client::handOut, "start", requireBarcode(barcode, action))
        HandOutAction.CONFIRM ->
            mutate(id, client::handOut, "confirm", requireBarcode(barcode, action), registerAs = ParcelStatus.HANDED_OUT)
        HandOutAction.REPORT_MISSING ->
            mutate(id, client::handOut, "report-missing", requireBarcode(barcode, action), registerAs = ParcelStatus.NOT_DELIVERED)
        HandOutAction.CONTINUE -> mutate(id, client::handOut, "continue")
        HandOutAction.ABORT -> mutate(id, client::handOut, "abort")
    }

    private fun requireBarcode(barcode: String?, action: Enum<*>): String = requireNotNull(barcode) { "barcode is required for $action" }

    // The parcel lookup stays INSIDE [mutation] so it runs after the ownership check:
    // an unknown barcode on a foreign session fails ownership first, never leaking its existence.
    private suspend fun mutate(
        id: UUID,
        call: suspend (String, MutationRequest) -> SimSessionSnapshot,
        op: String,
        barcode: String? = null,
        withSize: Boolean = false,
        registerAs: ParcelStatus? = null,
    ): LockerActionResponse {
        val afterSuccess: ((LockerSession) -> Unit)? =
            if (registerAs != null && barcode != null) {
                { session -> deliveryService.register(barcode, registerAs, session.id) }
            } else {
                null
            }
        return mutation(id, afterSuccess) { session, version ->
            val size = if (withSize) {
                io { parcels.findByBarcode(barcode!!) }?.size ?: throw NoSuchElementException("unknown barcode $barcode")
            } else {
                null
            }
            call(op, MutationRequest(session.externalSessionId, version, barcode, size))
        }
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

    // On 409: refetch state and return it flagged `reconciled: true` — NEVER blind-retry the mutation.
    private suspend fun mutation(
        id: UUID,
        afterSuccess: ((LockerSession) -> Unit)? = null,
        call: suspend (LockerSession, Int) -> SimSessionSnapshot,
    ): LockerActionResponse = locks.withSessionLock(id) {
        val session = loadOwned(id)
        if (session.status != LockerSessionStatus.ACTIVE) throw SessionNotActiveException(session.status)
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

    // The one ownership gate for every HTTP entry point; internal callers (the reaper, no
    // request context) use the repositories directly and deliberately bypass this gate.
    private suspend fun loadOwned(id: UUID): LockerSession {
        val session = io { load(id) }
        val caller = ReactiveSecurityContextHolder.getContext()
            .mapNotNull { (it.authentication as? JwtAuthenticationToken)?.token?.let(::courierId) }
            .awaitSingleOrNull()
        if (caller == null || session.courierId != caller) throw SessionForbiddenException()
        return session
    }

    private fun load(id: UUID): LockerSession = sessions.findById(id).orElseThrow { NoSuchElementException("locker session $id not found") }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
