package nl.callido.dhl.mapper.trips

import nl.callido.dhl.domain.Stop
import nl.callido.dhl.dto.trips.ParcelDto
import nl.callido.dhl.dto.trips.StopDto
import tech.mappie.api.ObjectMappie2

object StopMapper : ObjectMappie2<Stop, List<ParcelDto>, StopDto>() {
    override fun map(first: Stop, second: List<ParcelDto>): StopDto = mapping {
        to::parcels fromValue second
    }
}
