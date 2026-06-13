package nl.callido.dhl.mapper.trips

import nl.callido.dhl.domain.Parcel
import nl.callido.dhl.dto.trips.ParcelDto
import tech.mappie.api.ObjectMappie

object ParcelMapper : ObjectMappie<Parcel, ParcelDto>() {
    override fun map(from: Parcel): ParcelDto = mapping {
        to::dimensions fromValue DimensionsMapper.map(from)
    }
}
