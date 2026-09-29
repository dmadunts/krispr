package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoutingTest {
    @Test
    fun fees() {
        assertEquals(0, Routing.fee(60, member = true))
        assertEquals(5, Routing.fee(60, member = false))
        assertEquals(5, Routing.fee(20, member = true))
        assertEquals(0, Routing.fee(150, member = false))
    }

    @Test
    fun zones() {
        assertEquals("domestic", Routing.zone("NZ", remote = false))
        assertEquals("manual", Routing.zone("AU", remote = true))
        // Weak: `country.isEmpty()` is never the only reason for a manual zone.
        assertEquals("international", Routing.zone("AU", remote = false))
    }

    @Test
    fun depots() {
        assertEquals("AKL", Routing.depot("AKL"))
        assertEquals("HUB", Routing.depot(null))
    }

    @Test
    fun hopsAndRetries() {
        assertEquals(3, Routing.hops(250, 100))
        assertEquals(0, Routing.hops(0, 100))
        assertEquals(3, Routing.retries(3))
    }
}
