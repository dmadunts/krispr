package dev.krispr.sample

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InventoryTest {
    private val inventory = Inventory(mapOf("apple" to 5, "pear" to 0))

    @Test
    fun availabilityRespectsStockLevels() = runTest {
        assertTrue(inventory.available("apple", 5))
        assertFalse(inventory.available("apple", 6))
        assertFalse(inventory.available("apple", 0))
        assertFalse(inventory.available("plum", 1))
    }

    @Test
    fun countsReservableLines() = runTest {
        assertEquals(1, inventory.reservable(mapOf("apple" to 2, "pear" to 1, "plum" to 1)))
    }
}
