package dev.krispr.runtime

import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.TestPlan
import java.io.File

/**
 * Attributes mutant hits to tests. A JUnit Platform listener rather than a Jupiter extension because
 * it is picked up through ServiceLoader with no change to the tests under analysis, and it sees every
 * engine (Jupiter, Kotest, Vintage) rather than only Jupiter.
 *
 * Tests are recorded by JUnit unique id so the forked runner can re-select exactly them; parameterized
 * invocations are folded into their method-level template. Each test's class, method, duration and own
 * time go to `-Dkrispr.recordTests`, for ordering the tests of a mutant, for timeouts, and for excluding
 * tests by name or as slow. The duration runs from the test's started event to its finished one, so class
 * set-up (`@BeforeAll`, `@BeforeClass`) is outside it; the own time also leaves out the framework time
 * [FrameworkOverhead] reports, such as creating Robolectric's sandbox, which only the first test of a
 * sandbox pays. A last column is 1 when the framework reported while the test (or, for a class, any of
 * its tests) ran, which Robolectric does for each of its tests, and 0 when not.
 */
class RecordingListener : TestExecutionListener {
    private var plan: TestPlan? = null
    private val startedNanos = HashMap<String, Long>()
    private val startedReports = HashMap<String, Long>()
    private val tests = StringBuilder()

    override fun testPlanExecutionStarted(testPlan: TestPlan) {
        plan = testPlan
    }

    override fun executionStarted(testIdentifier: TestIdentifier) {
        if (!Recorder.enabled) return
        val target = selectorTarget(testIdentifier) ?: return
        if (target === testIdentifier) {
            FrameworkOverhead.take()
            startedNanos[target.uniqueId] = System.nanoTime()
            startedReports[target.uniqueId] = FrameworkOverhead.reportCount()
        }
        Recorder.enter(target.uniqueId, displayName(target))
    }

    override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
        if (!Recorder.enabled) return
        val target = selectorTarget(testIdentifier) ?: return
        Recorder.exit()
        val started = startedNanos.remove(target.uniqueId)
        val reportsBefore = startedReports.remove(target.uniqueId)
        if (target === testIdentifier && started != null) {
            val (className, methodName) = when (val source = target.source.orElse(null)) {
                is MethodSource -> source.className to source.methodName
                is ClassSource -> source.className to ""
                else -> return
            }
            val nanos = System.nanoTime() - started
            val own = (nanos - FrameworkOverhead.take()).coerceAtLeast(0)
            val framework = reportsBefore != null && FrameworkOverhead.reportCount() > reportsBefore
            tests.append(target.uniqueId).append('\t').append(className).append('\t').append(methodName).append('\t')
                .append(nanos / 1_000_000).append('\t').append(own / 1_000_000).append('\t').append(if (framework) '1' else '0').append('\n')
        }
    }

    override fun testPlanExecutionFinished(testPlan: TestPlan) {
        if (!Recorder.enabled) return
        Recorder.flush()
        System.getProperty(Recorder.TESTS_PROPERTY)?.let { Recorder.append(File(it), tests.toString()) }
        tests.setLength(0)
    }

    /** The node whose unique id selects this test, or null for nodes that are not tracked (engines). */
    private fun selectorTarget(id: TestIdentifier): TestIdentifier? {
        val source = id.source.orElse(null)
        return when {
            source is ClassSource -> id
            source is MethodSource -> {
                val parent = id.parentIdObject.map { plan?.getTestIdentifier(it) }.orElse(null)
                if (parent != null && parent.source.orElse(null) is MethodSource) parent else id
            }
            else -> null
        }
    }

    private fun displayName(id: TestIdentifier): String = when (val source = id.source.orElse(null)) {
        is MethodSource -> "${source.className.substringAfterLast('.')}.${source.methodName}"
        is ClassSource -> source.className.substringAfterLast('.')
        else -> id.displayName
    }
}
