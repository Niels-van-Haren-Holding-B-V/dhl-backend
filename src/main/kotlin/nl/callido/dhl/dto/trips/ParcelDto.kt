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
    /** Derived from the real dimensions — t-shirt size of the smallest fitting compartment. */
    val size: ParcelSize?,
)
