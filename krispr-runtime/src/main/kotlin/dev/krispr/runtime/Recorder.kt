package dev.krispr.runtime

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.function.IntConsumer

/**
 * Coverage recording: which mutant ids were reached while which test was running.
 *
 * The current test is a single global rather than a thread-local so that code the test hands to
 * other threads (coroutine dispatchers, executors) is still attributed to it. The cost is that
 * parallel test execution inside one JVM would mix attributions, so the recording run is forced
 * serial: ForkedRunner turns off Jupiter's parallel execution and the Gradle plugin sets Kotest's
 * parallelism to 1. Mutant runs, which only need pass or fail, keep the project's own settings.
 *
 * Robolectric runs each test inside a sandbox class loader that loads its own copy of every class it
 * does not exempt, this one included. A copy's hits are forwarded to the copy that the forked runner
 * and [RecordingListener] use, from the system class loader, which knows the current test.
 */
object Recorder {
    const val RECORD_PROPERTY = "krispr.record"
    const val RECORD_ENV = "KRISPR_RECORD"

    /** Where [RecordingListener] appends one `selector<TAB>class<TAB>method<TAB>millis` line per test. */
    const val TESTS_PROPERTY = "krispr.recordTests"

    /** Selector used for hits outside any test or test class: run every test. */
    const val ALL_TESTS = "*"

    private val outputPath: String? = System.getProperty(RECORD_PROPERTY) ?: System.getenv(RECORD_ENV)

    @JvmField
    val enabled: Boolean = outputPath != null

    private class Context(val selector: String, val displayName: String)

    private val stack = ArrayDeque<Context>()

    @Volatile
    private var current: Context? = null

    // mutant id -> selectors of the tests that reached it
    private val hits = ConcurrentHashMap<Int, MutableSet<String>>()
    private val displayNames = ConcurrentHashMap<String, String>()

    /** Accepts hits from copies of this class in other class loaders; a JDK type so every copy can call it. */
    @JvmField
    val sink: IntConsumer = IntConsumer { hit(it) }

    private val primary: IntConsumer? = findPrimary()

    private fun findPrimary(): IntConsumer? {
        val system = ClassLoader.getSystemClassLoader()
        if (Recorder::class.java.classLoader === system) return null
        return try {
            val main = Class.forName(Recorder::class.java.name, true, system)
            if (main === Recorder::class.java) null else main.getField("sink").get(null) as IntConsumer
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }

    fun hit(id: Int) {
        primary?.let { it.accept(id); return }
        val selector = current?.selector ?: ALL_TESTS
        val tests = hits.computeIfAbsent(id) { ConcurrentHashMap.newKeySet() }
        if (selector !in tests) tests.add(selector)
    }

    internal fun testsReaching(id: Int): Set<String> = hits[id].orEmpty()

    @Synchronized
    fun enter(selector: String, displayName: String) {
        val context = Context(selector, displayName)
        displayNames[selector] = displayName
        stack.addLast(context)
        current = context
    }

    @Synchronized
    fun exit() {
        stack.removeLastOrNull()
        current = stack.lastOrNull()
    }

    /** Appends `id<TAB>selector<TAB>displayName` lines. */
    @Synchronized
    fun flush() {
        val path = outputPath ?: return
        val text = buildString {
            for ((id, tests) in hits) {
                for (selector in tests) {
                    append(id).append('\t').append(selector).append('\t')
                    append(displayNames[selector] ?: selector).append('\n')
                }
            }
        }
        hits.clear()
        append(File(path), text)
    }

    /** Appends under a file lock because Gradle may run several test JVMs that share one output file. */
    internal fun append(file: File, text: String) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.channel.lock().use {
                raf.seek(raf.length())
                raf.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }
}
