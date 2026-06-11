package nl.callido.dhl.dto.delivery

import jakarta.validation.constraints.NotBlank
import nl.callido.dhl.domain.ParcelStatus
import java.util.UUID

data class RegisterDeliveryRequest(
    @field:NotBlank
    val barcode: String,
    val status: ParcelStatus,
    val sessionId: UUID? = null,
)
