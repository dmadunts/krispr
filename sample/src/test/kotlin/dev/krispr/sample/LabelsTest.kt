package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LabelsTest {
    @Test
    fun text() {
        assertEquals("1.2", Labels.version("v1.2-SNAPSHOT"))
        assertEquals("a-b", Labels.slug(" a b "))
        // Weak: no URL has a path, so skipping `substringBefore('/')` goes unnoticed.
        assertEquals("example.com", Labels.host("https://example.com"))
        assertNull(Labels.display(" "))
        assertEquals("Untitled", Labels.title(""))
    }

    @Test
    fun collectionsAndRounding() {
        assertEquals(listOf("a", "b"), Labels.tags(listOf("a"), "b"))
        assertEquals(setOf("a"), Labels.untagged(setOf("a", "b"), "b"))
        assertEquals(1.0, Labels.wholeKilos(1500.0))
    }
}
