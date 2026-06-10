package nl.callido.dhl.dto.sim

import nl.callido.dhl.common.ParcelSize

data class ValidateResponse(
    val valid: Boolean,
    val reason: String? = null,
    val suggestedSize: ParcelSize? = null,
)
