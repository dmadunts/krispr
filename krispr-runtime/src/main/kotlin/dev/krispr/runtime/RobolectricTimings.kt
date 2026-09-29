package dev.krispr.runtime

import org.robolectric.pluginapi.perf.Metadata
import org.robolectric.pluginapi.perf.Metric
import org.robolectric.pluginapi.perf.PerfStatsReporter
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * A Robolectric plugin (found through META-INF/services, and only ever loaded by Robolectric) that hands
 * [FrameworkOverhead] the time Robolectric spent on each test outside the test itself: creating or
 * picking its sandbox and setting up Android state before the test, and resetting it after. Recording
 * takes it out of each test's own time; mutant runs report it as their framework set-up phase.
 */
class RobolectricTimings : PerfStatsReporter {
    override fun report(metadata: Metadata, metrics: Collection<Metric>) {
        FrameworkOverhead.add(metrics.filter { it.name in OVERHEAD }.sumOf { it.elapsedNs })
    }

    private companion object {
        /** Top-level events; the others Robolectric reports are nested inside these. */
        val OVERHEAD = setOf("initialization", "reset Android state (after test)")
    }
}

/**
 * Test-framework time inside a test's started and finished events, which [RecordingListener] takes out
 * of the test's own time. Kept apart from [RobolectricTimings] so the listener never loads Robolectric's API.
 */
internal object FrameworkOverhead {
    private val nanos = AtomicLong()
    private val reports = AtomicLong()
    private val runNanos = AtomicLong()
    private val runPeakNanos = AtomicLong()

    fun add(elapsedNanos: Long) {
        reports.incrementAndGet()
        nanos.addAndGet(elapsedNanos)
        runNanos.addAndGet(elapsedNanos)
        runPeakNanos.accumulateAndGet(elapsedNanos, ::maxOf)
    }

    /** The overhead reported since the last call. */
    fun take(): Long = nanos.getAndSet(0)

    /** How many times a framework reported so far; a test that ran between two readings that differ ran under it. */
    fun reportCount(): Long = reports.get()

    /** Starts a mutant run's totals; see [PhaseTiming]. */
    fun startRun() {
        runNanos.set(0)
        runPeakNanos.set(0)
    }

    /** Whether a Robolectric test ran since [startRun]. */
    fun ranThisRun(): Boolean = runNanos.get() > 0

    fun runMillis(): Long = runNanos.get() / 1_000_000
    fun runPeakMillis(): Long = runPeakNanos.get() / 1_000_000
}

/**
 * The phase timing line a fork or a worker run prints for the Gradle task:
 * `KRISPR-TIMING<TAB>main entered (epoch ms, or -1 in a worker)<TAB>framework set-up ms<TAB>framework total ms<TAB>run ms<TAB>CPU ms`,
 * the last being the whole JVM's CPU time so far (-1 when the JVM does not tell).
 * Framework set-up is the costliest single test's Robolectric overhead, which in a fresh JVM is building
 * the sandbox; the total adds every other test's set-up and reset.
 */
internal object PhaseTiming {
    const val MARKER = "KRISPR-TIMING\t"

    fun line(mainEnteredEpochMillis: Long, runMillis: Long): String =
        "$MARKER$mainEnteredEpochMillis\t${FrameworkOverhead.runPeakMillis()}\t${FrameworkOverhead.runMillis()}\t$runMillis\t${cpuMillis()}"

    /** A JDK without the `jdk.management` module has no CPU time to give. */
    private fun cpuMillis(): Long = try {
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.processCpuTime
            ?.takeIf { it >= 0 }?.let { it / 1_000_000 } ?: -1
    } catch (e: LinkageError) {
        -1
    }
}
