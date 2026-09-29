package dev.krispr.playground.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginationTest {
    @Test
    fun counts() {
        assertEquals(1, Pagination.pageCount(0, 10))
        assertEquals(1, Pagination.pageCount(10, 10))
        assertEquals(2, Pagination.pageCount(11, 10))
    }

    @Test
    fun pages() {
        val items = (1..9).toList()
        val second = Pagination.page(items, 2, 4)
        assertEquals(listOf(5, 6, 7, 8), second.items)
        assertTrue(second.hasNext)
        assertTrue(second.hasPrevious)
        assertEquals(listOf(9), Pagination.page(items, 7, 4).items)
        assertFalse(Pagination.page(items, 3, 4).hasNext)
    }

    @Test
    fun windows() {
        assertEquals(listOf(1, 2, 3), Pagination.window(2, 3))
        assertEquals(listOf(3, 4, 5, 6, 7), Pagination.window(5, 10))
        assertEquals(listOf(6, 7, 8, 9, 10), Pagination.window(10, 10))
    }
}
