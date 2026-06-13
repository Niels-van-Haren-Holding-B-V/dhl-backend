package nl.callido.dhl.dto.delivery

import nl.callido.dhl.domain.ParcelStatus
import java.time.Instant
import java.util.UUID

data class DeliveryEventPayload(
    val barcode: String,
    val status: ParcelStatus,
    val sessionId: UUID?,
    val stopId: UUID?,
    val registeredAt: Instant,
)
