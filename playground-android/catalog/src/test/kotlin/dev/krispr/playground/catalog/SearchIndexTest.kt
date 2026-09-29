package dev.krispr.playground.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchIndexTest {
    private val index = SearchIndex(Fixtures.all)

    @Test
    fun tokenizes() {
        assertEquals(listOf("long", "winter", "novel"), SearchIndex.tokenize("The Long-Winter Novel."))
    }

    @Test
    fun exactTokensOutrankSubstrings() {
        assertEquals(listOf("m1", "b1"), index.search("winter").map { it.id })
        assertEquals(listOf("t1"), index.search("cord").map { it.id })
    }

    @Test
    fun emptyQuery() {
        assertEquals(emptyList<Product>(), index.search("the a"))
    }
}
