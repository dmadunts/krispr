package dev.krispr.playground.pricing

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoneyFormatTest {
    @Test
    fun formats() {
        assertEquals("£0.05", MoneyFormat.format(5))
        assertEquals("£12.30", MoneyFormat.format(1230))
        assertEquals("£1,234,567.89", MoneyFormat.format(123456789))
        assertEquals("-£4.00", MoneyFormat.format(-400))
    }
}
