package dev.krispr.sample

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Deliberately flaky, to show krispr's flake guards. Both tests pass in the test task and in krispr's
 * recording run, and behave differently in the JVMs that run mutants.
 */
class FlakyTest {
    private val stable = System.getProperty("org.gradle.test.worker") != null || System.getProperty("krispr.record") != null

    /** Fails without any mutant outside those two runs, so it cannot confirm a kill. */
    @Test
    fun backoffGrows() {
        check(stable) { "flaky" }
        assertEquals(200L, Flaky.backoff(2))
    }

    /** Reaches Flaky.jitter only in those two runs, so its mutants are never activated. */
    @Test
    fun jitterAdds() {
        if (stable) assertEquals(17L, Flaky.jitter(10))
    }
}
