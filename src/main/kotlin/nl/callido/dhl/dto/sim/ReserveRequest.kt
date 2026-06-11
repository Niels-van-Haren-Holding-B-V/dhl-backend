package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

/** Pre-announcement: reserve a compartment for an upstream-announced parcel. */
data class ReserveRequest(val barcode: String, val size: ParcelSize)
