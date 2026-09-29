package dev.krispr.playground.mocks

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Mocks an enum constant and a function type in the same Robolectric sandbox.
@RunWith(RobolectricTestRunner::class)
class BillingTest {
    private val analytics = mockk<Analytics>(relaxed = true)
    private val onCharge = mockk<(Long) -> Unit>(relaxed = true)
    private val billing = Billing(analytics, onCharge)

    @Test
    fun yearlyWithLoyalty() {
        assertEquals(11691L, billing.renew(Tier.PRO, 12, 3))
        verify { onCharge(any()) }
    }

    @Test
    fun monthly() {
        assertEquals(998L, billing.renew(Tier.PLUS, 2, 0))
        assertEquals(0L, billing.renew(Tier.FREE, 2, 0))
        assertEquals(0L, billing.renew(Tier.PLUS, 0, 0))
    }

    @Test
    fun mockedTier() {
        val tier = mockk<Tier>()
        every { tier.monthlyCents } returns 1000
        every { tier.name } returns "CUSTOM"
        assertEquals(3000L, billing.renew(tier, 3, 0))
        verify { analytics.track("renew", mapOf("tier" to "CUSTOM", "total" to 3000L)) }
    }
}
