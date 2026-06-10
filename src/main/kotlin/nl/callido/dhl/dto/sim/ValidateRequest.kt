package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

data class ValidateRequest(val sessionId: String, val barcode: String, val size: ParcelSize?)
