package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PriceCalculatorTest {
    private val calculator = PriceCalculator()

    @Test
    fun discountTiersAtTheirBoundaries() {
        assertEquals(0, calculator.discountPercent(1))
        assertEquals(0, calculator.discountPercent(9))
        assertEquals(10, calculator.discountPercent(10))
        assertEquals(10, calculator.discountPercent(99))
        assertEquals(20, calculator.discountPercent(100))
    }

    @Test
    fun rejectsNonPositiveQuantity() {
        assertThrows<IllegalArgumentException> { calculator.discountPercent(0) }
    }

    @Test
    fun totalAppliesDiscount() {
        assertEquals(300L, calculator.total(100, 3))
        assertEquals(1800L, calculator.total(100, 20))
        assertEquals(80_000L, calculator.total(800, 125))
    }

    @Test
    fun countsFullPacks() {
        assertEquals(0, calculator.fullPacks(9))
        assertEquals(2, calculator.fullPacks(20))
        assertEquals(2, calculator.fullPacks(25))
    }
}
