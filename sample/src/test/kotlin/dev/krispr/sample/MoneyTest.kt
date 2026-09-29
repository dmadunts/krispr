package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoneyTest {
    @Test
    fun addsSameCurrency() {
        assertEquals(Money(350, "AUD"), Money(200, "AUD") + Money(150, "AUD"))
    }

    @Test
    fun rejectsCurrencyMismatch() {
        assertThrows<IllegalArgumentException> { Money(1, "AUD") + Money(1, "USD") }
    }

    @Test
    fun positivity() {
        assertTrue(Money(1, "AUD").isPositive())
        assertFalse(Money(0, "AUD").isPositive())
    }
}
