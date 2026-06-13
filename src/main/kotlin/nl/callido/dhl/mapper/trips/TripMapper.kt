package nl.callido.dhl.mapper.trips

import nl.callido.dhl.domain.Trip
import nl.callido.dhl.dto.trips.StopDto
import nl.callido.dhl.dto.trips.TripDto
import tech.mappie.api.ObjectMappie2

object TripMapper : ObjectMappie2<Trip, List<StopDto>, TripDto>() {
    override fun map(first: Trip, second: List<StopDto>): TripDto = mapping {
        to::stops fromValue second
    }
}
