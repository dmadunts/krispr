package dev.krispr.playground.checkout

import org.junit.Assert.assertEquals
import org.junit.Test

class ShippingTest {
    @Test
    fun weights() {
        assertEquals(1200, Shipping.chargeableGrams(Parcel(1200, 10, 10, 10)))
        assertEquals(2000, Shipping.chargeableGrams(Parcel(500, 20, 25, 20)))
    }

    @Test
    fun costs() {
        assertEquals(300L, Shipping.cost(Parcel(900, 1, 1, 1), Zone.LOCAL, express = false))
        assertEquals(700L, Shipping.cost(Parcel(2500, 1, 1, 1), Zone.NATIONAL, express = false))
        assertEquals(2850L, Shipping.cost(Parcel(1001, 1, 1, 1), Zone.INTERNATIONAL, express = true))
    }

    @Test
    fun days() {
        assertEquals(4, Shipping.deliveryDays(Zone.INTERNATIONAL, express = true))
        assertEquals(1, Shipping.deliveryDays(Zone.LOCAL, express = true))
    }
}
