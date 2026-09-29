package dev.krispr.gradle

/**
 * Per-mutant timeouts: `(recorded own time of its tests + start-up + framework time per test) * factor +
 * constant`, never less than [minimum] and never more than [max], the baseline-wide timeout, which is also
 * used when a test has no recorded time (#34).
 *
 * Start-up is measured for each kind of runner, because Robolectric's sandbox set-up takes seconds and is
 * recorded against whichever test ran first, yet every fresh JVM pays it:
 * - a fork: the baseline fork's JVM start and framework set-up, or whatever it took beyond its tests'
 *   own time if that is more;
 * - a worker that has not set up a framework yet: the first reuse check's framework set-up;
 * - a warm worker: the second reuse check's, a few milliseconds when the sandbox is reused.
 *
 * Before #34 start-up was only the baseline's time beyond its tests' own time. A recording slower than the
 * baseline made that zero, and fresh Robolectric JVMs timed out at the constant, still building their sandbox.
 */
internal class Timeouts(
    baselineMillis: Long,
    baselineSelectors: List<String>,
    /** A test's recorded own time, without framework set-up and reset; null when it was not recorded. */
    private val ownMillis: (String) -> Long?,
    baselineTiming: ForkRunner.Timing?,
    /** The worker reuse check's two rounds, when it ran. */
    checkTimings: List<ForkRunner.Timing?>,
    private val factor: Double,
    private val constant: Long,
    private val minimum: Long,
) {
    val max = maxOf(minimum, (baselineMillis * factor).toLong() + constant)

    val forkStartup: Long = maxOf(
        baselineTiming?.let { (it.jvmStartMillis ?: 0) + it.setupMillis } ?: 0,
        recordedMillis(baselineSelectors)?.let { baselineMillis - it } ?: 0,
    )

    /** Robolectric's set-up and reset for every test after the first, spread over the baseline's selectors. */
    private val frameworkPerSelector = baselineTiming?.let { (it.frameworkMillis - it.setupMillis).coerceAtLeast(0) / baselineSelectors.size.coerceAtLeast(1) } ?: 0

    val coldWorkerStartup: Long = checkTimings.getOrNull(0)?.setupMillis ?: forkStartup
    val warmWorkerStartup: Long = checkTimings.getOrNull(1)?.setupMillis ?: coldWorkerStartup

    fun forFork(selectors: List<String>): Long = of(selectors, forkStartup)

    /** [warm]: the worker already set up its test framework in an earlier run. */
    fun forWorker(selectors: List<String>, warm: Boolean): Long = of(selectors, if (warm) warmWorkerStartup else coldWorkerStartup)

    /**
     * A reused worker's TIMED_OUT may be the worker's doing (a sandbox set up again, a slow GC over earlier
     * mutants' garbage), so the mutant runs once more in a fresh fork given twice a fork's time, and that
     * verdict counts.
     */
    fun forRetry(selectors: List<String>): Long = 2 * forFork(selectors)

    private fun of(selectors: List<String>, startup: Long): Long {
        val own = recordedMillis(selectors) ?: return max
        return (((own + startup + frameworkPerSelector * selectors.size) * factor).toLong() + constant).coerceIn(minimum, max)
    }

    private fun recordedMillis(selectors: List<String>): Long? = selectors.sumOf { ownMillis(it) ?: return null }

    /**
     * What a TIMED_OUT in a fresh JVM, after [timeoutMillis], means given the [control]: the same tests run
     * again at that moment in a fresh JVM with no mutant active and the same timeout (#42).
     *
     * Timeouts come from start-up and test times measured early in the task. When the host gets busier
     * later, a run can pass its timeout with no help from the mutant, and doubling it once for a worker's
     * retry is not enough when load has grown tenfold. So the timeout is worked out again, by the same
     * formula, from the control's time, which includes the JVM's start-up on the host as it is now:
     * - within it, the mutant ran past a timeout that still covers its tests: TIMED_OUT stands, a kill;
     * - above it, the host has slowed since: the mutant runs once more in a fresh JVM with the new timeout,
     *   and that verdict counts;
     * - the control timed out too, or did not run: UNKNOWN, "host too slow", never a kill.
     */
    fun afterTimeout(timeoutMillis: Long, control: ForkRunner.Result): TimeoutCheck {
        if (control.timedOut) return TimeoutCheck.Unknown("$HOST_TOO_SLOW: its tests also ran past the $timeoutMillis ms timeout without the mutant")
        if (control.exitCode !in 0..1) return TimeoutCheck.Unknown("$HOST_TOO_SLOW to tell: its tests' run without the mutant failed (exit ${control.exitCode})")
        val now = maxOf(minimum, (control.millis * factor).toLong() + constant)
        return if (now <= timeoutMillis) TimeoutCheck.Kill else TimeoutCheck.Retry(now)
    }

    companion object {
        /** Starts the reason of an UNKNOWN from [afterTimeout]; the summary line counts these apart. */
        const val HOST_TOO_SLOW = "host too slow"
    }
}

internal sealed class TimeoutCheck {
    /** The timeout stands, and counts as killed. */
    object Kill : TimeoutCheck()

    /** Run the mutant again in a fresh JVM with [millis]; that verdict counts. */
    class Retry(val millis: Long) : TimeoutCheck()

    class Unknown(val reason: String) : TimeoutCheck()
}
