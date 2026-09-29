package dev.krispr.runtime

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.Socket
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import kotlin.system.exitProcess

/**
 * A long-lived JVM that runs many mutants, one at a time, so they share the JVM start-up and the
 * loaded, JIT-compiled JDK, JUnit and library classes. Each mutant gets a fresh [IsolatingClassLoader]
 * over the project's own classes (`-Dkrispr.isolated`, path-separated) and a fresh thread; system
 * properties, the default locale and the default time zone are restored after it.
 *
 * Usage: `MutantWorker <port>`. It connects to the Gradle task on the loopback port and serves requests:
 *
 * - request: `int mutant` ([SHUTDOWN] to exit), `UTF log file`, `boolean failFast`, `int n`, n × `UTF unique id`
 * - response: `int exitCode` (ForkedRunner's codes, or [ERROR] when the run itself threw), `long millis`,
 *   `int n`, n × (`UTF unique id`, `UTF display name`, `UTF first line of the message`) of the failures,
 *   `boolean activated` (the mutant was reached), `int retire` ([KEEP], [FRAMEWORK_FAILURE] or [RETIRE])
 *
 * `retire` asks the caller to retire this JVM. [RETIRE]: a thread the mutant started is still running, the
 * heap is nearly full, or the run threw. [FRAMEWORK_FAILURE]: a Robolectric test failed. A Robolectric
 * sandbox, and the project classes in it, outlive the mutant (only [Mutants.activeId] switches inside it),
 * and a failed test may leave it in a state later mutants must not inherit; the caller either retires the
 * JVM or reruns the failed tests here with no mutant active and keeps it only if they pass. A run that ran
 * out of memory reports [ERROR] and [RETIRE]: in a reused JVM the memory may have leaked from earlier
 * mutants, so only a fresh fork may call it the mutant's MEMORY_ERROR. The caller kills the JVM on a
 * timeout, and reruns a mutant in a fresh fork if the worker dies or reports [ERROR], so a mutant's status
 * never depends on how a worker failed.
 */
object MutantWorker {
    const val ISOLATED_PROPERTY = "krispr.isolated"
    const val SHUTDOWN = Int.MIN_VALUE
    const val ERROR = -2
    const val KEEP = 0
    const val FRAMEWORK_FAILURE = 1
    const val RETIRE = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val isolated = System.getProperty(ISOLATED_PROPERTY).orEmpty()
            .split(File.pathSeparator).filter { it.isNotEmpty() }.map { File(it) }
        val socket = Socket(InetAddress.getLoopbackAddress(), args.single().toInt())
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val ownThreads = Thread.getAllStackTraces().keys
        while (true) {
            val mutant = input.readInt()
            if (mutant == SHUTDOWN) break
            val log = File(input.readUTF())
            val failFast = input.readBoolean()
            val selectors = List(input.readInt()) { input.readUTF() }

            val started = System.nanoTime()
            Mutants.activated = false
            val result = runIsolated(mutant, selectors, failFast, log, isolated)
            val activated = Mutants.activated
            val millis = (System.nanoTime() - started) / 1_000_000

            // A run that threw or ran out of memory leaves the JVM suspect; retire it.
            val broken = result == null || result.outOfMemory
            output.writeInt(result?.takeIf { !broken }?.exitCode ?: ERROR)
            output.writeLong(millis)
            val failures = result?.failures.orEmpty()
            output.writeInt(failures.size)
            failures.forEach {
                output.writeUTF(it.uniqueId.take(MAX_UTF))
                output.writeUTF(it.displayName.take(MAX_UTF))
                output.writeUTF(firstLine(it))
            }
            output.writeBoolean(activated)
            output.writeInt(retirement(broken, { strayThreads(ownThreads) || heapNearlyFull() }, result?.exitCode, FrameworkOverhead.ranThisRun()))
            output.flush()
        }
        exitProcess(0)
    }

    /**
     * What a run asks of the caller: [RETIRE] when the run threw or ran out of memory ([broken]) or left the
     * JVM [suspect], whatever its tests did; [FRAMEWORK_FAILURE] when a test failed under Robolectric; else [KEEP].
     */
    internal fun retirement(broken: Boolean, suspect: () -> Boolean, exitCode: Int?, frameworkRan: Boolean): Int = when {
        broken || suspect() -> RETIRE
        exitCode == 1 && frameworkRan -> FRAMEWORK_FAILURE
        else -> KEEP
    }

    /** [failure]'s first message line, for the caller's log; the whole message is in the run's log. */
    internal fun firstLine(failure: TestFailure): String = failure.message.lineSequence().first().take(MAX_MESSAGE)

    /** Null when the run itself failed rather than a test. */
    private fun runIsolated(mutant: Int, selectors: List<String>, failFast: Boolean, log: File, isolated: List<File>): TestRunResult? {
        val properties = System.getProperties().clone() as Properties
        val locale = Locale.getDefault()
        val timeZone = TimeZone.getDefault()
        val out = System.out
        val err = System.err
        val loader = IsolatingClassLoader(isolated, ClassLoader.getSystemClassLoader())
        var result: TestRunResult? = null
        FrameworkOverhead.startRun()
        val started = System.nanoTime()
        PrintStream(FileOutputStream(log), true).use { stream ->
            System.setOut(stream)
            System.setErr(stream)
            // Robolectric-style sandboxes read the property when they load their own copy of Mutants.
            System.setProperty(Mutants.ACTIVE_PROPERTY, mutant.toString())
            Mutants.activeId = mutant
            try {
                val thread = Thread({
                    try {
                        result = TestRun.byUniqueId(selectors, failFast)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }, "krispr-mutant-$mutant")
                thread.contextClassLoader = loader
                thread.start()
                thread.join()
                result?.printFailures(stream)
                stream.println(PhaseTiming.line(-1, (System.nanoTime() - started) / 1_000_000))
            } finally {
                Mutants.activeId = Mutants.NONE
                System.setOut(out)
                System.setErr(err)
                System.setProperties(properties)
                Locale.setDefault(locale)
                TimeZone.setDefault(timeZone)
                loader.close()
            }
        }
        return result
    }

    /** A thread the tests started that is still busy; it would steal CPU from later mutants, or loop forever. */
    private fun strayThreads(own: Set<Thread>): Boolean {
        // The JVM's own threads (Attach Listener and the like) are in the "system" group.
        fun busy() = Thread.getAllStackTraces().keys.filter {
            it !in own && it.isAlive && it.state == Thread.State.RUNNABLE && it.threadGroup?.name != "system"
        }
        if (busy().isEmpty()) return false
        Thread.sleep(200)
        return busy().isNotEmpty()
    }

    private fun heapNearlyFull(): Boolean {
        val runtime = Runtime.getRuntime()
        if (runtime.totalMemory() - runtime.freeMemory() < runtime.maxMemory() * 0.7) return false
        System.gc()
        return runtime.totalMemory() - runtime.freeMemory() > runtime.maxMemory() * 0.7
    }

    /** `writeUTF` takes at most 65535 bytes; a third of that leaves room for multi-byte characters. */
    private const val MAX_UTF = 20_000

    private const val MAX_MESSAGE = 500
}
