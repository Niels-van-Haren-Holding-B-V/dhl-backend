package nl.callido.dhl.dto.trips

import nl.callido.dhl.common.ParcelSize
import nl.callido.dhl.domain.ParcelDirection
import nl.callido.dhl.domain.ParcelStatus
import java.util.UUID

data class ParcelDto(
    val id: UUID,
    val barcode: String,
    val direction: ParcelDirection,
    val status: ParcelStatus,
    val dimensions: DimensionsDto,
    val size: ParcelSize?,
)
