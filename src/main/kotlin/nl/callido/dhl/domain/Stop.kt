package nl.callido.dhl.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "stop")
class Stop(
    @Id val id: UUID,
    val tripId: UUID,
    val seq: Int,
    val address: String,
    @Enumerated(EnumType.STRING) val deliveryLocationType: DeliveryLocationType,
)
