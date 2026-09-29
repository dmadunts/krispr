package dev.krispr.runtime

import org.junit.platform.engine.Filter
import org.junit.platform.engine.discovery.ClassNameFilter
import org.junit.platform.launcher.EngineFilter
import org.junit.platform.launcher.TagFilter
import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

/**
 * Entry point for the recording JVM and each per-mutant JVM: runs the tests named by JUnit unique id
 * (or every test when given `*`) and exits 0 when all pass, 1 when any fail, 2 when nothing ran.
 *
 * Usage: `ForkedRunner <file listing one unique id per line>`; the active mutant comes from
 * `-Dkrispr.active`, and `-Dkrispr.testDirs` (path-separated) roots the `*` selector.
 * `-Dkrispr.filters` names a properties file carrying the test task's tag, engine and class-name
 * filters (newline-separated lists), which only apply to the `*` selector: unique ids were already
 * filtered when they were recorded. `-Dkrispr.failFast=true` runs the ids in order and stops at the
 * first failing batch (see [TestRun.byUniqueId]). Before exiting it prints a [FAILURE_MARKER] line per
 * failed test, and [ACTIVATED_MARKER] when the active mutant was reached. [MEMORY_ERROR_MARKER] means a
 * test, or the runner itself, ran out of memory; the runner exits [MEMORY_ERROR_EXIT] when nothing
 * else could report it. Last comes a [PhaseTiming] line.
 */
object ForkedRunner {
    const val TEST_DIRS_PROPERTY = "krispr.testDirs"
    const val FILTERS_PROPERTY = "krispr.filters"
    const val FAIL_FAST_PROPERTY = "krispr.failFast"
    const val FAILURE_MARKER = "KRISPR-FAILED\t"
    const val ACTIVATED_MARKER = "KRISPR-ACTIVATED"
    const val MEMORY_ERROR_MARKER = "KRISPR-MEMORY-ERROR"
    const val MEMORY_ERROR_EXIT = 3

    private fun oneLine(text: String) = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    @JvmStatic
    fun main(args: Array<String>) {
        val entered = System.currentTimeMillis()
        val ids = File(args.single()).readLines().map { it.trim() }.filter { it.isNotEmpty() }
        FrameworkOverhead.startRun()
        val started = System.nanoTime()
        val result = try {
            if (Recorder.ALL_TESTS in ids) {
                TestRun.classpathRoots(testRoots(), readFilters())
            } else {
                TestRun.byUniqueId(ids, failFast = System.getProperty(FAIL_FAST_PROPERTY).toBoolean())
            }
        } catch (e: OutOfMemoryError) {
            println(MEMORY_ERROR_MARKER)
            if (Mutants.activated) println(ACTIVATED_MARKER)
            System.out.flush()
            exitProcess(MEMORY_ERROR_EXIT)
        }
        result.printFailures(System.err)
        if (result.outOfMemory) println(MEMORY_ERROR_MARKER)
        // For the Gradle task, which reads the log: failures by unique id, and whether the mutant was reached.
        result.failures.forEach { println("$FAILURE_MARKER${oneLine(it.uniqueId)}\t${oneLine(it.displayName)}") }
        if (Mutants.activated) println(ACTIVATED_MARKER)
        println(PhaseTiming.line(entered, (System.nanoTime() - started) / 1_000_000))
        System.out.flush()
        exitProcess(result.exitCode)
    }

    private fun testRoots() = System.getProperty(TEST_DIRS_PROPERTY).orEmpty()
        .split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it).toPath() }.toSet()

    private fun readFilters(): List<Filter<*>> {
        val path = System.getProperty(FILTERS_PROPERTY) ?: return emptyList()
        val properties = Properties().apply { File(path).inputStream().use { load(it) } }
        fun list(key: String): List<String> =
            properties.getProperty(key).orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        val filters = mutableListOf<Filter<*>>()
        list("includeTags").takeIf { it.isNotEmpty() }?.let { filters += TagFilter.includeTags(it) }
        list("excludeTags").takeIf { it.isNotEmpty() }?.let { filters += TagFilter.excludeTags(it) }
        list("includeEngines").takeIf { it.isNotEmpty() }?.let { filters += EngineFilter.includeEngines(it) }
        list("excludeEngines").takeIf { it.isNotEmpty() }?.let { filters += EngineFilter.excludeEngines(it) }
        list("includeClasses").takeIf { it.isNotEmpty() }?.let { filters += ClassNameFilter.includeClassNamePatterns(*it.toTypedArray()) }
        list("excludeClasses").takeIf { it.isNotEmpty() }?.let { filters += ClassNameFilter.excludeClassNamePatterns(*it.toTypedArray()) }
        return filters
    }
}
