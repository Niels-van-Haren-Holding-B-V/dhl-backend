package nl.callido.dhl.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import nl.callido.dhl.common.ParcelSize
import java.util.UUID

@Entity
@Table(name = "parcel")
class Parcel(
    @Id val id: UUID,
    val stopId: UUID,
    val barcode: String,
    @Enumerated(EnumType.STRING) val direction: ParcelDirection,
    @Enumerated(EnumType.STRING) var status: ParcelStatus,
    val lengthCm: Int,
    val widthCm: Int,
    val heightCm: Int,
    // Explicit: Hibernate's camel-case strategy maps the trailing single
    // capital to "weightg", not "weight_g".
    @Column(name = "weight_g") val weightG: Int,
) {
    /** Derived, never stored — the real dimensions are the source of truth. */
    val size: ParcelSize?
        get() = ParcelSize.forDimensions(lengthCm, widthCm, heightCm)
}
