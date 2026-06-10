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
            "5 M L L M M 6 M M M L M M 7 M M M L M M 8 XXL L M"

    private val DOOR_SIZES = mapOf(
        "XXS" to ParcelSize.XXS,
        "XS" to ParcelSize.XS,
        "S" to ParcelSize.S,
        "M" to ParcelSize.M,
        "L" to ParcelSize.L,
        "XL" to ParcelSize.XL,
        "XXL" to ParcelSize.XXL,
    )

    /** Door pitch per size (door + frame), cm; modules (TC/FC) have a fixed pitch. */
    val DOOR_PITCH_CM: Map<ParcelSize, Int> = mapOf(
        ParcelSize.XXS to 10,
        ParcelSize.XS to 15,
        ParcelSize.S to 20,
        ParcelSize.M to 30,
        ParcelSize.L to 40,
        ParcelSize.XL to 55,
        ParcelSize.XXL to 75,
    )
    const val MODULE_PITCH_CM = 45

    private fun pitchOf(token: String): Int = when (token) {
        "TC", "FC" -> MODULE_PITCH_CM
        "B" -> DOOR_PITCH_CM.getValue(ParcelSize.XXS)
        else -> DOOR_PITCH_CM.getValue(DOOR_SIZES.getValue(token))
    }

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
        // the letterbox address rule looks at the column as TEMPLATED
        val templatedColumnSize = columns.mapValues { (_, slots) -> slots.size }
        // a machine face is always completely filled: pad every column with
        // extra doors until it reaches the tallest column of the template
        val tallest = columns.values.maxOf { slots -> slots.sumOf(::pitchOf) }
        for (slots in columns.values) {
            var gap = tallest - slots.sumOf(::pitchOf)
            while (gap >= 10) {
                val fill = listOf("M", "S", "XS", "XXS").first { token ->
                    val rest = gap - pitchOf(token)
                    rest == 0 || rest >= 10
                }
                slots.add(fill)
                gap -= pitchOf(fill)
            }
            // a 5cm sliver: swap one small door for the next size up
            if (gap == 5) {
                val swaps = listOf("XXS" to "XS", "XS" to "S", "S" to "M")
                for ((out, repl) in swaps) {
                    val i = slots.indexOf(out)
                    if (i >= 0) {
                        slots[i] = repl
                        break
                    }
                }
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
                        address = if (steel <= 2 && templatedColumnSize.getValue(col) <= 4) 20 else 0,
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
