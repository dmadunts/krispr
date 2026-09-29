package dev.krispr.runtime

import dev.krispr.fixture.NullMessageFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MutantWorkerTest {
    @Test
    fun aRobolectricKillAsksForAHealthCheck() {
        assertEquals(MutantWorker.FRAMEWORK_FAILURE, MutantWorker.retirement(broken = false, suspect = { false }, exitCode = 1, frameworkRan = true))
    }

    @Test
    fun aPlainKillOrAPassKeepsTheWorker() {
        assertEquals(MutantWorker.KEEP, MutantWorker.retirement(broken = false, suspect = { false }, exitCode = 1, frameworkRan = false))
        assertEquals(MutantWorker.KEEP, MutantWorker.retirement(broken = false, suspect = { false }, exitCode = 0, frameworkRan = true))
    }

    /** An OutOfMemoryError, a stray thread or a full heap retires the worker even when a Robolectric test killed. */
    @Test
    fun aSuspectJvmRetiresWhateverItsTestsDid() {
        assertEquals(MutantWorker.RETIRE, MutantWorker.retirement(broken = true, suspect = { false }, exitCode = 1, frameworkRan = true))
        assertEquals(MutantWorker.RETIRE, MutantWorker.retirement(broken = false, suspect = { true }, exitCode = 1, frameworkRan = true))
        assertEquals(MutantWorker.RETIRE, MutantWorker.retirement(broken = true, suspect = { false }, exitCode = null, frameworkRan = false))
    }

    /**
     * `Throwable.message` may be null, but a failure's message is the error's `toString()`, which then is
     * its class name: the worker's first line of it never sees a null.
     */
    @Test
    fun aFailureWithoutAMessageIsNamedByItsClass() {
        val previous = System.setProperty(NullMessageFailure.RUN, "true")
        val result = try {
            TestRun.byUniqueId(listOf("[engine:junit-jupiter]/[class:${NullMessageFailure::class.java.name}]/[method:fails()]"), failFast = false)
        } finally {
            if (previous == null) System.clearProperty(NullMessageFailure.RUN) else System.setProperty(NullMessageFailure.RUN, previous)
        }
        val failure = result.failures.single()
        assertNull(AssertionError().message)
        assertEquals("java.lang.AssertionError", failure.message)
        assertEquals("java.lang.AssertionError", MutantWorker.firstLine(failure))
    }

    @Test
    fun theFirstLineOfAMultiLineMessage() {
        assertEquals("expected: <2>", MutantWorker.firstLine(TestFailure("id", "t", "expected: <2>\nbut was: <3>")))
        assertEquals("", MutantWorker.firstLine(TestFailure("id", "t", "")))
    }
}
