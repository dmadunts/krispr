package dev.krispr.sample.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun parsesAndPrints() {
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals("1.2.3-rc1", Version.parse("1.2.3-rc1").toString())
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("1.2.-3"))
        assertNull(Version.parse("1.2.3-"))
    }

    @Test
    fun ordersByComponentsThenPreRelease() {
        assertTrue(Version(1, 2, 3) < Version(1, 3, 0))
        assertTrue(Version(2, 0, 0) > Version(1, 9, 9))
        assertTrue(Version(1, 0, 0, "rc1") < Version(1, 0, 0))
        assertTrue(Version(1, 0, 0, "alpha") < Version(1, 0, 0, "beta"))
    }

    // Weak on purpose: never checks the 0.x rule, the lower bound or equal versions.
    @Test
    fun acceptsWithinTheMajor() {
        assertTrue(Version(1, 2, 0).accepts(Version(1, 9, 0)))
        assertFalse(Version(1, 2, 0).accepts(Version(2, 0, 0)))
    }

    // Weak on purpose: only the major bump is checked exactly.
    @Test
    fun bumps() {
        assertEquals(Version(2, 0, 0), Version(1, 4, 7).bump(Version.Part.MAJOR))
        assertTrue(Version(1, 4, 7).bump(Version.Part.PATCH) > Version(1, 4, 7))
    }
}
