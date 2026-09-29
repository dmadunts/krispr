package dev.krispr.playground.checkout

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CheckoutTextTest {
    private val text = CheckoutText(RuntimeEnvironment.getApplication())

    @Test
    fun delivery() {
        assertEquals("Arrives tomorrow", text.delivery(1))
        assertEquals("Arrives in 3 days", text.delivery(Shipping.deliveryDays(Zone.NATIONAL, express = false)))
    }

    @Test
    fun card() {
        assertEquals("Card ending 1111", text.card("4111 1111 1111 1111"))
    }
}
