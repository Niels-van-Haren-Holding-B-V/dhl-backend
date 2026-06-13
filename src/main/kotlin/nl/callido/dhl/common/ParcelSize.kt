package nl.callido.dhl.common

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
        fun forDimensions(lengthCm: Int, widthCm: Int, heightCm: Int): ParcelSize? =
            entries.firstOrNull { it.fits(lengthCm, widthCm, heightCm) }
    }
}
