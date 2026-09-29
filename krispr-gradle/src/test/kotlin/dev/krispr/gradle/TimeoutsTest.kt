package dev.krispr.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimeoutsTest {
    private val own = mapOf("a" to 100L, "b" to 300L, "slow" to 50_000L, "recordedSlowly" to 6_000L)

    private fun timing(jvmStart: Long?, setup: Long, framework: Long = setup, run: Long = 1_000) = ForkRunner.Timing(jvmStart, setup, framework, run)

    private fun timeouts(
        baselineMillis: Long = 60_000,
        baseline: ForkRunner.Timing? = null,
        check: List<ForkRunner.Timing?> = emptyList(),
        minimum: Long = 0,
        baselineSelectors: List<String> = listOf("a", "b"),
    ) = Timeouts(baselineMillis, baselineSelectors, own::get, baseline, check, factor = 1.25, constant = 4_000, minimum = minimum)

    @Test
    fun aForkCoversItsTestsAndItsMeasuredStartUp() {
        // #34: the recording was slower than the baseline, so the old start-up (baseline less own time) was 0.
        val timeouts = timeouts(baselineMillis = 5_600, baseline = timing(jvmStart = 80, setup = 5_000), baselineSelectors = listOf("recordedSlowly"))
        assertEquals(5_080, timeouts.forkStartup)
        assertEquals(((100 + 5_080) * 1.25).toLong() + 4_000, timeouts.forFork(listOf("a")))
    }

    @Test
    fun withoutATimingStartUpIsWhatTheBaselineTookBeyondItsTests() {
        val timeouts = timeouts(baselineMillis = 1_400)
        assertEquals(1_000, timeouts.forkStartup)
        assertEquals(((300 + 1_000) * 1.25).toLong() + 4_000, timeouts.forFork(listOf("b")))
    }

    @Test
    fun frameworkTimeAfterTheFirstTestIsSpreadOverTheSelectors() {
        // 400 ms of resets over the baseline's 2 selectors: 200 ms more per selector.
        val timeouts = timeouts(baselineMillis = 1_400, baseline = timing(jvmStart = 0, setup = 1_000, framework = 1_400))
        assertEquals(((100 + 1_000 + 200) * 1.25).toLong() + 4_000, timeouts.forFork(listOf("a")))
    }

    @Test
    fun noTimeoutIsBelowTheMinimum() {
        val timeouts = timeouts(baselineMillis = 1_000, minimum = 10_000)
        assertEquals(10_000, timeouts.max)
        assertEquals(10_000, timeouts.forFork(listOf("a")))
        assertEquals(10_000, timeouts.forWorker(listOf("a"), warm = true))
    }

    @Test
    fun onlyRobolectricGetsAFloorByDefault() {
        assertEquals(10_000, Timeouts.defaultMinimum(robolectric = true))
        assertEquals(0, Timeouts.defaultMinimum(robolectric = false))
        // Plain JVM tests then time out by PIT's formula alone, well under the old 10 s floor.
        val timeouts = timeouts(baselineMillis = 1_000, minimum = Timeouts.defaultMinimum(robolectric = false))
        assertEquals(((100 + 600) * 1.25).toLong() + 4_000, timeouts.forFork(listOf("a")))
    }

    @Test
    fun noTimeoutIsAboveTheBaselineWideOneExceptTheRetry() {
        val timeouts = timeouts(baselineMillis = 20_000)
        assertEquals(29_000, timeouts.max)
        assertEquals(29_000, timeouts.forFork(listOf("slow")))
        assertEquals(29_000, timeouts.forFork(listOf("unrecorded")), "a test without a recorded time gets the baseline-wide timeout")
        assertEquals(58_000, timeouts.forRetry(listOf("slow")))
    }

    @Test
    fun aWorkerUsesItsOwnStartUpColdThenWarm() {
        val timeouts = timeouts(
            baseline = timing(jvmStart = 80, setup = 5_000),
            check = listOf(timing(null, setup = 4_700), timing(null, setup = 12)),
        )
        assertEquals(((100 + 4_700) * 1.25).toLong() + 4_000, timeouts.forWorker(listOf("a"), warm = false))
        assertEquals(((100 + 12) * 1.25).toLong() + 4_000, timeouts.forWorker(listOf("a"), warm = true))
    }

    @Test
    fun withoutACheckAWorkerGetsAForksStartUp() {
        val timeouts = timeouts(baseline = timing(jvmStart = 80, setup = 5_000))
        assertEquals(timeouts.forFork(listOf("a")), timeouts.forWorker(listOf("a"), warm = true))
    }

    @Test
    fun aWorkerTimeoutIsRetriedInAForkWithTwiceAForksTime() {
        val timeouts = timeouts(baseline = timing(jvmStart = 80, setup = 5_000), check = listOf(timing(null, 4_700), timing(null, 12)), minimum = 10_000)
        assertEquals(2 * timeouts.forFork(listOf("a")), timeouts.forRetry(listOf("a")))
    }

    @Test
    fun theTimingLineParsesIntoPhases() {
        val timing = ForkRunner.Timing.parse("1000\t4500\t4600\t5400", spawnedEpochMillis = 920)!!
        assertEquals(80L, timing.jvmStartMillis)
        assertEquals(800L, timing.testsMillis)
    }

    private fun control(millis: Long, timedOut: Boolean = false, exitCode: Int = 0) =
        ForkRunner.Result(exitCode, timedOut, millis, java.io.File("control.log"), emptyList(), activated = false)

    @Test
    fun aTimeoutStandsWhenItsTestsStillFitTheTimeoutWithoutTheMutant() {
        val timeouts = timeouts(minimum = 10_000)
        // (4800 * 1.25) + 4000 = 10000: the tests, re-measured now, still fit the 10 s timeout.
        assertTrue(timeouts.afterTimeout(10_000, control(4_800)) is TimeoutCheck.Kill)
        assertTrue(timeouts.afterTimeout(10_000, control(1_000, exitCode = 1)) is TimeoutCheck.Kill, "a failing test still measured the time")
        // A Robolectric fork: ~5.5 s of sandbox and tests under a ~12 s timeout is the normal case, not load.
        assertTrue(timeouts.afterTimeout(12_000, control(5_500)) is TimeoutCheck.Kill)
    }

    @Test
    fun aTimeoutRunsAgainWithATimeoutFromTheTestsTimeNowWhenTheHostSlowedDown() {
        val timeouts = timeouts(minimum = 10_000)
        // #42: load grew after the timeouts were set; the tests alone now take 9 s of a 10 s timeout.
        val check = timeouts.afterTimeout(10_000, control(9_000))
        assertTrue(check is TimeoutCheck.Retry, check.toString())
        assertEquals((9_000 * 1.25).toLong() + 4_000, (check as TimeoutCheck.Retry).millis)
    }

    @Test
    fun aTimeoutIsUnknownWhenItsTestsAlsoTimeOutWithoutTheMutant() {
        val check = timeouts().afterTimeout(20_000, control(20_050, timedOut = true))
        val reason = (check as TimeoutCheck.Unknown).reason
        assertTrue(reason.startsWith(Timeouts.HOST_TOO_SLOW), reason)
        assertTrue(reason.contains("also ran past the 20000 ms timeout"), reason)
    }

    @Test
    fun aTimeoutIsUnknownWhenTheRunWithoutTheMutantBreaks() {
        val check = timeouts().afterTimeout(20_000, control(200, exitCode = 3))
        val reason = (check as TimeoutCheck.Unknown).reason
        assertTrue(reason.startsWith(Timeouts.HOST_TOO_SLOW), reason)
        assertTrue(reason.contains("exit 3"), reason)
    }
}
