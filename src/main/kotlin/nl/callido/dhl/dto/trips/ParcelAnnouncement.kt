package nl.callido.dhl.dto.trips

import java.util.UUID

/**
 * Message on the `parcel-intake` topic: an upstream planning system announces
 * a parcel for delivery. Mirrors how parcels enter the real system ("Pakketten
 * komen binnen via Kafka-ingestie vanuit upstream planningssystemen").
 */
data class ParcelAnnouncement(
    val barcode: String,
    /** Defaults to the demo trip's LOCKER stop when absent. */
    val stopId: UUID? = null,
    val lengthCm: Int,
    val widthCm: Int,
    val heightCm: Int,
    val weightG: Int,
)
