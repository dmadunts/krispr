package dev.krispr.runtime

import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.Filter
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.ClassNameFilter
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.listeners.SummaryGeneratingListener
import java.io.File
import java.io.PrintStream
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

/** [causes]: see [TestRun.causeChain]. */
internal class TestFailure(
    val uniqueId: String,
    val displayName: String,
    val message: String,
    val outOfMemory: Boolean = false,
    val causes: List<String> = emptyList(),
)

internal class TestRunResult(val testsFound: Long, val failures: List<TestFailure>) {
    /** 0 when every test passed, 1 when any failed, 2 when nothing ran; ForkedRunner's exit code. */
    val exitCode: Int
        get() = when {
            failures.isNotEmpty() -> 1
            testsFound == 0L -> 2
            else -> 0
        }

    /** A test failed with an OutOfMemoryError, directly or as the cause of what it threw. */
    val outOfMemory: Boolean get() = failures.any { it.outOfMemory }

    fun printFailures(out: PrintStream) {
        failures.forEach { failure ->
            out.println("FAILED ${failure.displayName}: ${failure.message}")
            failure.causes.forEach { out.println("  $it") }
        }
    }
}

/** Runs tests on the JUnit Platform, for the forked runner and the reused worker JVMs alike. */
internal object TestRun {
    /**
     * Runs the tests named by unique id. With [failFast], it runs them in the given order in batches of
     * 1, 1, 2, 4, … and stops after the first batch with a failure: a mutant needs only one killing test,
     * and the first tests are the cheapest. Batches keep class-level setup from running once per test.
     * A test an earlier batch already ran is not run again: Kotest runs the whole spec of any test
     * selected by unique id, so a spec's later tests have usually run with its first.
     */
    fun byUniqueId(selectors: List<String>, failFast: Boolean): TestRunResult {
        val launcher = LauncherFactory.create()
        if (!failFast) return execute(launcher, request().selectors(selectors.map { DiscoverySelectors.selectUniqueId(it) }))
        var found = 0L
        val executed = ExecutedTests()
        for (batch in batches(selectors)) {
            val pending = batch.filter { it !in executed.ids }
            if (pending.isEmpty()) continue
            val result = execute(launcher, request().selectors(pending.map { DiscoverySelectors.selectUniqueId(it) }), executed)
            found += result.testsFound
            if (result.failures.isNotEmpty()) return TestRunResult(found, result.failures)
        }
        return TestRunResult(found, emptyList())
    }

    /** Every test under [roots], narrowed by the test task's [filters]. */
    fun classpathRoots(roots: Set<Path>, filters: List<Filter<*>>): TestRunResult {
        val selectors = mutableListOf<DiscoverySelector>()
        selectors += DiscoverySelectors.selectClasspathRoots(roots)
        kotestSpecs(roots, filters, Thread.currentThread().contextClassLoader ?: TestRun::class.java.classLoader)
            .mapTo(selectors) { DiscoverySelectors.selectClass(it) }
        return execute(LauncherFactory.create(), request().selectors(selectors).filters(*filters.toTypedArray()))
    }

    /**
     * The Kotest specs under [roots]. Kotest 6's engine finds specs only through class (and unique id)
     * selectors; it ignores classpath roots, which Jupiter, Vintage and Kotest 5 scan. So the specs are
     * also selected by name, as Gradle's `test` selects every class it runs. Kotest ignores class-name
     * filters, so [filters]' are applied here. Empty when Kotest is not on the classpath.
     */
    internal fun kotestSpecs(roots: Set<Path>, filters: List<Filter<*>>, loader: ClassLoader, specClass: String = KOTEST_SPEC): List<String> {
        val spec = loadWithoutInit(specClass, loader) ?: return emptyList()
        val classFilters = filters.filterIsInstance<ClassNameFilter>()
        return classNames(roots)
            .filter { name -> classFilters.all { it.toPredicate().test(name) } }
            .filter { name ->
                val type = loadWithoutInit(name, loader)
                type != null && spec.isAssignableFrom(type) && !Modifier.isAbstract(type.modifiers)
            }
    }

    private const val KOTEST_SPEC = "io.kotest.core.spec.Spec"

    private fun loadWithoutInit(name: String, loader: ClassLoader): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: LinkageError) {
        null
    }

    /** The binary names of the classes in the directories among [roots], sorted. */
    internal fun classNames(roots: Set<Path>): List<String> = roots.map { it.toFile() }.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" && it.name != "package-info.class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .toList()
    }.distinct().sorted()

    internal fun batches(selectors: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var start = 0
        var size = 1
        while (start < selectors.size) {
            val end = minOf(selectors.size, start + size)
            batches += selectors.subList(start, end)
            start = end
            if (batches.size > 1) size *= 2
        }
        return batches
    }

    private fun request(): LauncherDiscoveryRequestBuilder {
        val request = LauncherDiscoveryRequestBuilder.request()
        if (Recorder.enabled) {
            // Hits are attributed to the one test running at a time; see Recorder.
            request.configurationParameter("junit.jupiter.execution.parallel.enabled", "false")
        }
        return request
    }

    private fun outOfMemory(error: Throwable): Boolean =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(20).any { it is OutOfMemoryError }

    /**
     * What a failure's log shows beyond its exception's own message (#36): each cause's class and message,
     * then the first [frames] stack frames of the root cause, which says where a set-up failure a
     * framework wrapped (a mock that could not be made, a sandbox that could not be built) came from.
     */
    internal fun causeChain(error: Throwable, frames: Int = ROOT_CAUSE_FRAMES): List<String> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(MAX_CAUSES).toList()
        val root = chain.last()
        val trace = root.stackTrace
        return chain.drop(1).map { "caused by: $it" } +
            trace.take(frames).map { "  at $it" } +
            listOfNotNull("  ... ${trace.size - frames} more".takeIf { trace.size > frames })
    }

    private const val MAX_CAUSES = 20
    private const val ROOT_CAUSE_FRAMES = 8

    /** The unique ids of every test and container that finished, across runs. */
    private class ExecutedTests : TestExecutionListener {
        val ids = HashSet<String>()

        override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
            ids += testIdentifier.uniqueId
        }
    }

    private fun execute(launcher: Launcher, request: LauncherDiscoveryRequestBuilder, vararg listeners: TestExecutionListener): TestRunResult {
        val summary = SummaryGeneratingListener()
        launcher.execute(request.build(), summary, *listeners)
        val result = summary.summary
        return TestRunResult(
            result.testsFoundCount,
            result.failures.map {
                TestFailure(
                    it.testIdentifier.uniqueId, it.testIdentifier.displayName, it.exception.toString(), outOfMemory(it.exception),
                    causeChain(it.exception),
                )
            },
        )
    }
}
