package nl.callido.dhl.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "delivery_registration")
class DeliveryRegistration(
    @Id val id: UUID,
    val sessionId: UUID?,
    val barcode: String,
    @Enumerated(EnumType.STRING) val status: ParcelStatus,
    val registeredAt: Instant,
)
