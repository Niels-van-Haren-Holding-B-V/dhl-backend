package nl.callido.dhl.dto.trips

import java.util.UUID

/**
 * Message on the `parcel-intake` topic: an upstream planning system announces
 * a parcel for delivery.
 */
data class ParcelAnnouncement(
    val barcode: String,
    /** Defaults to the first LOCKER stop when absent. */
    val stopId: UUID? = null,
    val lengthCm: Int,
    val widthCm: Int,
    val heightCm: Int,
    val weightG: Int,
)
