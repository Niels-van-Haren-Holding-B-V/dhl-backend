package nl.callido.dhl.controller.sim

import nl.callido.dhl.dto.sim.ConflictResponse
import nl.callido.dhl.dto.sim.RejectionResponse
import nl.callido.dhl.service.sim.SimEngineConflictException
import nl.callido.dhl.service.sim.SimEngineRejectedException
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [CourierSessionController::class, SimControlController::class])
@ConditionalOnBooleanProperty("dhl.locker-sim.serve")
class LockerSimExceptionHandler {

    @ExceptionHandler(SimEngineConflictException::class)
    fun conflict(e: SimEngineConflictException): ResponseEntity<ConflictResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(ConflictResponse(e.reason, e.snapshot))

    @ExceptionHandler(SimEngineRejectedException::class)
    fun rejected(e: SimEngineRejectedException): ResponseEntity<RejectionResponse> =
        ResponseEntity.unprocessableEntity().body(RejectionResponse(e.code, e.message))
}
