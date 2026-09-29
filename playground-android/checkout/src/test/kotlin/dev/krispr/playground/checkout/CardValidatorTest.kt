package dev.krispr.playground.checkout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardValidatorTest {
    @Test
    fun luhn() {
        assertTrue(CardValidator.luhn("4111 1111 1111 1111"))
        assertTrue(CardValidator.luhn("5500-0000-0000-0004"))
        assertFalse(CardValidator.luhn("4111 1111 1111 1112"))
        assertFalse(CardValidator.luhn("4111"))
    }

    @Test
    fun networks() {
        assertEquals(Network.VISA, CardValidator.network("4111"))
        assertEquals(Network.MASTERCARD, CardValidator.network("5500"))
        assertEquals(Network.AMEX, CardValidator.network("3782"))
        assertEquals(Network.UNKNOWN, CardValidator.network("6011"))
    }

    @Test
    fun expiry() {
        assertTrue(CardValidator.expiryValid(9, 2026, 9, 2026))
        assertFalse(CardValidator.expiryValid(8, 2026, 9, 2026))
        assertTrue(CardValidator.expiryValid(1, 2027, 9, 2026))
        assertFalse(CardValidator.expiryValid(13, 2027, 9, 2026))
    }

    @Test
    fun cvc() {
        assertTrue(CardValidator.cvcValid("123", Network.VISA))
        assertTrue(CardValidator.cvcValid("1234", Network.AMEX))
        assertFalse(CardValidator.cvcValid("12a", Network.VISA))
    }
}
