package dev.krispr.playground.mocks

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NotifierTest {
    private val analytics = mockk<Analytics>(relaxed = true)
    private val onMessage = mockk<(String) -> Unit>(relaxed = true)
    private val onCount = mockk<(Int) -> Unit>(relaxed = true)
    private val notifier = Notifier(RuntimeEnvironment.getApplication(), analytics, onMessage, onCount)

    @Test
    fun upgradePrompt() {
        every { analytics.userId() } returns "u1"
        assertTrue(notifier.notifyUpgrade(Tier.FREE, "export"))
        verify { onMessage("Upgrade to use export from 12.99") }
        verify { onCount(1) }
        verify { analytics.track("upgrade_prompt", mapOf("feature" to "export", "user" to "u1")) }
        assertFalse(notifier.notifyUpgrade(Tier.PRO, "export"))
    }

    @Test
    fun welcome() {
        notifier.welcome("  Ann ")
        notifier.welcome(null)
        verify { onMessage("Welcome to test, Ann") }
        verify { onMessage("Welcome to test, there") }
    }
}
