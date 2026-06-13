package nl.callido.dhl.dto.locker

data class HandOutCommand(val action: HandOutAction, val barcode: String? = null)
