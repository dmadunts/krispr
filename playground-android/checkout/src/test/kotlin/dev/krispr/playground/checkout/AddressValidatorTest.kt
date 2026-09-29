package dev.krispr.playground.checkout

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressValidatorTest {
    @Test
    fun postcodes() {
        assertEquals("SW1A 1AA", AddressValidator.normalisePostcode("sw1a1aa"))
        assertEquals("AB1", AddressValidator.normalisePostcode("ab1"))
    }

    @Test
    fun problems() {
        assertEquals(emptyList<String>(), AddressValidator.problems(Address("Ann", "1 High St", "Leeds", "ls1 4ap", "GB")))
        assertEquals(listOf("name", "line1", "postcode"), AddressValidator.problems(Address(" ", "1", "Leeds", "123", "GB")))
        assertEquals(listOf("city", "country"), AddressValidator.problems(Address("Bo", "Rue 1", "", "75001", "FRA")))
    }
}
