package nl.callido.dhl.common

/**
 * T-shirt sizing for parcels and compartments. The REAL dimensions are leading:
 * a parcel's size is always derived from its measured dimensions, never stored.
 * Each size carries the inner dimensions of the matching compartment type.
 *
 * Fit is orientation-free: dimensions are compared sorted, so a parcel may be
 * rotated to fit.
 */
enum class ParcelSize(val maxLengthCm: Int, val maxWidthCm: Int, val maxHeightCm: Int) {
    XXS(35, 25, 3),
    XS(35, 25, 8),
    S(43, 31, 12),
    M(43, 31, 20),
    L(58, 43, 28),
    XL(62, 48, 40),
    XXL(75, 60, 50),
    ;

    fun fits(lengthCm: Int, widthCm: Int, heightCm: Int): Boolean {
        val parcel = listOf(lengthCm, widthCm, heightCm).sortedDescending()
        val box = listOf(maxLengthCm, maxWidthCm, maxHeightCm).sortedDescending()
        return parcel[0] <= box[0] && parcel[1] <= box[1] && parcel[2] <= box[2]
    }

    companion object {
        /** Smallest size that fits the given dimensions, or null if nothing does. */
        fun forDimensions(lengthCm: Int, widthCm: Int, heightCm: Int): ParcelSize? =
            entries.firstOrNull { it.fits(lengthCm, widthCm, heightCm) }
    }
}
