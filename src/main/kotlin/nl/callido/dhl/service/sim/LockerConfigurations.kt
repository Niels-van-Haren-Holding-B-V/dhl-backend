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
 * A parcel machine comes in different physical configurations. BIG is the
 * default demo machine: 22 compartments, a decent mix of every t-shirt size
 * and 2 XXL (which, like the real thing, have back doors).
 */
object LockerConfigurations {

    const val DEFAULT = "BIG"

    /** Barcode of the hand-out parcel pre-loaded at machine start (in the first M compartment, C3). */
    const val PRELOADED_HAND_OUT_BARCODE = "DHL-OUT-001"

    private fun column(col: Int, startNr: Int, sizes: List<ParcelSize>): List<CompartmentSpec> =
        sizes.mapIndexed { i, size ->
            val nr = startNr + i
            CompartmentSpec(
                nr = nr,
                label = "${'A' + (col - 1)}${i + 1}",
                column = col,
                address = 100 + nr,
                backAddress = if (size == ParcelSize.XXL) 200 + nr else null,
                size = size,
            )
        }

    val BIG: List<CompartmentSpec> = buildList {
        addAll(column(1, 1, listOf(ParcelSize.XXS, ParcelSize.XXS, ParcelSize.XXS, ParcelSize.XS)))
        addAll(column(2, 5, listOf(ParcelSize.XS, ParcelSize.XS, ParcelSize.S, ParcelSize.S)))
        addAll(column(3, 9, listOf(ParcelSize.S, ParcelSize.S, ParcelSize.M, ParcelSize.M)))
        addAll(column(4, 13, listOf(ParcelSize.M, ParcelSize.M, ParcelSize.L, ParcelSize.L)))
        addAll(column(5, 17, listOf(ParcelSize.L, ParcelSize.L, ParcelSize.XL, ParcelSize.XL)))
        addAll(column(6, 21, listOf(ParcelSize.XXL, ParcelSize.XXL)))
    }

    val COMPACT: List<CompartmentSpec> = buildList {
        addAll(column(1, 1, listOf(ParcelSize.XS, ParcelSize.S, ParcelSize.S, ParcelSize.M)))
        addAll(column(2, 5, listOf(ParcelSize.M, ParcelSize.M, ParcelSize.L, ParcelSize.L)))
        addAll(column(3, 9, listOf(ParcelSize.L, ParcelSize.XL, ParcelSize.XL, ParcelSize.XXL)))
    }

    val byName: Map<String, List<CompartmentSpec>> = mapOf("BIG" to BIG, "COMPACT" to COMPACT)
}
