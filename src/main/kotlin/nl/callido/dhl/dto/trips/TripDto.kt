package nl.callido.dhl.dto.trips

import java.time.LocalDate
import java.util.UUID

data class TripDto(
    val id: UUID,
    val name: String,
    val tripDate: LocalDate,
    val stops: List<StopDto>,
)
