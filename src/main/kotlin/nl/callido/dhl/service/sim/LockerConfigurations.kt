package nl.callido.dhl.service.sim

import nl.callido.dhl.common.ParcelSize

/**
 * Static compartment spec, modelled after a real PostNL-style locker: each
 * compartment has a label ("C3"), a column, a hardware (relay) address, an
 * optional back-door address and an enabled flag.
 */
data class CompartmentSpec(
    val nr: Int,
    val label: String,
    val column: Int,
    val address: Int,
    val backAddress: Int?,
    val size: ParcelSize,
    val enabled: Boolean = true,
)

/**
 * Machine layouts parsed from REAL parcel-machine templates, format
 * `<steel> <hw> [<colNr> <slot>...]...`: a numeric token starts a new column,
 * the tokens after it are the slots in that column, top to bottom.
 *
 * Slot tokens: XS/S/M/L/XL are courier doors. B is the brievenbus — a
 * letterbox with a FIXED hardware address (0, or 20 on steel type 1/2 with a
 * short column) outside the normal address sequence; couriers cannot open
 * it, so it is disabled here. TC is the technical compartment (touch screen,
 * camera, barcode scanner); FC is a functional compartment — both embedded
 * modules, not doors.
 */
object LockerConfigurations {

    const val DEFAULT = "BIG"

    /** Barcode of the hand-out parcel pre-loaded at machine start (first M compartment). */
    const val PRELOADED_HAND_OUT_BARCODE = "DHL-OUT-001"

    private const val MINI_TEMPLATE = "1 2 1 XS M L TC 2 S M XL FC"

    private const val COMPACT_TEMPLATE = "1 5 1 M B L M M 2 M L M M 3 M TC M L M M 4 XL XL M"

    private const val BIG_TEMPLATE =
        "2 9 1 M B L M M 2 M L M M 3 M TC M L M M 4 XL XL M " +
            "5 M L L M M 6 M M M L M M 7 M M M L M M 8 XL XL M"

    private val DOOR_SIZES = mapOf(
        "XS" to ParcelSize.XS,
        "S" to ParcelSize.S,
        "M" to ParcelSize.M,
        "L" to ParcelSize.L,
        "XL" to ParcelSize.XL,
        "XXL" to ParcelSize.XXL,
    )

    private fun parse(template: String): List<CompartmentSpec> {
        val tokens = template.trim().split(Regex("\\s+"))
        val steel = tokens[0].toInt()
        val columns = linkedMapOf<Int, MutableList<String>>()
        var column = 0
        for (token in tokens.drop(2)) {
            val colNr = token.toIntOrNull()
            if (colNr != null) {
                column = colNr
                columns.getOrPut(column) { mutableListOf() }
            } else {
                columns.getValue(column).add(token)
            }
        }
        var nr = 0
        var address = 1
        return columns.flatMap { (col, slots) ->
            slots.mapIndexed { row, token ->
                nr++
                when (token) {
                    "TC", "FC" -> CompartmentSpec(
                        nr = nr,
                        label = token,
                        column = col,
                        address = 0,
                        backAddress = null,
                        size = ParcelSize.M,
                        enabled = false,
                    )
                    "B" -> CompartmentSpec(
                        nr = nr,
                        label = "BUS",
                        column = col,
                        // the letterbox has a fixed hardware address outside the sequence
                        address = if (steel <= 2 && slots.size <= 4) 20 else 0,
                        backAddress = null,
                        size = ParcelSize.XXS,
                        enabled = false,
                    )
                    else -> CompartmentSpec(
                        nr = nr,
                        label = "${'A' + (col - 1)}${row + 1}",
                        column = col,
                        address = address++,
                        backAddress = if (token == "XXL") 200 + nr else null,
                        size = DOOR_SIZES.getValue(token),
                    )
                }
            }
        }
    }

    val byName: Map<String, List<CompartmentSpec>> = mapOf(
        "MINI" to parse(MINI_TEMPLATE),
        "COMPACT" to parse(COMPACT_TEMPLATE),
        "BIG" to parse(BIG_TEMPLATE),
    )
}
