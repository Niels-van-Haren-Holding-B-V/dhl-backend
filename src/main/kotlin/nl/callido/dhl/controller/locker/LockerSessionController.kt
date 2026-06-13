package nl.callido.dhl.controller.locker

import nl.callido.dhl.dto.locker.CreateSessionRequest
import nl.callido.dhl.dto.locker.CreateSessionResponse
import nl.callido.dhl.dto.locker.HandInCommand
import nl.callido.dhl.dto.locker.HandOutCommand
import nl.callido.dhl.dto.locker.LockerActionRequest
import nl.callido.dhl.dto.locker.LockerActionResponse
import nl.callido.dhl.dto.locker.SessionStatusDto
import nl.callido.dhl.dto.locker.ValidationResultDto
import nl.callido.dhl.service.locker.LockerSessionService
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/locker/sessions")
@ConditionalOnBooleanProperty("dhl.backend.enabled")
class LockerSessionController(private val service: LockerSessionService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(@RequestBody req: CreateSessionRequest, @AuthenticationPrincipal jwt: Jwt): CreateSessionResponse =
        service.create(req.stopId, LockerSessionService.courierId(jwt))

    @GetMapping("/{id}")
    suspend fun status(@PathVariable id: UUID): SessionStatusDto = service.status(id)

    @PostMapping("/{id}/finish")
    suspend fun finish(@PathVariable id: UUID): LockerActionResponse = service.finish(id)

    @PostMapping("/{id}/hand-in/validate")
    suspend fun validate(@PathVariable id: UUID, @RequestBody req: LockerActionRequest): ValidationResultDto =
        service.validate(id, requireNotNull(req.barcode) { "barcode is required to validate" })

    @PostMapping("/{id}/hand-in")
    suspend fun handIn(@PathVariable id: UUID, @RequestBody cmd: HandInCommand): LockerActionResponse =
        service.handIn(id, cmd.action, cmd.barcode)

    @PostMapping("/{id}/hand-out")
    suspend fun handOut(@PathVariable id: UUID, @RequestBody cmd: HandOutCommand): LockerActionResponse =
        service.handOut(id, cmd.action, cmd.barcode)
}
