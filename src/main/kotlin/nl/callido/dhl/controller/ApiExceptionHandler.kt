package nl.callido.dhl.controller

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import nl.callido.dhl.client.LockerUnavailableException
import nl.callido.dhl.client.SimRejectedException
import nl.callido.dhl.controller.delivery.DeliveryController
import nl.callido.dhl.controller.locker.LockerSessionController
import nl.callido.dhl.controller.sim.SimProxyController
import nl.callido.dhl.controller.trips.TripController
import nl.callido.dhl.dto.ErrorMessage
import nl.callido.dhl.dto.sim.RejectionResponse
import nl.callido.dhl.service.locker.SessionForbiddenException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException

/**
 * Error contract for the courier-facing API. Messages are deliberately
 * constant strings: nothing from the locker side (least of all a token or
 * stack trace) may leak into a response.
 */
@RestControllerAdvice(
    assignableTypes = [
        TripController::class,
        LockerSessionController::class,
        DeliveryController::class,
        SimProxyController::class,
    ],
)
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(LockerUnavailableException::class, CallNotPermittedException::class)
    fun unavailable(e: Exception): ResponseEntity<ErrorMessage> {
        log.warn("locker unavailable: {}", e.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorMessage("Pakketautomaat tijdelijk niet bereikbaar"))
    }

    @ExceptionHandler(SimRejectedException::class)
    fun rejected(e: SimRejectedException): ResponseEntity<RejectionResponse> =
        ResponseEntity.unprocessableEntity().body(RejectionResponse(e.code, e.message))

    @ExceptionHandler(SessionForbiddenException::class)
    fun forbidden(e: SessionForbiddenException): ResponseEntity<ErrorMessage> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorMessage("Geen toegang tot deze sessie"))

    @ExceptionHandler(WebExchangeBindException::class)
    fun invalidBody(e: WebExchangeBindException): ResponseEntity<ErrorMessage> =
        ResponseEntity.badRequest().body(ErrorMessage("Ongeldig verzoek"))

    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(e: NoSuchElementException): ResponseEntity<ErrorMessage> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorMessage(e.message ?: "not found"))

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun badRequest(e: Exception): ResponseEntity<ErrorMessage> =
        ResponseEntity.badRequest().body(ErrorMessage(e.message ?: "invalid request"))
}
