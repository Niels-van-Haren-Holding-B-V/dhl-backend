package nl.callido.dhl.dto.delivery

import nl.callido.dhl.domain.ParcelStatus

data class RegisterDeliveryResponse(val barcode: String, val status: ParcelStatus, val duplicate: Boolean)
