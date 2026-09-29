package dev.krispr.playground.mocks

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// A second SDK means a second Robolectric sandbox, with its own copy of MockK, in the same JVM: each copy
// names its first Function1 proxy Function1$Subclass0.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacySdkNotifierTest {
    @Test
    fun welcomeOnOlderSdk() {
        val onMessage = mockk<(String) -> Unit>(relaxed = true)
        val onDone = mockk<() -> Unit>(relaxed = true)
        Notifier(RuntimeEnvironment.getApplication(), mockk(relaxed = true), onMessage).welcome("Bo")
        onDone()
        verify { onMessage("Welcome to test, Bo") }
        verify { onDone() }
    }
}
