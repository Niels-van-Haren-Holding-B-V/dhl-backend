package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

data class MutationRequest(val sessionId: String, val version: Int, val barcode: String? = null, val size: ParcelSize? = null)
