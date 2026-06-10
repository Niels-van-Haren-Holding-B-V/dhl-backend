package nl.callido.dhl.client

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import nl.callido.dhl.dto.sim.BindRequest
import nl.callido.dhl.dto.sim.ConflictResponse
import nl.callido.dhl.dto.sim.DoorRequest
import nl.callido.dhl.dto.sim.FailureRequest
import nl.callido.dhl.dto.sim.InitResponse
import nl.callido.dhl.dto.sim.MutationRequest
import nl.callido.dhl.dto.sim.RejectionResponse
import nl.callido.dhl.dto.sim.ResetRequest
import nl.callido.dhl.dto.sim.SimSessionSnapshot
import nl.callido.dhl.dto.sim.SimStateSnapshot
import nl.callido.dhl.dto.sim.StatusResponse
import nl.callido.dhl.dto.sim.ValidateRequest
import nl.callido.dhl.dto.sim.ValidateResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * The only way the BFF talks to the Locker API: suspend functions over
 * WebClient. Every call goes through one circuit breaker; 409 and 422 are
 * business signals and never count as breaker failures. Connection errors
 * and 5xx do.
 */
@Component
class LockerSimClient(
    webClientBuilder: WebClient.Builder,
    private val tokenProvider: LockerTokenProvider,
    private val baseUrl: LockerSimBaseUrl,
    private val objectMapper: ObjectMapper,
) {

    private val web = webClientBuilder.build()

    private val breaker = CircuitBreaker.of(
        "lockerSim",
        CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(10))
            .permittedNumberOfCallsInHalfOpenState(2)
            .ignoreExceptions(SimConflictException::class.java, SimRejectedException::class.java)
            .build(),
    )

    // ---- courier API ----

    suspend fun init(): InitResponse = post("/locker-api/courier/session/init", null)

    suspend fun status(externalSessionId: String): StatusResponse = guarded {
        request {
            val token = tokenProvider.token()
            web.get()
                .uri("${baseUrl.get()}/locker-api/courier/session/status?sessionId={id}", externalSessionId)
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .awaitBody()
        }
    }

    suspend fun finished(req: MutationRequest): SimSessionSnapshot = post("/locker-api/courier/session/finished", req)

    suspend fun validate(req: ValidateRequest): ValidateResponse = post("/locker-api/courier/hand-in/validate", req)

    /** op: attempt | confirm | continue | report-incorrect-compartment-size | report-compartment-issue | reopen-compartment */
    suspend fun handIn(op: String, req: MutationRequest): SimSessionSnapshot = post("/locker-api/courier/hand-in/$op", req)

    /** op: start | continue | confirm | report-missing | report-compartment-issue | abort */
    suspend fun handOut(op: String, req: MutationRequest): SimSessionSnapshot = post("/locker-api/courier/hand-out/$op", req)

    // ---- sim control passthrough ----

    suspend fun simBind(req: BindRequest): SimSessionSnapshot = post("/locker-api/sim/bind", req)

    suspend fun simState(): SimStateSnapshot = guarded {
        request {
            val token = tokenProvider.token()
            web.get()
                .uri("${baseUrl.get()}/locker-api/sim/state")
                .headers { it.setBearerAuth(token) }
                .retrieve()
                .awaitBody()
        }
    }

    suspend fun simDoor(req: DoorRequest): SimSessionSnapshot = post("/locker-api/sim/door", req)

    suspend fun simFailures(req: FailureRequest): SimStateSnapshot = post("/locker-api/sim/failures", req)

    suspend fun simReset(req: ResetRequest): SimStateSnapshot = post("/locker-api/sim/reset", req)

    // ---- plumbing ----

    private suspend inline fun <reified T : Any> post(path: String, body: Any?): T = guarded {
        request {
            val token = tokenProvider.token()
            val spec = web.post()
                .uri(baseUrl.get() + path)
                .headers { it.setBearerAuth(token) }
                .contentType(MediaType.APPLICATION_JSON)
            (body?.let { spec.bodyValue(it) } ?: spec)
                .retrieve()
                .awaitBody()
        }
    }

    private suspend fun <T : Any> request(call: suspend () -> T): T = try {
        call()
    } catch (e: WebClientResponseException) {
        when (e.statusCode.value()) {
            409 -> throw SimConflictException(readBody<ConflictResponse>(e.responseBodyAsByteArray))
            422 -> {
                val rejection = readBody<RejectionResponse>(e.responseBodyAsByteArray)
                throw SimRejectedException(rejection?.code ?: "REJECTED", rejection?.message ?: "rejected by locker")
            }
            // 401/403 here means OUR credentials are broken — an availability
            // problem from the courier's point of view. Never echo details.
            else -> throw LockerUnavailableException(e)
        }
    } catch (e: WebClientRequestException) {
        throw LockerUnavailableException(e)
    }

    private inline fun <reified T> readBody(bytes: ByteArray): T? = try {
        objectMapper.readValue(bytes, T::class.java)
    } catch (_: Exception) {
        null
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        breaker.executeSuspendFunction { block() }
    } catch (e: CallNotPermittedException) {
        throw LockerUnavailableException(e)
    }
}
