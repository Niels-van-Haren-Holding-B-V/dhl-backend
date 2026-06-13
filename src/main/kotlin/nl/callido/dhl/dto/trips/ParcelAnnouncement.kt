package nl.callido.dhl.dto.trips

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import java.util.UUID

data class ParcelAnnouncement(
    @field:NotBlank
    val barcode: String,
    val stopId: UUID? = null,
    @field:Positive
    val lengthCm: Int,
    @field:Positive
    val widthCm: Int,
    @field:Positive
    val heightCm: Int,
    @field:Positive
    val weightG: Int,
)
