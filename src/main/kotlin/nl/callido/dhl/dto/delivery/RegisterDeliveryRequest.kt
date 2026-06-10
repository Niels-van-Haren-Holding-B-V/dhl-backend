package nl.callido.dhl.dto.delivery

import nl.callido.dhl.domain.ParcelStatus
import java.util.UUID

data class RegisterDeliveryRequest(
    val barcode: String,
    val status: ParcelStatus,
    val sessionId: UUID? = null,
)
