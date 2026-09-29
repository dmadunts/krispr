package dev.krispr.sample.lib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CartCalculatorTest {
    private fun cart(vararg lines: Pair<Int, Long>, coupon: Int = 0) =
        Cart(lines.mapIndexed { i, (q, c) -> CartLine("sku$i", q, c) }, coupon)

    @Test
    fun subtotalMultipliesQuantityByUnitPrice() {
        assertEquals(700L, CartCalculator.subtotal(cart(2 to 200L, 3 to 100L)))
    }

    @Test
    fun couponIsCappedAtHalf() {
        assertEquals(1_000L, CartCalculator.discount(cart(1 to 2_000L, coupon = 80)))
        assertEquals(200L, CartCalculator.discount(cart(1 to 2_000L, coupon = 10)))
    }

    @Test
    fun shippingIsFreeFromTheThresholdAndForEmptyCarts() {
        assertEquals(0L, CartCalculator.shipping(cart(1 to 5_000L)))
        assertEquals(499L, CartCalculator.shipping(cart(1 to 4_999L)))
        assertEquals(0L, CartCalculator.shipping(cart()))
    }

    // Deliberately weak: only checks that a total is positive, so arithmetic mutants in total() survive.
    @Test
    fun totalIsPositive() {
        assertTrue(CartCalculator.total(cart(1 to 1_000L, coupon = 10)) > 0)
    }
}
