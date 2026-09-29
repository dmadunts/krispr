package dev.krispr.playground.mocks

import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JUnit in the same module: mocks the same function type outside any Robolectric sandbox, so the JVM
// defines MockK's proxy of kotlin.jvm.functions.Function1 both outside and inside the sandbox.
class TierTest {
    @Test
    fun allowances() {
        assertTrue(Tier.FREE.allows("read"))
        assertFalse(Tier.FREE.allows("share"))
        assertTrue(Tier.PLUS.allows("share"))
        assertFalse(Tier.PLUS.allows("export"))
        assertTrue(Tier.PRO.allows("export"))
    }

    @Test
    fun chargesThroughCallback() {
        val onCharge = mockk<(Long) -> Unit>(relaxed = true)
        assertEquals(998L, Billing(mockk(relaxed = true), onCharge).renew(Tier.PLUS, 2, 0))
        verify { onCharge(998L) }
    }
}
