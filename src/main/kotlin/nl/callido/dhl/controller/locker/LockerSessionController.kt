package nl.callido.dhl.controller.locker

import nl.callido.dhl.dto.locker.CreateSessionRequest
import nl.callido.dhl.dto.locker.CreateSessionResponse
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
        service.create(req.stopId, jwt.getClaimAsString("preferred_username") ?: jwt.subject ?: "unknown")

    @GetMapping("/{id}")
    suspend fun status(@PathVariable id: UUID): SessionStatusDto = service.status(id)

    @PostMapping("/{id}/finish")
    suspend fun finish(@PathVariable id: UUID): LockerActionResponse = service.finish(id)

    // ---- hand-in ----

    @PostMapping("/{id}/hand-in/validate")
    suspend fun validate(@PathVariable id: UUID, @RequestBody req: LockerActionRequest): ValidationResultDto =
        service.validate(id, requireBarcode(req))

    @PostMapping("/{id}/hand-in/attempt")
    suspend fun attempt(@PathVariable id: UUID, @RequestBody req: LockerActionRequest): LockerActionResponse =
        service.handInAttempt(id, requireBarcode(req))

    @PostMapping("/{id}/hand-in/confirm")
    suspend fun confirm(@PathVariable id: UUID, @RequestBody req: LockerActionRequest): LockerActionResponse =
        service.handInConfirm(id, requireBarcode(req))

    @PostMapping("/{id}/hand-in/continue")
    suspend fun handInContinue(@PathVariable id: UUID): LockerActionResponse = service.handInContinue(id)

    @PostMapping("/{id}/hand-in/report-size")
    suspend fun reportSize(@PathVariable id: UUID): LockerActionResponse = service.handInReportSize(id)

    @PostMapping("/{id}/hand-in/report-issue")
    suspend fun reportIssue(@PathVariable id: UUID): LockerActionResponse = service.handInReportIssue(id)

    @PostMapping("/{id}/hand-in/reopen")
    suspend fun reopen(@PathVariable id: UUID): LockerActionResponse = service.handInReopen(id)

    // ---- hand-out ----

    @PostMapping("/{id}/hand-out/start")
    suspend fun handOutStart(@PathVariable id: UUID, @RequestBody req: LockerActionRequest): LockerActionResponse =
        service.handOutStart(id, requireBarcode(req))

    @PostMapping("/{id}/hand-out/continue")
    suspend fun handOutContinue(@PathVariable id: UUID): LockerActionResponse = service.handOutContinue(id)

    @PostMapping("/{id}/hand-out/confirm")
    suspend fun handOutConfirm(@PathVariable id: UUID, @RequestBody req: LockerActionRequest): LockerActionResponse =
        service.handOutConfirm(id, requireBarcode(req))

    @PostMapping("/{id}/hand-out/report-missing")
    suspend fun reportMissing(@PathVariable id: UUID, @RequestBody req: LockerActionRequest): LockerActionResponse =
        service.handOutReportMissing(id, requireBarcode(req))

    @PostMapping("/{id}/hand-out/abort")
    suspend fun abort(@PathVariable id: UUID): LockerActionResponse = service.handOutAbort(id)

    private fun requireBarcode(req: LockerActionRequest): String = requireNotNull(req.barcode) { "barcode is required for this action" }
}
