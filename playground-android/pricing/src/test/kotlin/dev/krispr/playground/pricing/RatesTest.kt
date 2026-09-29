package dev.krispr.playground.pricing

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RatesTest {
    private val rates = Rates(RuntimeEnvironment.getApplication())

    @Test
    fun converts() {
        assertEquals(1170L, rates.convert(1000, "EUR"))
        assertEquals(19050L, rates.convert(100, "JPY"))
        assertEquals(1000L, rates.convert(1000, "XXX"))
    }

    @Test
    fun symbols() {
        assertEquals("€", rates.symbol("EUR"))
        assertEquals("CHF", rates.symbol("CHF"))
    }
}
