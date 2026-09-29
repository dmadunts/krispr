package dev.krispr.playground.session

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

// Robolectric resets the main looper's clock for every test, but RefreshThrottle's last refresh lives in
// the sandbox's copy of the class: a second run in the same sandbox sees "refreshed just now".
@RunWith(RobolectricTestRunner::class)
class RefreshThrottleTest {
    @Test
    fun throttlesOnTheMainLooperClock() {
        assertEquals(0L, RefreshThrottle.remainingMillis())
        assertTrue(RefreshThrottle.maybeRefresh())
        assertFalse(RefreshThrottle.maybeRefresh())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_000))
        assertEquals(20_000L, RefreshThrottle.remainingMillis())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20_000))
        assertTrue(RefreshThrottle.maybeRefresh())
        assertEquals(2, RefreshThrottle.refreshes)
    }
}
