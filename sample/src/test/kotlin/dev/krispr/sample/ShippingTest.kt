package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShippingTest {
    private val sydney = Customer("Ada", Address("Sydney", "2000"))
    private val perth = Customer("Bob", Address("Perth", "6000"))

    @Test
    fun cityLabelHandlesMissingData() {
        assertEquals("SYDNEY", Shipping.cityLabel(sydney))
        assertEquals("UNKNOWN", Shipping.cityLabel(Customer("Cy", null)))
        assertEquals("UNKNOWN", Shipping.cityLabel(null))
    }

    @Test
    fun localPostcodes() {
        assertTrue(Shipping.isLocal(sydney))
        assertFalse(Shipping.isLocal(perth))
        assertFalse(Shipping.isLocal(null))
    }

    @Test
    fun shippingCost() {
        assertEquals(500, Shipping.shippingCents(sydney, 5000))
        assertEquals(900 + 12 * 50, Shipping.shippingCents(perth, 1250))
    }
}
