package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class TagsTest {
    @Test
    fun save() {
        var saved = 0
        val editor = TagEditor { saved++ }
        assertEquals(mapOf("TITLE" to "Song"), editor.save(" Song ", null))
        assertEquals(mapOf("TITLE" to "Song"), editor.lastSaved)
        assertEquals(1, saved)
        assertFalse(editor.saving.value)
        assertNotNull(editor.caption())
    }
}
