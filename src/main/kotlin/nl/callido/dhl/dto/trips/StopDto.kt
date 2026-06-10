package nl.callido.dhl.dto.trips

import nl.callido.dhl.domain.DeliveryLocationType
import java.util.UUID

data class StopDto(
    val id: UUID,
    val seq: Int,
    val address: String,
    val deliveryLocationType: DeliveryLocationType,
    val parcels: List<ParcelDto>,
)
