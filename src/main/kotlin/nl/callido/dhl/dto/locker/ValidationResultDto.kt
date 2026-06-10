package nl.callido.dhl.dto.locker

import nl.callido.dhl.common.ParcelSize

data class ValidationResultDto(
    val barcode: String,
    val valid: Boolean,
    val reason: String? = null,
    /** Compartment size the locker proposes for this parcel. */
    val suggestedSize: ParcelSize? = null,
    /** Size derived from the parcel's real dimensions. */
    val parcelSize: ParcelSize? = null,
)
