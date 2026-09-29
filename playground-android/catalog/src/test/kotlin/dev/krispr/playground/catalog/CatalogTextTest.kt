package dev.krispr.playground.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CatalogTextTest {
    private val text = CatalogText(RuntimeEnvironment.getApplication())

    @Test
    fun stockStates() {
        assertEquals("Out of stock", text.stock(Fixtures.vinyl))
        assertEquals("Only 3 left", text.stock(Fixtures.atlas))
        assertEquals("In stock", text.stock(Fixtures.novel))
        assertEquals("In stock", text.stock(Fixtures.novel.copy(stock = 5)))
        assertEquals("Only 4 left", text.stock(Fixtures.novel.copy(stock = 4)))
    }

    @Test
    fun resultCounts() {
        assertEquals("1 result", text.results(1))
        assertEquals("7 results", text.results(7))
    }

    @Test
    fun ratings() {
        assertEquals("No reviews yet", text.rating(Fixtures.trowel))
        assertEquals("4.4 (120 reviews)", text.rating(Fixtures.novel))
    }

    @Test
    fun badges() {
        assertEquals(listOf("Sale", "New"), text.badges(Fixtures.atlas))
        assertEquals(emptyList<String>(), text.badges(Fixtures.novel))
    }

    @Test
    fun prices() {
        assertEquals("£12.99", text.price(1299))
        assertEquals("£7.05", text.price(705))
        assertEquals("From £7.99", text.priceFrom(Fixtures.all))
        assertNull(text.priceFrom(emptyList()))
    }
}
