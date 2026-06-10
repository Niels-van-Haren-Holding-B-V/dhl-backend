package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

/** Modelled after a real (PostNL-style) locker: label/column/hardware address/back door. */
data class CompartmentDto(
    val nr: Int,
    val label: String,
    val column: Int,
    val address: Int,
    val backAddress: Int?,
    val size: ParcelSize,
    val enabled: Boolean,
    val state: CompartmentState,
    val barcode: String?,
)
