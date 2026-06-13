package nl.callido.dhl.mapper.trips

import nl.callido.dhl.domain.Parcel
import nl.callido.dhl.dto.trips.DimensionsDto
import tech.mappie.api.ObjectMappie

object DimensionsMapper : ObjectMappie<Parcel, DimensionsDto>()
