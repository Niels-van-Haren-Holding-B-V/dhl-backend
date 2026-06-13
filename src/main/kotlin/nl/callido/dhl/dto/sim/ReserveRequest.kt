package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

data class ReserveRequest(val barcode: String, val size: ParcelSize)
