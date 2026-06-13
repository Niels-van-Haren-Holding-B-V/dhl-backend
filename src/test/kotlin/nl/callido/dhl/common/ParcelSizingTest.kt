package nl.callido.dhl.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParcelSizingTest {

    @Test
    fun `derives the smallest fitting size from real dimensions`() {
        assertEquals(ParcelSize.XXS, ParcelSize.forDimensions(35, 25, 3))
        assertEquals(ParcelSize.XS, ParcelSize.forDimensions(35, 25, 4))
        assertEquals(ParcelSize.S, ParcelSize.forDimensions(40, 30, 11))
        assertEquals(ParcelSize.M, ParcelSize.forDimensions(42, 30, 18))
        assertEquals(ParcelSize.L, ParcelSize.forDimensions(55, 40, 25))
        assertEquals(ParcelSize.XL, ParcelSize.forDimensions(60, 45, 35))
        assertEquals(ParcelSize.XXL, ParcelSize.forDimensions(75, 60, 50))
    }

    @Test
    fun `fit is orientation-free`() {
        assertEquals(ParcelSize.XXS, ParcelSize.forDimensions(3, 25, 35))
        assertEquals(ParcelSize.XXS, ParcelSize.forDimensions(25, 35, 3))
    }

    @Test
    fun `oversized parcels have no size`() {
        assertNull(ParcelSize.forDimensions(80, 60, 50))
        assertNull(ParcelSize.forDimensions(76, 10, 10))
    }
}
