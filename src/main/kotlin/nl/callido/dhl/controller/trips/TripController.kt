package nl.callido.dhl.controller.trips

import nl.callido.dhl.service.trips.TripService
import nl.callido.dhl.dto.trips.TripDto
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trips")
@ConditionalOnBooleanProperty("dhl.backend.enabled")
class TripController(private val tripService: TripService) {

    @GetMapping
    suspend fun trips(): List<TripDto> = tripService.trips()
}
