package dev.krispr.playground.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenParserTest {
    @Test
    fun parses() {
        assertEquals(Token("ann", 5, setOf("read", "write")), TokenParser.parse("ann.5.read,write"))
        assertEquals(Token("bob", 0, emptySet()), TokenParser.parse("bob.0."))
        assertNull(TokenParser.parse("ann.5"))
        assertNull(TokenParser.parse(" .5.x"))
        assertNull(TokenParser.parse("ann.-1.x"))
        assertNull(TokenParser.parse("ann.x.x"))
    }

    @Test
    fun expiry() {
        val t = Token("ann", 100, emptySet())
        assertFalse(TokenParser.expired(t, 150, 50))
        assertTrue(TokenParser.expired(t, 151, 50))
    }
}
