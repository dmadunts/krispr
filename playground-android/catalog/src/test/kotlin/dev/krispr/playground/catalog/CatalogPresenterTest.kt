package dev.krispr.playground.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CatalogPresenterTest {
    private val presenter = CatalogPresenter(RuntimeEnvironment.getApplication())

    @Test
    fun firstPage() {
        val screen = presenter.present(Fixtures.all, FilterSpec(sort = Sort.PRICE_LOW), 1)
        assertEquals("6 results", screen.header)
        assertEquals(listOf("h1", "b1", "m1", "b2"), screen.rows.map { it.id })
        assertEquals("Page 1 of 2", screen.pageLabel)
        assertEquals(listOf(1, 2), screen.pages)
        assertFalse(screen.empty)
    }

    @Test
    fun rowsDimOutOfStockAndTruncateTitles() {
        val screen = presenter.present(Fixtures.all, FilterSpec(categories = setOf(Category.MUSIC, Category.BOOKS)), 1)
        val vinyl = screen.rows.single { it.id == "m1" }
        assertTrue(vinyl.dimmed)
        assertEquals("Out of stock", vinyl.stock)
        assertEquals("The Long Winter Nov…", screen.rows.single { it.id == "b1" }.title)
    }

    @Test
    fun emptyResults() {
        val screen = presenter.present(Fixtures.all, FilterSpec(minCents = 100_000), 3)
        assertTrue(screen.empty)
        assertEquals("0 results", screen.header)
        assertEquals("Page 1 of 1", screen.pageLabel)
    }
}
