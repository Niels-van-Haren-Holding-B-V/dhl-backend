package nl.callido.dhl.service.sim

import nl.callido.dhl.common.ParcelSize
import kotlin.math.pow

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
 * Parcel machines come in physical configurations that are GENERATED, not
 * hand-rolled: take a fictional machine (N columns with [COLUMN_HEIGHT_CM]
 * of door area each), give every size its real door pitch, and fill the
 * machine with a door count that decays exponentially toward the big sizes —
 * commercial space management: lots of letterbox-plus doors, a couple of XXL
 * (with back doors), never the other way around.
 */
object LockerConfigurations {

    const val DEFAULT = "BIG"

    /** Barcode of the hand-out parcel pre-loaded at machine start (first M compartment). */
    const val PRELOADED_HAND_OUT_BARCODE = "DHL-OUT-001"

    /** Usable door area per column, cm — the fictional machine's inner height. */
    const val COLUMN_HEIGHT_CM = 150

    /** Door pitch per size (door + frame), cm. An XS is a minor postal parcel. */
    val DOOR_PITCH_CM: Map<ParcelSize, Int> = mapOf(
        ParcelSize.XXS to 10,
        ParcelSize.XS to 15,
        ParcelSize.S to 20,
        ParcelSize.M to 30,
        ParcelSize.L to 40,
        ParcelSize.XL to 55,
        ParcelSize.XXL to 75,
    )

    /** Per size step up, the door count drops to ~62% — exponential decay. */
    private const val COUNT_DECAY = 0.62

    /**
     * How many doors of each size fit the machine: counts follow
     * decay^sizeRank, scaled so the total door area fills all columns.
     */
    private fun sizeCounts(columns: Int): Map<ParcelSize, Int> {
        val totalCm = columns * COLUMN_HEIGHT_CM
        val weights = ParcelSize.entries.associateWith { COUNT_DECAY.pow(it.ordinal) }
        val cmPerUnit = weights.entries.sumOf { (size, w) -> w * DOOR_PITCH_CM.getValue(size) }
        val units = totalCm / cmPerUnit
        // floor, never round up: the machine must not be over-allocated — the
        // packer tops remaining space off with XXS doors afterwards
        return ParcelSize.entries.associateWith { size ->
            maxOf(1, (weights.getValue(size) * units).toInt())
        }
    }

    /**
     * First-fit-decreasing bin packing into the columns; every leftover gap
     * is topped off with XXS doors so each column is exactly full. Within a
     * column small doors sit on top, heavy sizes at the bottom.
     */
    private fun generate(columns: Int): List<CompartmentSpec> {
        val bins = List(columns) { mutableListOf<ParcelSize>() }
        val space = IntArray(columns) { COLUMN_HEIGHT_CM }
        val doors = sizeCounts(columns)
            .flatMap { (size, count) -> List(count) { size } }
            .sortedByDescending { DOOR_PITCH_CM.getValue(it) }
        for (size in doors) {
            val pitch = DOOR_PITCH_CM.getValue(size)
            val bin = space.indices.filter { space[it] >= pitch }.maxByOrNull { space[it] } ?: continue
            bins[bin].add(size)
            space[bin] -= pitch
        }
        for (bin in bins.indices) {
            while (space[bin] >= DOOR_PITCH_CM.getValue(ParcelSize.XXS)) {
                bins[bin].add(ParcelSize.XXS)
                space[bin] -= DOOR_PITCH_CM.getValue(ParcelSize.XXS)
            }
            // all pitches are multiples of 5, so at most a 5cm sliver remains;
            // absorb it by swapping one door for a 5cm-taller combination
            if (space[bin] == 5) {
                space[bin] = fillSliver(bins[bin])
            }
        }
        var nr = 0
        return bins.flatMapIndexed { col, sizes ->
            sizes.sorted().mapIndexed { row, size ->
                nr++
                CompartmentSpec(
                    nr = nr,
                    label = "${'A' + col}${row + 1}",
                    column = col + 1,
                    address = 100 + nr,
                    backAddress = if (size == ParcelSize.XXL) 200 + nr else null,
                    size = size,
                )
            }
        }
    }

    /** Swap one door for a combination 5cm taller; returns the remaining gap. */
    private fun fillSliver(bin: MutableList<ParcelSize>): Int {
        fun swap(out: ParcelSize, vararg replacement: ParcelSize): Boolean = bin.remove(out).also { if (it) bin.addAll(replacement) }
        val resolved = swap(ParcelSize.XXS, ParcelSize.XS) ||
            swap(ParcelSize.XS, ParcelSize.S) ||
            swap(ParcelSize.M, ParcelSize.XS, ParcelSize.S) ||
            swap(ParcelSize.L, ParcelSize.XS, ParcelSize.M) ||
            swap(ParcelSize.XL, ParcelSize.S, ParcelSize.L)
        return if (resolved) 0 else 5
    }

    val BIG: List<CompartmentSpec> = generate(columns = 10)
    val COMPACT: List<CompartmentSpec> = generate(columns = 4)

    val byName: Map<String, List<CompartmentSpec>> = mapOf("BIG" to BIG, "COMPACT" to COMPACT)
}
