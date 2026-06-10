package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

/** All sim mutations carry the caller's last seen version — stale → 409. */
data class MutationRequest(
    val sessionId: String,
    val version: Int,
    val barcode: String? = null,
    val size: ParcelSize? = null,
)
