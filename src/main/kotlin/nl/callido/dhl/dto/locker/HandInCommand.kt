package nl.callido.dhl.dto.locker

data class HandInCommand(val action: HandInAction, val barcode: String? = null)
