package nl.callido.dhl.dto.trips

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.util.UUID

/**
 * Message on the `parcel-intake` topic: an upstream planning system announces
 * a parcel for delivery.
 */
data class ParcelAnnouncement(
    @field:NotBlank
    val barcode: String,
    /** Defaults to the first LOCKER stop when absent. */
    val stopId: UUID? = null,
    @field:Positive
    val lengthCm: Int,
    @field:Positive
    val widthCm: Int,
    @field:Positive
    val heightCm: Int,
    @field:Positive
    val weightG: Int,
)
