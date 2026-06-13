package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

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
