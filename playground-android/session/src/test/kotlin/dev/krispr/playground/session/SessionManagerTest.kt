package dev.krispr.playground.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Passes in a fresh JVM. A reused Robolectric sandbox keeps AppGraph and SessionManager from the previous
// run, so install() throws and the numbering does not restart: the #43 "fails in a reused JVM" shape.
@RunWith(RobolectricTestRunner::class)
class SessionManagerTest {
    companion object {
        var now = 1_000L

        @BeforeClass
        @JvmStatic
        fun installGraph() = AppGraph.install(Graph({ now }, maxSessions = 2, tokenTtlMillis = 500))
    }

    @Test
    fun numbersSessionsFromOneAndEvictsOldest() {
        val ann = SessionManager.start("ann")
        assertEquals(1, ann.number)
        assertSame(ann, SessionManager.start("ann"))
        now += 10
        assertEquals(2, SessionManager.start("bob").number)
        now += 10
        assertEquals(3, SessionManager.start("cy").number)
        assertFalse(SessionManager.isActive("ann"))
        assertTrue(SessionManager.isActive("bob"))
        assertEquals(2, SessionManager.activeCount())
        now += 1_000
        assertEquals(0, SessionManager.activeCount())
        assertTrue(SessionManager.end("bob"))
        assertFalse(SessionManager.end("bob"))
    }
}
