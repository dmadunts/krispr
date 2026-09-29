package dev.krispr.playground.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Needs no Android API, but the team runs every test in this module under Robolectric.
@RunWith(RobolectricTestRunner::class)
class CatalogFilterTest {
    private fun ids(spec: FilterSpec) = CatalogFilter.apply(Fixtures.all, spec).map { it.id }

    @Test
    fun priceRangeUsesSalePrice() {
        assertEquals(listOf("b2", "m1"), ids(FilterSpec(minCents = 2499, maxCents = 2900)))
    }

    @Test
    fun categoriesAndStock() {
        assertEquals(listOf("b1", "b2"), ids(FilterSpec(categories = setOf(Category.BOOKS, Category.MUSIC), inStockOnly = true)))
    }

    @Test
    fun ratingAndTag() {
        assertEquals(listOf("b1", "m1"), ids(FilterSpec(tag = "WINTER")))
        assertEquals(listOf("b2", "t1"), ids(FilterSpec(minRating = 4.5)))
    }

    @Test
    fun sorts() {
        assertEquals(listOf("t1", "g1", "b2", "m1", "b1", "h1"), ids(FilterSpec(sort = Sort.PRICE_HIGH)))
        assertEquals(listOf("b2", "t1", "b1", "m1", "g1", "h1"), ids(FilterSpec(sort = Sort.RATING)))
        assertEquals("g1", ids(FilterSpec(sort = Sort.NEWEST)).first())
    }

    @Test
    fun bounds() {
        assertEquals(799L..7999L, CatalogFilter.priceBounds(Fixtures.all))
        assertNull(CatalogFilter.priceBounds(emptyList()))
    }

    @Test
    fun counts() {
        assertEquals(2, CatalogFilter.countByCategory(Fixtures.all)[Category.BOOKS])
    }
}
