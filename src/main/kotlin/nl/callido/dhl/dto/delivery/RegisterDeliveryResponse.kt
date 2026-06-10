package nl.callido.dhl.dto.delivery

import nl.callido.dhl.domain.ParcelStatus

data class RegisterDeliveryResponse(
    val barcode: String,
    val status: ParcelStatus,
    /** True when this (session, barcode) pair was already registered — idempotent no-op. */
    val duplicate: Boolean,
)
