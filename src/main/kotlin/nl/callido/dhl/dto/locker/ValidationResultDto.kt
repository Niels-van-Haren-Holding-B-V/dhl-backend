package nl.callido.dhl.dto.locker

import nl.callido.dhl.common.ParcelSize

data class ValidationResultDto(
    val barcode: String,
    val valid: Boolean,
    val reason: String? = null,
    val suggestedSize: ParcelSize? = null,
    val parcelSize: ParcelSize? = null,
)
