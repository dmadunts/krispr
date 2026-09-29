package dev.krispr.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.File
import java.lang.management.ManagementFactory
import java.time.LocalTime
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

/**
 * See docs/PHILOSOPHY.md. TIMED_OUT and MEMORY_ERROR (a fresh JVM ran out of memory with the mutant
 * active) count as killed; RUN_ERROR, when a mutant's JVM failed outside any test, does not. A timeout
 * whose tests also time out without the mutant is UNKNOWN instead of TIMED_OUT; see [Timeouts.afterTimeout].
 */
enum class MutantStatus { KILLED, TIMED_OUT, MEMORY_ERROR, SURVIVED, NO_COVERAGE, NOT_MEASURED, UNKNOWN, RUN_ERROR }

/**
 * The end (exclusive) of the failFast batch that [index] falls into, among [total] tests run in priority
 * order. Mirrors `TestRun.batches` in krispr-runtime, which grows batches 1, 1, 2, 4, 8, … and stops at
 * the first batch with a failure: the whole batch containing a killer ran against the mutant, not just
 * the tests up to the killer's own index. Duplicated rather than shared across the module boundary, like
 * ForkRunner's protocol markers mirror ForkedRunner's: krispr-gradle must not gain a compile dependency
 * on krispr-runtime, which would leak its test-engine classpath into every consumer of the plugin.
 */
internal fun batchEnd(index: Int, total: Int): Int {
    var start = 0
    var size = 1
    var batchCount = 0
    while (start < total) {
        val end = minOf(total, start + size)
        if (index < end) return end
        start = end
        batchCount++
        if (batchCount > 1) size *= 2
    }
    return total
}

abstract class KrisprRunTask : KrisprForkTask() {
    /**
     * Written by the instrumented compile, which this task's classpath already depends on. Internal rather
     * than an input file because a module without main sources never writes one: that is zero mutants.
     */
    @get:Internal abstract val manifest: RegularFileProperty

    /** False when this invocation did not instrument the main compilation; fails the task with advice. */
    @get:Internal abstract val instrumented: Property<Boolean>
    @get:InputFile abstract val coverage: RegularFileProperty
    @get:Input abstract val timeoutFactor: Property<Double>
    @get:Input abstract val timeoutConstantMillis: Property<Long>

    /** See [KrisprExtension.timeoutMinimumMillis]; unset: [Timeouts.defaultMinimum]. */
    @get:Input @get:Optional abstract val timeoutMinimumMillis: Property<Long>

    /** At most this many of this module's mutants run at once; 0 or less: [jvmCap]. */
    @get:Input abstract val threads: Property<Int>
    @get:Internal abstract val projectDirectory: DirectoryProperty
    @get:OutputFile abstract val report: RegularFileProperty
    @get:OutputDirectory abstract val logsDirectory: DirectoryProperty

    /** See [KrisprExtension.reuseJvms]. */
    @get:Input abstract val reuseJvms: Property<Boolean>

    /** See [KrisprExtension.robolectricReuse]. */
    @get:Input abstract val robolectricReuse: Property<String>

    /** See [KrisprExtension.useScreenshotTests]. */
    @get:Input abstract val useScreenshotTests: Property<Boolean>

    /** See [KrisprExtension.excludeTests]. */
    @get:Input abstract val excludeTests: ListProperty<String>

    /** See [KrisprExtension.quarantinedTests]. */
    @get:Input abstract val quarantinedTests: ListProperty<String>

    /** See [KrisprExtension.confirmKills]. */
    @get:Input abstract val confirmKills: Property<Boolean>

    /** See [KrisprExtension.confirmSurvivors]. */
    @get:Input abstract val confirmSurvivors: Property<Boolean>

    /** See [KrisprExtension.robolectricKeepAfterKill]. */
    @get:Input abstract val robolectricKeepAfterKill: Property<Boolean>

    /** See [KrisprExtension.diffBase]; `--since <ref>` on the command line. */
    @get:Input @get:Optional
    @get:Option(option = "since", description = "Mutates only the lines changed since the merge base of this ref and HEAD.")
    abstract val since: Property<String>

    /** See [KrisprExtension.diffFailOnSurvivors]. */
    @get:Input abstract val diffFailOnSurvivors: Property<Boolean>

    /** See [KrisprExtension.maxSurvivorsPerFile]. */
    @get:Input abstract val maxSurvivorsPerFile: Property<Int>

    /** See [KrisprExtension.killMatrix]. */
    @get:Input abstract val killMatrix: Property<Boolean>

    /** See [KrisprExtension.slowTestThresholdMs]. */
    @get:Input abstract val slowTestThresholdMs: Property<Long>

    /** See [KrisprExtension.includeSlowTests]. */
    @get:Input abstract val includeSlowTests: Property<Boolean>

    /** See [KrisprExtension.slowTestBudgetMs]. */
    @get:Input abstract val slowTestBudgetMs: Property<Long>

    /** See [KrisprExtension.mode]. */
    @get:Input abstract val mode: Property<String>

    /** See [KrisprExtension.showChanges]. */
    @get:Input abstract val showChanges: Property<Boolean>

    /** See [KrisprExtension.historyFile]; read and written, so not an input or output. */
    @get:Internal abstract val historyFile: RegularFileProperty

    /** See [KrisprExtension.useHistory]. */
    @get:Input abstract val useHistory: Property<Boolean>

    /** Classpath entries under this directory are the build's own code, which reused JVMs reload per mutant. */
    @get:Internal abstract val buildRootDirectory: DirectoryProperty

    init {
        // The summary is the point; always print it.
        outputs.upToDateWhen { false }
        val extension = project.extensions.getByType(KrisprExtension::class.java)
        // -Pkrispr.reuseJvms=false overrides the build script, to compare the two modes on one build.
        reuseJvms.convention(project.providers.gradleProperty("krispr.reuseJvms").map { it.toBoolean() }.orElse(extension.reuseJvms).orElse(true))
        robolectricReuse.convention(project.providers.gradleProperty("krispr.robolectricReuse").orElse(extension.robolectricReuse).orElse(SANDBOX))
        useScreenshotTests.convention(extension.useScreenshotTests.orElse(false))
        excludeTests.convention(extension.excludeTests.orElse(emptyList()))
        quarantinedTests.convention(extension.quarantinedTests.orElse(emptyList()))
        since.convention(diffBaseOf(project, extension))
        diffFailOnSurvivors.convention(
            project.providers.gradleProperty("krispr.diffFailOnSurvivors").map { it.toBoolean() }.orElse(extension.diffFailOnSurvivors).orElse(false),
        )
        maxSurvivorsPerFile.convention(extension.maxSurvivorsPerFile.orElse(3))
        slowTestThresholdMs.convention(extension.slowTestThresholdMs.orElse(2_000L))
        includeSlowTests.convention(extension.includeSlowTests.orElse(false))
        slowTestBudgetMs.convention(extension.slowTestBudgetMs.orElse(10_000L))
        confirmKills.convention(project.providers.gradleProperty("krispr.confirmKills").map { it.toBoolean() }.orElse(extension.confirmKills).orElse(false))
        confirmSurvivors.convention(
            project.providers.gradleProperty("krispr.confirmSurvivors").map { it.toBoolean() }.orElse(extension.confirmSurvivors).orElse(true),
        )
        robolectricKeepAfterKill.convention(
            project.providers.gradleProperty("krispr.robolectricKeepAfterKill").map { it.toBoolean() }
                .orElse(extension.robolectricKeepAfterKill).orElse(true),
        )
        killMatrix.convention(project.providers.gradleProperty("krispr.killMatrix").map { it.toBoolean() }.orElse(extension.killMatrix).orElse(false))
        buildRootDirectory.fileValue(project.rootDir)
    }

    private class Mutant(
        val id: Int,
        val file: String,
        val line: Int,
        val column: Int,
        val operator: String,
        val description: String,
        val declaration: String?,
        /** Runs once per class load, so coverage names only the test that loaded the class first. */
        val initializer: Boolean,
        /** Of the enclosing declaration's source text; see [History]. */
        val hash: String,
    )

    private class Outcome(
        val status: MutantStatus,
        val tests: List<String>,
        val killedBy: String? = null,
        val millis: Long = 0,
        /** "worker", "fork", or "history" for a verdict reused from an earlier run; null when nothing ran. */
        val runner: String? = null,
        val reason: String? = null,
        /** The selector of the test that killed it. */
        val killer: String? = null,
        /**
         * Display names of every test confirmed to have failed and killed this mutant. Complete only with
         * [KrisprExtension.killMatrix]; otherwise at most the one test in [killedBy], since a kill stops
         * the run before the rest of [tests] gets a turn. See [TestValueAnalysis].
         */
        val allKillers: List<String> = emptyList(),
        /**
         * Display names of the tests this outcome is real evidence about: for SURVIVED, every covering
         * test really ran and passed; for KILLED without the kill matrix, only the tests up to and
         * including the killer ran before the run stopped, so the rest say nothing either way.
         */
        val testedTests: List<String> = emptyList(),
    )

    @TaskAction
    fun run() {
        if (!instrumented.get()) {
            throw GradleException(
                "krispr: this invocation did not instrument the main compilation. Request the task by its " +
                    "full name (krisprRun), or pass -P${KrisprGradlePlugin.INSTRUMENT_PROPERTY}=true.",
            )
        }
        val started = System.nanoTime()
        val all = excludeByRules(readManifest())
        val diffRef = since.orNull
        val mutants = diffRef?.let { ref ->
            val changed = ChangedLines.since(ref, projectDirectory.get().asFile)
            all.filter { changed.contains(File(it.file), it.line) }.also {
                logger.lifecycle("krispr: ${it.size} of ${all.size} mutants on lines changed since $ref (merge base ${changed.mergeBase.take(10)})")
            }
        } ?: all
        if (mutants.isEmpty()) {
            // Nothing to run: no baseline, no workers. An empty report still records the run.
            val wallMillis = (System.nanoTime() - started) / 1_000_000
            writeReport(mutants, emptyMap(), wallMillis, emptyMap(), diffRef)
            printSummary(mutants, emptyMap(), wallMillis, emptyMap())
            return
        }
        val (testsByMutant, displayNames) = readCoverage()
        val recorded = readRecordedTests()
        val logs = logsDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        val runner = forkRunner(logs)
        val isolated = isolatedEntries()

        val threshold = slowTestThresholdMs.get()
        val selection = TestSelection(
            screenshotClasses = if (useScreenshotTests.get()) emptyMap() else ScreenshotTests.detect(isolated),
            excludePatterns = excludeTests.get(),
            quarantinePatterns = quarantinedTests.get(),
            slowThresholdMillis = threshold.takeUnless { includeSlowTests.get() },
        )
        val leaves = leaves(recorded.keys)
        val excluded = recorded.values.mapNotNull { test -> selection.exclusionReason(test, test.selector in leaves)?.let { test.selector to it } }.toMap()
        // With slow tests included, each mutant runs its fast tests and then slow ones up to the budget.
        val slow = if (includeSlowTests.get()) recorded.values.filter { it.selector in leaves && it.isSlow(threshold) }.associate { it.selector to it.millis } else emptyMap()
        val budget = slowTestBudgetMs.get()
        if (excluded.isNotEmpty()) {
            val kinds = excluded.values.groupingBy { it.substringBefore(" (").substringBefore(" '") }.eachCount()
            logger.lifecycle("krispr: ${excluded.size} tests may not kill mutants (${kinds.entries.joinToString(", ") { "${it.key}: ${it.value}" }})")
        }
        // A hit outside any test reached every test: the outermost recorded ones. So did a reached class
        // initializer, which only the test that loaded the class first saw run.
        val everyTest = outermost(recorded.keys).ifEmpty { setOf(ALL_TESTS) }
        val reachedBy = mutants.associate { mutant ->
            val selectors = testsByMutant[mutant.id].orEmpty()
            val everywhere = ALL_TESTS in selectors || (mutant.initializer && selectors.isNotEmpty())
            mutant.id to if (everywhere) outermost(selectors - ALL_TESTS + everyTest) else selectors
        }
        // The tests that may kill each mutant, likeliest and cheapest first (see [priority]): a killed mutant
        // stops at its first failing test.
        val overBudget = HashSet<Int>()
        val killers = mutants.associateTo(HashMap()) { mutant ->
            val sorted = reachedBy.getValue(mutant.id).filter { it !in excluded }
                .sortedWith(compareBy<String>({ priority(recorded[it], mutant) }, { it }))
            var spent = 0L
            val affordable = sorted.filter { selector -> slow[selector]?.let { spent += it; spent <= budget } ?: true }
            if (affordable.size < sorted.size) overBudget += mutant.id
            mutant.id to affordable
        }
        fun names(selectors: Collection<String>) = selectors.map { displayNames[it] ?: readableSelector(it) }
        val testTimes = recorded.values.associate { (displayNames[it.selector] ?: readableSelector(it.selector)) to it.millis }

        val outcomes = ConcurrentHashMap<Int, Outcome>()
        for (mutant in mutants) {
            val reached = reachedBy.getValue(mutant.id)
            if (killers.getValue(mutant.id).isNotEmpty()) continue
            outcomes[mutant.id] = if (reached.isEmpty()) {
                Outcome(MutantStatus.NO_COVERAGE, emptyList())
            } else {
                val reasons = reached.mapNotNull { excluded[it] }.distinct() + listOfNotNull("slow tests over slowTestBudgetMs".takeIf { mutant.id in overBudget })
                Outcome(MutantStatus.NOT_MEASURED, names(reached.sorted()), reason = "reached only by excluded tests: ${reasons.joinToString("; ")}")
            }
        }

        // Verdicts of the last run whose code and tests did not change; see [History].
        val candidates = HashMap(killers)
        val extreme = mode.get() == EXTREME
        val historyFile = historyFile.get().asFile.let { if (extreme) File(it.parentFile, "${it.nameWithoutExtension}-$EXTREME.${it.extension}") else it }
        val robolectric = usesRobolectric()
        val routing = Routing(robolectric, recorded)
        fun plainOnly(mutant: Mutant) = routing.plainOnly(killers.getValue(mutant.id))
        val sandboxReuse = when (val value = robolectricReuse.get().trim().lowercase()) {
            SANDBOX -> true
            FRESH -> false
            else -> throw GradleException("krispr: robolectricReuse is '$value'; use '$SANDBOX' or '$FRESH'.")
        }
        val timeoutMinimum = timeoutMinimumMillis.orNull ?: Timeouts.defaultMinimum(robolectric)
        // timeoutControl: TIMED_OUT verdicts from before #42 may be the host's doing, so they are not reused.
        val settings = "$KRISPR_VERSION|${timeoutFactor.get()}|${timeoutConstantMillis.get()}|$timeoutMinimum|${confirmKills.get()}|timeoutControl" +
            if (!robolectric) "" else if (sandboxReuse) "|robolectricReuse=$SANDBOX|confirmSurvivors=${confirmSurvivors.get()}|keepAfterKill=${robolectricKeepAfterKill.get()}" else "|robolectricReuse=$FRESH"
        val history = if (useHistory.get()) History.read(historyFile, settings) else null
        val classHashes = TestClassHashes(testClassesDirs.files)
        fun classHash(selector: String) = classHashes.of(recorded[selector]?.className ?: readableSelector(selector))
        if (history != null) {
            var reused = 0
            for (mutant in mutants) {
                val tests = killers.getValue(mutant.id)
                if (tests.isEmpty() || outcomes.containsKey(mutant.id)) continue
                val entry = history.reusable(mutant.id, mutant.hash, tests, ::classHash)
                if (entry != null) {
                    // The history keeps only the one killer (killedBy is "OutOfMemoryError", not a test, for
                    // MEMORY_ERROR); SURVIVED still proves every one of its tests ran and passed.
                    val killerName = entry.killedBy.takeIf { entry.status == MutantStatus.KILLED }
                    val evidence = if (entry.status == MutantStatus.SURVIVED) entry.names else listOfNotNull(killerName)
                    outcomes[mutant.id] = Outcome(
                        entry.status, entry.names, entry.killedBy, entry.millis, HISTORY, killer = entry.killer,
                        allKillers = listOfNotNull(killerName), testedTests = evidence,
                    )
                    reused++
                } else {
                    // Changed since: the test that killed it last time is the likeliest to kill it again.
                    history.previousKiller(mutant.id)?.takeIf { it in tests }?.let { killers[mutant.id] = listOf(it) + (tests - it) }
                }
            }
            logger.lifecycle("krispr: reused $reused of ${mutants.size} verdicts from $historyFile")
        }

        var covered = mutants.filter { !outcomes.containsKey(it.id) }
        val cap = jvmCap()
        val threadCount = threads.get().takeIf { it > 0 }?.coerceAtMost(cap) ?: cap
        val pool = Executors.newFixedThreadPool(threadCount)
        val workers = WorkerPool(runner, isolated)
        val confirm = confirmKills.get()
        val keepAfterKill = robolectricKeepAfterKill.get()
        val killMatrixEnabled = killMatrix.get()
        val disagreements = AtomicInteger()
        // Timeouts on a host slower than when the timeouts were set: run again, or UNKNOWN.
        val slowRetried = AtomicInteger()
        val slowUnknown = AtomicInteger()
        // Mutants reaching tests the reuse check rejected that their other tests killed in a worker.
        val partialKills = AtomicInteger()
        // Only a Robolectric module's workers share a sandbox between mutants.
        val survivorChecks = SurvivorChecks(robolectric && confirmSurvivors.get())
        var timeoutOf: (List<String>) -> Long = { PROBE_TIMEOUT_MILLIS }
        val changes = ConcurrentHashMap<Int, Change>()
        try {
            if (covered.isNotEmpty()) {
                val baselineSelectors = covered.flatMap { killers.getValue(it.id) }.toSortedSet().toList()
                val baselineRun = pool.submit(Callable { withJvmSlot { runner.run("baseline", null, baselineSelectors, timeoutMillis = 10 * 60_000L) } })
                val plainCount = covered.count { plainOnly(it) }
                if (robolectric) {
                    logger.lifecycle(
                        "krispr: ${covered.size - plainCount} mutants reached by Robolectric tests, $plainCount only by plain tests" +
                            if (plainCount > 0) " (these run in reused JVMs whatever robolectricReuse says)" else "",
                    )
                }
                val reuse = when {
                    !reuseJvms.get() -> false.also { logger.lifecycle("krispr: reuseJvms is off; one JVM per mutant") }
                    robolectric && !sandboxReuse && plainCount == 0 ->
                        false.also { logger.lifecycle("krispr: robolectricReuse is $FRESH; one JVM per mutant") }
                    // Workers run the plain-only mutants alone, so the check runs their tests alone.
                    robolectric && !sandboxReuse ->
                        withJvmSlot { workers.check(covered.filter { plainOnly(it) }.flatMap { killers.getValue(it.id) }.toSortedSet().toList()) }
                    else -> withJvmSlot { workers.check(baselineSelectors) }
                }
                val baseline = baselineRun.get()
                // Tests that fail with no mutant active cannot confirm a kill, so they may not kill.
                val failing = baselineSelectors.filter { selector -> baseline.failures.any { overlaps(it.first, selector) } }.toSet()
                if (baseline.timedOut || baseline.exitCode !in 0..1 || failing.size == baselineSelectors.size) {
                    throw GradleException(
                        "krispr: the covering tests do not pass in the forked runner without mutants " +
                            "(exit ${baseline.exitCode}); see ${baseline.log}.",
                    )
                }
                if (failing.isNotEmpty()) {
                    val shown = names(failing).sorted()
                    logger.warn(
                        "krispr: ${failing.size} tests fail without mutants and may not kill them: " +
                            shown.take(5).joinToString(", ") + (if (shown.size > 5) " +${shown.size - 5} more" else "") + "; see ${baseline.log}",
                    )
                    for (mutant in covered) {
                        val selectors = killers.getValue(mutant.id)
                        killers[mutant.id] = selectors.filter { it !in failing }
                        if (killers.getValue(mutant.id).isEmpty()) {
                            outcomes[mutant.id] = Outcome(MutantStatus.UNKNOWN, names(selectors), reason = "its tests fail without the mutant")
                        }
                    }
                    covered = covered.filter { !outcomes.containsKey(it.id) }
                }
                val timeouts = Timeouts(
                    baseline.millis, baselineSelectors, { recorded[it]?.ownMillis }, baseline.timing,
                    if (reuse) workers.checkTimings else emptyList(), timeoutFactor.get(), timeoutConstantMillis.get(), timeoutMinimum,
                )
                timeoutOf = timeouts::forFork
                logger.lifecycle(
                    "krispr: ${mutants.size} mutants, ${covered.size} covered; baseline ${baseline.millis} ms, " +
                        "timeout $timeoutMinimum to ${timeouts.max} ms, $threadCount threads, at most $cap JVMs at once build-wide" +
                        if (reuse) ", reused JVMs (${workers.unsafe.size} tests need fresh ones)" else "",
                )
                logger.info(
                    "krispr: start-up ${timeouts.forkStartup} ms in a fresh JVM" +
                        if (reuse) ", ${timeouts.coldWorkerStartup} ms in a new worker, ${timeouts.warmWorkerStartup} ms in a warm one" else "",
                )

                /**
                 * A run's verdict. A kill needs a failing test that activated the mutant and passed in the
                 * baseline; a survivor that never activated the mutant is UNKNOWN, and [NOT_ACTIVATED] tells
                 * the caller to rerun it.
                 */
                fun judge(selectors: List<String>, result: ForkRunner.Result, runner: String): Outcome {
                    fun outcome(
                        status: MutantStatus, killedBy: String? = null, reason: String? = null, killer: String? = null,
                        allKillers: List<String> = emptyList(), testedTests: List<String> = emptyList(),
                    ) = Outcome(status, names(selectors), killedBy, result.millis, runner, reason, killer, allKillers, testedTests)
                    return when {
                        result.timedOut -> outcome(MutantStatus.TIMED_OUT)
                        // Only forks report it: a worker's heap may be full of earlier mutants' leaks.
                        result.memoryError && result.activated -> outcome(MutantStatus.MEMORY_ERROR, "OutOfMemoryError")
                        result.exitCode == 0 && result.activated -> outcome(MutantStatus.SURVIVED, testedTests = names(selectors))
                        result.exitCode == 0 -> outcome(MutantStatus.UNKNOWN, reason = NOT_ACTIVATED)
                        result.exitCode == 1 -> {
                            // Every failure that passed in the baseline confirms a kill; without the kill matrix,
                            // failFast stops the run at the first one, so there is usually only one to find here.
                            val confirmedFailures = result.failures.filter { (id, _) ->
                                baselineSelectors.any { overlaps(id, it) } && baseline.failures.none { overlaps(it.first, id) }
                            }
                            val confirmed = confirmedFailures.firstOrNull()
                            when {
                                !result.activated -> outcome(MutantStatus.UNKNOWN, result.firstFailure, "a test failed without activating the mutant")
                                confirmed == null -> outcome(MutantStatus.UNKNOWN, result.firstFailure, "the failing test has no passing run without the mutant")
                                else -> {
                                    val killerIndex = selectors.indexOfFirst { overlaps(confirmed.first, it) }
                                    val allKillerSelectors = confirmedFailures.mapNotNull { (id, _) -> selectors.firstOrNull { overlaps(id, it) } }.distinct()
                                    // Without the kill matrix, only the failFast batch containing the killer ran;
                                    // the rest, in later batches, never got a turn against this mutant. A whole
                                    // batch runs before failures are checked, so the killer need not be its last test.
                                    val evidence = if (killMatrixEnabled || killerIndex < 0) selectors else selectors.subList(0, batchEnd(killerIndex, selectors.size))
                                    outcome(
                                        MutantStatus.KILLED, confirmed.second, killer = selectors.getOrNull(killerIndex),
                                        allKillers = names(allKillerSelectors), testedTests = names(evidence),
                                    )
                                }
                            }
                        }
                        else -> outcome(MutantStatus.RUN_ERROR, "exit ${result.exitCode}")
                    }
                }

                // Most expensive first, so the long ones do not trail at the end with the other threads idle.
                val cost = covered.associate { mutant -> mutant.id to killers.getValue(mutant.id).sumOf { recorded[it]?.millis ?: 0L } }
                val queue = ConcurrentLinkedQueue(covered.sortedByDescending { cost.getValue(it.id) })
                /** One mutant's verdict: its runs, one JVM at a time, in [lease]'s worker or fresh forks. */
                fun runMutant(mutant: Mutant, lease: WorkerPool.Lease): Outcome {
                    val selectors = killers.getValue(mutant.id)
                    val sandboxed = routing.sandboxed(selectors)
                    // Only a kill counts in a worker that ran just some of the tests; see [Routing.workerTests].
                    val partial = selectors.any { it in workers.unsafe }
                    val workerSelectors = routing.workerTests(selectors, mutant.initializer, reuse, sandboxReuse, workers.unsafe, killMatrixEnabled)
                    var forkTimeout = 0L
                    fun fork(name: String, timeout: Long = timeouts.forFork(selectors)): Outcome {
                        forkTimeout = timeout
                        return judge(selectors, runner.run(name, mutant.id, selectors, timeout, failFast = !killMatrixEnabled), "fork")
                    }
                    val inWorker = workerSelectors?.let {
                        workers.run(
                            lease, mutant.id, it, { tests, warm -> timeouts.forWorker(tests, warm) }, failFast = !killMatrixEnabled,
                            keepAfterKill = keepAfterKill,
                        )
                    }
                    var outcome = inWorker?.let { result ->
                        val judged = judge(workerSelectors, result, "worker")
                        when {
                            !partial -> judged
                            judged.status == MutantStatus.KILLED -> Outcome(
                                judged.status, names(selectors), judged.killedBy, judged.millis, judged.runner, judged.reason,
                                judged.killer, judged.allKillers, judged.testedTests,
                            ).also { partialKills.incrementAndGet() }
                            else -> null
                        }
                    } ?: fork("mutant-${mutant.id}")
                    if (!sandboxed && robolectric && (!sandboxReuse || mutant.initializer) && (inWorker?.timing?.frameworkMillis ?: 0) > 0) {
                        // A test recorded as plain ran under Robolectric after all; its sandbox is not to be trusted.
                        lease.close()
                        outcome = fork("mutant-${mutant.id}-sandboxed")
                    }
                    if (outcome.status == MutantStatus.TIMED_OUT && outcome.runner == "worker") {
                        // A reused JVM may be what was slow (#34); a fresh one with twice the time decides.
                        outcome = fork("mutant-${mutant.id}-timeout", timeouts.forRetry(selectors))
                    }
                    if (outcome.runner == "worker" && survivorChecks.needed(outcome.status, inWorker?.timing?.frameworkMillis)) {
                        // State an earlier run left in the sandbox may hide this mutant; a fresh JVM's verdict wins.
                        outcome = fork("mutant-${mutant.id}-survived")
                        if (survivorChecks.record(outcome.status)) {
                            logger.lifecycle(
                                "krispr: mutant ${mutant.id} (${relative(mutant.file)}:${mutant.line}) survived in a reused " +
                                    "Robolectric sandbox, then ${outcome.status} in a fresh JVM" + (outcome.reason?.let { " ($it)" } ?: ""),
                            )
                        }
                    }
                    if (outcome.reason == NOT_ACTIVATED) {
                        // Coverage is flaky; one more try in a fresh JVM, and UNKNOWN if the mutant is still not reached.
                        outcome = fork("mutant-${mutant.id}-rerun").let {
                            if (it.reason == NOT_ACTIVATED) Outcome(it.status, it.tests, null, it.millis, it.runner, "its tests did not activate the mutant, twice") else it
                        }
                    }
                    if (outcome.status == MutantStatus.TIMED_OUT) {
                        // The host may be what got slow (#42): the same tests without the mutant, now, decide.
                        // See [Timeouts.afterTimeout].
                        val timeout = forkTimeout.takeIf { it > 0 } ?: timeouts.forFork(selectors)
                        val control = runner.run("mutant-${mutant.id}-control", null, selectors, timeout, failFast = !killMatrixEnabled)
                        val load = "load average ${"%.1f".format(ManagementFactory.getOperatingSystemMXBean().systemLoadAverage)}"
                        when (val check = timeouts.afterTimeout(timeout, control)) {
                            TimeoutCheck.Kill -> Unit
                            is TimeoutCheck.Retry -> {
                                slowRetried.incrementAndGet()
                                logger.info("krispr: mutant ${mutant.id} timed out after $timeout ms; its tests now need ${check.millis} ms ($load), so it runs again")
                                outcome = fork("mutant-${mutant.id}-slow", check.millis)
                            }
                            is TimeoutCheck.Unknown -> {
                                slowUnknown.incrementAndGet()
                                logger.lifecycle("krispr: mutant ${mutant.id} (${relative(mutant.file)}:${mutant.line}) timed out, but ${check.reason} ($load); UNKNOWN")
                                outcome = Outcome(MutantStatus.UNKNOWN, outcome.tests, null, outcome.millis, outcome.runner, check.reason)
                            }
                        }
                    }
                    if (confirm && outcome.status == MutantStatus.KILLED) {
                        val again = fork("mutant-${mutant.id}-confirm")
                        if (again.status != MutantStatus.KILLED && again.status != MutantStatus.TIMED_OUT) {
                            disagreements.incrementAndGet()
                            outcome = Outcome(
                                MutantStatus.UNKNOWN, outcome.tests, outcome.killedBy, outcome.millis, outcome.runner,
                                "killed once, then ${again.status} in a fresh JVM" + (again.reason?.let { " ($it)" } ?: ""),
                            )
                        }
                    }
                    return outcome
                }

                // Each thread takes one build-wide JVM slot for its mutants: its reused JVM and any fresh one it
                // forks run one at a time. It keeps the slot, and its warm worker, while no other task waits for
                // one; once one does, it gives the slot back after [quantum], retiring the worker first so no
                // more JVMs live than slots are held, and queues again behind the other modules (#41). A task
                // holding several slots passes one on at once to a waiting task that holds none.
                val quantum = if (reuse) maxOf(MIN_SLOT_QUANTUM_MILLIS, SLOT_QUANTUM_STARTUPS * timeouts.coldWorkerStartup) else 0L
                val handedBack = AtomicInteger()
                val mutantsQueued = LocalTime.now().withNano(0)
                val firstSlot = AtomicReference<LocalTime>()
                List(threadCount) {
                    pool.submit {
                        val lease = workers.lease()
                        var slot: JvmSlots.Slot? = null
                        try {
                            while (true) {
                                val mutant = queue.poll() ?: break
                                slot?.takeIf { (it.heldMillis >= quantum && it.contended()) || it.passToOwnerWithNone() }?.let {
                                    lease.close()
                                    it.close()
                                    slot = null
                                    handedBack.incrementAndGet()
                                }
                                if (slot == null) slot = acquireJvmSlot().also { firstSlot.compareAndSet(null, LocalTime.now().withNano(0)) }
                                outcomes[mutant.id] = runMutant(mutant, lease)
                            }
                        } finally {
                            lease.close()
                            slot?.close()
                        }
                    }
                }.forEach { it.get() }
                // Shows whether modules' mutants interleaved in a whole-build run.
                logger.info(
                    "krispr: mutants queued at $mutantsQueued, ran from ${firstSlot.get() ?: mutantsQueued} to ${LocalTime.now().withNano(0)}; " +
                        "JVM slots given back to other tasks ${handedBack.get()} times (after $quantum ms or more each)",
                )
                if (reuse) {
                    logger.lifecycle(
                        "krispr: ${outcomes.values.count { it.runner == "worker" }} mutants in reused JVMs " +
                            "(${workers.started.get()} started), ${outcomes.values.count { it.runner == "fork" }} in fresh ones",
                    )
                    if (robolectric && sandboxReuse) {
                        logger.lifecycle(
                            "krispr: after a Robolectric test failed, ${workers.keptAfterKill.get()} workers kept (health check passed), " +
                                "${workers.retiredAfterKill.get()} retired",
                        )
                    }
                    if (robolectric && sandboxReuse && confirmSurvivors.get()) logger.lifecycle(survivorChecks.summary())
                    if (workers.unsafe.isNotEmpty()) {
                        val reaching = covered.count { m -> killers.getValue(m.id).any { it in workers.unsafe } }
                        logger.lifecycle(
                            "krispr: $reaching mutants reach tests that fail in a reused JVM; ${partialKills.get()} of them were " +
                                "killed in one by their other tests, the rest ran in fresh JVMs",
                        )
                    }
                }
                if (slowRetried.get() + slowUnknown.get() > 0) {
                    logger.lifecycle(
                        "krispr: ${slowRetried.get() + slowUnknown.get()} timeouts came on a host slower than when the timeouts were set: " +
                            "${slowRetried.get()} ran again with a timeout from their tests' time then, ${slowUnknown.get()} are UNKNOWN",
                    )
                }
                if (confirm) {
                    val kills = outcomes.values.count { it.status == MutantStatus.KILLED } + disagreements.get()
                    logger.lifecycle("krispr: rechecked $kills kills in fresh JVMs; ${disagreements.get()} were not killed again")
                }
            }
            if (showChanges.get() && !extreme) probeSurvivors(mutants, outcomes, killers, runner, logs, timeoutOf, pool, changes)
        } finally {
            pool.shutdownNow()
            workers.close()
        }
        runner.timings.summary()?.let { logger.lifecycle(it) }
        logger.info("krispr: at most ${jvmSlots.get().peak} krispr JVMs ran at once in this build so far")

        if (useHistory.get()) {
            val kept = mutants.mapNotNull { m ->
                val outcome = outcomes[m.id]?.takeIf { it.status in History.KEPT && m.hash.isNotEmpty() } ?: return@mapNotNull null
                val tests = candidates.getValue(m.id).associateWith(::classHash)
                m.id to History.Entry(m.hash, outcome.status, outcome.killer, tests, outcome.tests, outcome.killedBy, outcome.millis)
            }.toMap()
            // Verdicts of mutants this run skipped (outside the diff) stay; those of mutants that are gone do not.
            val ran = mutants.mapTo(HashSet()) { it.id }
            val present = all.mapTo(HashSet()) { it.id }
            val carried = history?.entries.orEmpty().filterKeys { it !in ran && it in present }
            History(settings, carried + kept).write(historyFile)
        }

        val wallMillis = (System.nanoTime() - started) / 1_000_000
        writeReport(mutants, outcomes, wallMillis, testTimes, diffRef, changes)
        printSummary(mutants, outcomes, wallMillis, testTimes, changes)
        if (diffRef != null && diffFailOnSurvivors.get()) {
            val survived = outcomes.values.count { it.status == MutantStatus.SURVIVED }
            if (survived > 0) {
                throw GradleException(
                    "krispr: $survived mutant(s) survived on lines changed since $diffRef " +
                        "(krispr.diffFailOnSurvivors=true); see ${report.get().asFile}.",
                )
            }
        }
    }

    /**
     * showChanges: each survivor's tests run twice more in fresh forks, with the mutant off and then on,
     * while the runtime probe records the values at its site ([ShowChanges.compare] reads them). A
     * survivor of an operator with no probe is reported as not captured without a run.
     */
    private fun probeSurvivors(
        mutants: List<Mutant>, outcomes: Map<Int, Outcome>, killers: Map<Int, List<String>>, runner: ForkRunner, logs: File,
        timeoutOf: (List<String>) -> Long, pool: java.util.concurrent.ExecutorService, changes: MutableMap<Int, Change>,
    ) {
        val survivors = mutants.filter { outcomes[it.id]?.status == MutantStatus.SURVIVED }
        if (survivors.isEmpty()) return
        val started = System.nanoTime()
        val probed = AtomicInteger()
        survivors.map { mutant ->
            pool.submit { withJvmSlot {
                val selectors = killers[mutant.id].orEmpty()
                changes[mutant.id] = if (mutant.operator in ShowChanges.UNPROBED || selectors.isEmpty()) {
                    ShowChanges.notCaptured()
                } else {
                    probed.incrementAndGet()
                    fun probe(side: String, active: Int?): ProbeValues? {
                        val values = File(logs, "probe-${mutant.id}-$side.values").apply { delete() }
                        val args = listOf("-D${ShowChanges.PROBE_PROPERTY}=${mutant.id}", "-D${ShowChanges.OUT_PROPERTY}=${values.absolutePath}")
                        val result = runner.run("probe-${mutant.id}-$side", active, selectors, timeoutOf(selectors), extraJvmArgs = args)
                        // A timeout or a crashed JVM (exit other than 0 or 1) leaves no trustworthy values file.
                        return if (result.timedOut || result.exitCode !in 0..1) null else ProbeValues.read(values)
                    }
                    ShowChanges.compare(probe("original", null), probe("mutant", mutant.id))
                }
            } }
        }.forEach { it.get() }
        val millis = (System.nanoTime() - started) / 1_000_000
        logger.lifecycle("krispr: showChanges probed ${probed.get()} of ${survivors.size} survivors in $millis ms")
    }

    /**
     * A test's place in a mutant's run order: its recorded time, less PIT's direct-hit bonus
     * (TestInfoPriorisationComparator) when the test is named for the mutated class or file, which makes
     * it the likeliest to kill. Tests without a recorded time go last.
     */
    private fun priority(test: RecordedTest?, mutant: Mutant): Long {
        test ?: return Long.MAX_VALUE
        return test.millis - if (directHit(test.className, mutant.file, mutant.declaration)) DIRECT_HIT_BONUS_MILLIS else 0
    }

    /**
     * Long-lived worker JVMs, each leased to one thread for as long as that thread holds its build-wide JVM
     * slot, and retired after [MUTANTS_PER_WORKER] mutants (class loaders that a library keeps alive add up),
     * when a worker asks to be, or when the thread gives its slot back.
     */
    private inner class WorkerPool(private val runner: ForkRunner, private val isolated: List<File>) : AutoCloseable {
        private val all = ConcurrentLinkedQueue<ForkRunner.Worker>()
        val started = AtomicInteger()

        /** The timings of the check's two rounds: a new worker's start-up, then a warm one's. */
        var checkTimings: List<ForkRunner.Timing?> = emptyList()
            private set

        /** Tests that failed in a reused JVM with no mutant active; mutants they reach run in forks. */
        var unsafe: Set<String> = emptySet()
            private set

        /**
         * Runs [selectors] twice in one worker with no mutant active: the first run finds tests that break
         * when the project's classes are loaded apart from the system class loader, the second tests that
         * break when a JVM is reused. False when the worker itself does not work. A worker that asks to
         * be retired after the first run (a thread the tests started is still busy) is replaced for the
         * second, as it would be between two mutants. The worker is closed afterwards rather than kept
         * idle for a mutant thread: it would be a live JVM without a slot until that thread got one (#41).
         */
        fun check(selectors: List<String>): Boolean {
            fun notStarted() = false.also { logger.warn("krispr: the worker JVM did not start; one JVM per mutant") }
            var worker = start() ?: return notStarted()
            val failed = mutableSetOf<String>()
            // Each rejected test's first failure: its round, display name and message.
            val causes = mutableListOf<String>()
            val timings = mutableListOf<ForkRunner.Timing?>()
            for (round in 1..2) {
                if (!worker.usable) worker = start() ?: return notStarted()
                val result = worker.run("check-$round", null, selectors, 10 * 60_000L, failFast = false)
                if (result == null || result.timedOut || result.exitCode == 2) {
                    worker.kill()
                    logger.warn("krispr: the tests did not run in a reused JVM (see ${File(logsDirectory.get().asFile, "check-$round.log")}); one JVM per mutant")
                    return false
                }
                result.failures.forEachIndexed { i, (id, name) ->
                    if (failed.add(id)) causes += "$round\t$name\t${result.failureMessages.getOrNull(i).orEmpty()}"
                }
                timings += result.timing
            }
            checkTimings = timings
            unsafe = selectors.filter { selector -> failed.any { overlaps(it, selector) } }.toSet()
            if (unsafe.isNotEmpty()) {
                // Round 1 differs from a fresh JVM only in loading the project's classes apart; round 2 runs them again.
                val file = File(logsDirectory.get().asFile, "check-failures.tsv").apply { writeText("round\ttest\tfirst line of the failure\n" + causes.joinToString("\n") + "\n") }
                val firstRound = causes.count { it.startsWith("1\t") }
                logger.lifecycle(
                    "krispr: ${unsafe.size} tests fail in a reused JVM ($firstRound failures on the first run, ${causes.size - firstRound} " +
                        "only when run again); mutants they reach get fresh JVMs unless their other tests kill them in a worker. " +
                        "Causes: $file",
                )
                causes.take(5).forEach { cause ->
                    val (round, name, message) = cause.split('\t', limit = 3)
                    logger.lifecycle("  $name (run $round): ${message.take(200)}")
                }
            }
            worker.close()
            return true
        }

        /** One thread's worker, kept from mutant to mutant while the thread holds its slot, and closed with the lease. */
        inner class Lease : AutoCloseable {
            var worker: ForkRunner.Worker? = null

            override fun close() {
                worker?.close()
                worker = null
            }
        }

        fun lease() = Lease()

        /** Workers kept after a Robolectric test failed in them, because the health check passed, and retired. */
        val keptAfterKill = AtomicInteger()
        val retiredAfterKill = AtomicInteger()

        /**
         * [timeout] of a run of the given tests, given whether the worker already set up its test framework.
         * When a Robolectric test fails, the worker is retired unless [keepAfterKill]: then the failed tests
         * run again here with no mutant active (the health check), and the worker is kept only if they pass.
         */
        fun run(
            lease: Lease, mutant: Int, selectors: List<String>, timeout: (List<String>, Boolean) -> Long, failFast: Boolean,
            keepAfterKill: Boolean,
        ): ForkRunner.Result? {
            val worker = lease.worker?.takeIf { it.usable && it.mutantsRun < MUTANTS_PER_WORKER } ?: run {
                lease.close()
                start()?.also { lease.worker = it }
            } ?: return null
            val result = worker.run(
                "mutant-$mutant", mutant, selectors, timeout(selectors, worker.frameworkWarm), failFast = failFast,
                keepAfterFrameworkFailure = true,
            )
            if (worker.needsHealthCheck) {
                if (keepAfterKill && healthy(worker, mutant, selectors, result, timeout)) {
                    keptAfterKill.incrementAndGet()
                } else {
                    worker.close()
                    retiredAfterKill.incrementAndGet()
                }
            }
            if (!worker.usable) lease.worker = null
            return result?.takeIf { it.timedOut || it.exitCode == 0 || it.exitCode == 1 }
        }

        /**
         * Whether the tests that failed against [mutant] pass again in [worker] with no mutant active: a
         * sandbox the failure left broken would fail them, and would be retired like any sandbox a test
         * failed in before the health check. A timeout, an error or a worker that asks to go retires it.
         */
        private fun healthy(
            worker: ForkRunner.Worker, mutant: Int, selectors: List<String>, result: ForkRunner.Result?,
            timeout: (List<String>, Boolean) -> Long,
        ): Boolean {
            val failed = result?.failures.orEmpty()
            val rerun = selectors.filter { selector -> failed.any { overlaps(it.first, selector) } }.ifEmpty { selectors }
            val health = worker.run("mutant-$mutant-health", null, rerun, timeout(rerun, true), failFast = false, record = false)
            return health != null && !health.timedOut && health.exitCode == 0 && worker.usable
        }

        private fun start(): ForkRunner.Worker? =
            runner.startWorker("worker-${started.incrementAndGet()}", isolated)?.also { all += it }

        override fun close() {
            all.forEach { it.close() }
        }
    }

    /** Whether one of two unique ids is the other or inside it: a failed test and the selector that ran it. */
    private fun overlaps(a: String, b: String) = a == b || a.startsWith("$b/") || b.startsWith("$a/")

    /** [selectors] with no other selector inside them. */
    private fun leaves(selectors: Set<String>): Set<String> {
        val parents = HashSet<String>()
        for (selector in selectors) {
            var i = selector.indexOf("/[", 1)
            while (i > 0) {
                selector.substring(0, i).takeIf { it in selectors }?.let(parents::add)
                i = selector.indexOf("/[", i + 1)
            }
        }
        return selectors - parents
    }

    /** [selectors] without those inside another one (a method whose class is also selected). */
    private fun outermost(selectors: Set<String>): Set<String> = selectors.filterTo(sortedSetOf()) { selector ->
        generateSequence(selector.lastIndexOf("/[")) { selector.lastIndexOf("/[", it - 1).takeIf { i -> i > 0 } }
            .takeWhile { it > 0 }
            .none { selector.substring(0, it) in selectors }
    }

    /**
     * The test classpath entries that are this build's own code (class directories, and jars built under
     * the root project), which a reused JVM loads afresh for each mutant. The krispr runtime is left out:
     * the worker switches mutants on its one shared copy.
     */
    private fun isolatedEntries(): List<File> {
        val root = buildRootDirectory.get().asFile.toPath()
        return testClasspath.files.filter { file ->
            file.exists() && (file.isDirectory || file.toPath().startsWith(root)) && !containsRuntime(file)
        }
    }

    private fun containsRuntime(entry: File): Boolean = when {
        entry.isDirectory -> File(entry, RUNTIME_MARKER).exists()
        entry.name.endsWith(".jar") -> ZipFile(entry).use { it.getEntry(RUNTIME_MARKER) != null }
        else -> false
    }

    /**
     * Robolectric caches its sandbox class loaders, and the project classes in them, across tests; see
     * [KrisprExtension.robolectricReuse].
     */
    private fun usesRobolectric(): Boolean =
        testClasspath.files.any { it.name.startsWith("robolectric-") || it.name.startsWith("shadows-framework-") }

    /** Drops the mutants a `.krispr-exclude` in the module or the build root matches. */
    private fun excludeByRules(mutants: List<Mutant>): List<Mutant> {
        val roots = listOf(projectDirectory.get().asFile, buildRootDirectory.get().asFile)
        val exclusions = MutantExclusions.read(roots)
        if (exclusions.isEmpty) return mutants
        val kept = mutants.filter { exclusions.excludes(MutantExclusions.Candidate(it.file, it.line, it.operator, it.declaration), roots) == null }
        logger.lifecycle("krispr: ${mutants.size - kept.size} of ${mutants.size} mutants excluded by ${MutantExclusions.FILE_NAME}")
        return kept
    }

    private fun readManifest(): List<Mutant> {
        val file = manifest.get().asFile
        if (!file.isFile) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(file) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        return (root["mutants"] as List<Map<String, Any?>>).map {
            Mutant(
                id = (it["id"] as Number).toInt(),
                file = it["file"] as String,
                line = (it["line"] as Number).toInt(),
                column = (it["column"] as Number).toInt(),
                operator = it["operator"] as String,
                description = it["description"] as String,
                declaration = it["declaration"] as String?,
                initializer = it["initializer"] == true,
                hash = it["hash"] as String? ?: "",
            )
        }
    }

    /** `id<TAB>selector<TAB>displayName` lines written by the runtime's Recorder. */
    private fun readCoverage(): Pair<Map<Int, Set<String>>, Map<String, String>> {
        val tests = mutableMapOf<Int, MutableSet<String>>()
        val names = mutableMapOf<String, String>()
        coverage.get().asFile.takeIf { it.isFile }?.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size < 3) return@forEachLine
            tests.getOrPut(parts[0].toInt()) { sortedSetOf() }.add(parts[1])
            names[parts[1]] = parts[2]
        }
        return tests to names
    }

    /**
     * `selector<TAB>class<TAB>method<TAB>millis<TAB>ownMillis` lines written by the runtime's RecordingListener.
     */
    private fun readRecordedTests(): Map<String, RecordedTest> {
        val tests = mutableMapOf<String, RecordedTest>()
        KrisprRecordTask.testsFile(coverage.get().asFile).takeIf { it.isFile }?.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size < 4) return@forEachLine
            val millis = parts[3].toLongOrNull() ?: return@forEachLine
            val framework = when (parts.getOrNull(5)) {
                "1" -> true
                "0" -> false
                else -> null
            }
            tests[parts[0]] = RecordedTest(parts[0], parts[1], parts[2], millis, parts.getOrNull(4)?.toLongOrNull() ?: millis, framework)
        }
        return tests
    }

    private fun relative(path: String): String {
        val base = projectDirectory.get().asFile.toPath()
        val file = File(path).toPath()
        return if (file.startsWith(base)) base.relativize(file).toString() else path
    }

    private fun writeReport(
        mutants: List<Mutant>, outcomes: Map<Int, Outcome>, wallMillis: Long, testTimes: Map<String, Long>, diffRef: String?,
        changes: Map<Int, Change> = emptyMap(),
    ) {
        val counts = MutantStatus.entries.associate { status -> status.name to outcomes.values.count { it.status == status } }
        val entries = mutants.map { m ->
            val outcome = outcomes.getValue(m.id)
            linkedMapOf(
                "id" to m.id, "file" to relative(m.file), "line" to m.line, "column" to m.column,
                "operator" to m.operator, "description" to m.description, "status" to outcome.status.name,
                "tests" to outcome.tests, "killedBy" to outcome.killedBy, "millis" to outcome.millis,
            ).apply {
                outcome.runner?.let { put("runner", it) }
                outcome.reason?.let { put("reason", it) }
                if (outcome.allKillers.isNotEmpty()) put("killers", outcome.allKillers)
                if (outcome.testedTests.isNotEmpty()) put("testedTests", outcome.testedTests)
                changes[m.id]?.let { put("change", it.toJson()) }
            }
        }
        val scores = Scores(outcomes.values.map { it.status })
        val reused = outcomes.values.count { it.runner == HISTORY }
        val root = if (mode.get() == EXTREME) {
            // No score: a function whose body can go unnoticed is a finding on its own, not a fraction.
            val pseudoTested = pseudoTested(mutants, outcomes).map { m ->
                linkedMapOf("file" to relative(m.file), "line" to m.line, "declaration" to m.declaration, "tests" to outcomes.getValue(m.id).tests)
            }
            linkedMapOf(
                "summary" to linkedMapOf(
                    "mode" to EXTREME, "functions" to mutants.size, "wallMillis" to wallMillis, "reused" to reused,
                    "tested" to scores.killed, "pseudoTested" to pseudoTested.size, "killMatrix" to killMatrix.get(),
                ) + counts,
                "pseudoTested" to pseudoTested,
                "mutants" to entries,
            )
        } else {
            linkedMapOf(
                "summary" to linkedMapOf(
                    "total" to mutants.size, "wallMillis" to wallMillis, "reused" to reused,
                    "killed" to scores.killed, "valid" to scores.valid, "covered" to scores.covered,
                    "mutationScore" to scores.ofValid, "coveredScore" to scores.ofCovered, "killMatrix" to killMatrix.get(),
                    "diffBase" to diffRef,
                ) + counts,
                "mutants" to entries,
                "testTimes" to testTimes,
            )
        }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(root)) + "\n")
    }

    /** Extreme mode's finding: functions whose body went and no test failed. */
    private fun pseudoTested(mutants: List<Mutant>, outcomes: Map<Int, Outcome>) =
        mutants.filter { outcomes.getValue(it.id).status == MutantStatus.SURVIVED }
            .sortedWith(compareBy({ relative(it.file) }, { it.line }, { it.column }, { it.id }))

    private fun printSummary(
        mutants: List<Mutant>, outcomes: Map<Int, Outcome>, wallMillis: Long, testTimes: Map<String, Long>,
        changes: Map<Int, Change> = emptyMap(),
    ) {
        fun count(vararg statuses: MutantStatus) = outcomes.values.count { it.status in statuses }
        val scores = Scores(outcomes.values.map { it.status })
        if (mode.get() == EXTREME) {
            val pseudoTested = pseudoTested(mutants, outcomes)
            logger.lifecycle(
                "krispr: extreme mode, ${mutants.size} functions: ${scores.killed} tested, ${pseudoTested.size} pseudo-tested, " +
                    "${count(MutantStatus.NO_COVERAGE)} not covered, ${count(MutantStatus.UNKNOWN)} unknown, " +
                    "${count(MutantStatus.NOT_MEASURED)} not measured, ${count(MutantStatus.RUN_ERROR)} run errors; " +
                    "wall ${"%.1f".format(wallMillis / 1000.0)}s",
            )
            if (pseudoTested.isNotEmpty()) {
                logger.lifecycle("Pseudo-tested (covered, yet no test fails without the body):")
                for (m in pseudoTested) {
                    val covering = outcomes.getValue(m.id).tests
                    val tests = covering.take(3).joinToString(", ") + if (covering.size > 3) " +${covering.size - 3} more" else ""
                    logger.lifecycle("  ${relative(m.file)}:${m.line}  ${m.declaration}  [tests: $tests]")
                }
            }
            logger.lifecycle("Report: ${report.get().asFile}")
            return
        }
        val counts = MutantStatus.entries.associateWith { status -> outcomes.values.count { it.status == status } }
        val summary = ReportSummary(
            total = mutants.size, wallMillis = wallMillis, killed = scores.killed, valid = scores.valid,
            covered = scores.covered, mutationScore = scores.ofValid, coveredScore = scores.ofCovered, counts = counts,
            killMatrix = killMatrix.get(),
        )
        val mutantReports = mutants.map { m ->
            val outcome = outcomes.getValue(m.id)
            MutantReport(
                id = m.id, file = relative(m.file), line = m.line, column = m.column, operator = m.operator,
                description = m.description, status = outcome.status, tests = outcome.tests,
                killedBy = outcome.killedBy, millis = outcome.millis, runner = outcome.runner, reason = outcome.reason,
                killers = outcome.allKillers, testedTests = outcome.testedTests,
                change = changes[m.id],
            )
        }
        val cap = maxSurvivorsPerFile.get()
        val fullReport = Report(summary, mutantReports, cap, testTimes)
        val color = System.console() != null && System.getenv("NO_COLOR") == null
        logger.lifecycle(
            TerminalReportWriter.write(fullReport, projectDirectory.get().asFile, color, report.get().asFile).trimEnd('\n'),
        )
    }

    /**
     * The two scores of docs/PHILOSOPHY.md, as whole percentages rounded down, null when their denominator
     * is zero. Killed counts KILLED, TIMED_OUT and MEMORY_ERROR. Valid is every mutant but NOT_MEASURED, and covered is
     * valid without NO_COVERAGE, so SURVIVED, UNKNOWN and RUN_ERROR count against both scores.
     */
    internal class Scores(statuses: Collection<MutantStatus>) {
        val killed = statuses.count { it == MutantStatus.KILLED || it == MutantStatus.TIMED_OUT || it == MutantStatus.MEMORY_ERROR }
        val valid = statuses.count { it != MutantStatus.NOT_MEASURED }
        val covered = valid - statuses.count { it == MutantStatus.NO_COVERAGE }
        val ofValid: Int? = if (valid == 0) null else killed * 100 / valid
        val ofCovered: Int? = if (covered == 0) null else killed * 100 / covered
    }

    companion object {
        /** A probe run's timeout when no baseline ran (every survivor's verdict came from the history). */
        private const val PROBE_TIMEOUT_MILLIS = 10 * 60_000L

        /** The reason of a survivor whose tests never reached it; it gets one rerun. */
        private const val NOT_ACTIVATED = "its tests did not activate the mutant"

        /** The runtime's selector for a hit outside any test. */
        private const val ALL_TESTS = "*"

        /** [Outcome.runner] of a verdict reused from the history. */
        private const val HISTORY = "history"

        /** PIT's TestInfoPriorisationComparator weight for a test of the mutated class. */
        private const val DIRECT_HIT_BONUS_MILLIS = 1000L

        /**
         * Whether [testClass] is named for the code under mutation: its simple name less a `Test`, `Tests`
         * or `Spec` suffix or a `Test` prefix is the mutated file's name or a class of its [declaration]
         * (`CartTest` for `Cart.kt`, or for `Cart.total(...)` in any file).
         */
        internal fun directHit(testClass: String, file: String, declaration: String?): Boolean {
            val simple = testClass.substringBefore('$').substringAfterLast('.')
            val subject = listOf("Tests", "Test", "Spec").firstOrNull { simple.endsWith(it) }?.let { simple.removeSuffix(it) }
                ?: simple.takeIf { it.startsWith("Test") }?.removePrefix("Test")
            if (subject.isNullOrEmpty()) return false
            val classes = declaration?.substringBefore('(')?.split('.')?.dropLast(1).orEmpty()
            return subject == File(file).nameWithoutExtension || subject in classes
        }

        /** `[engine:junit-jupiter]/[class:com.example.CartTest]` → `com.example.CartTest`, for selectors without a recorded name. */
        internal fun readableSelector(selector: String): String =
            Regex("""\[(?:class|nested-class|runner|method|test|test-template|test-factory):([^\]]*)]""").findAll(selector)
                .joinToString(".") { it.groupValues[1] }.ifEmpty { selector }
        private const val RUNTIME_MARKER = "dev/krispr/runtime/Mutants.class"
        private const val SANDBOX = "sandbox"
        private const val FRESH = "fresh"
        private const val MUTANTS_PER_WORKER = 100

        /**
         * How long a thread keeps its JVM slot, and its warm worker, before it gives the slot to another
         * module that waits for one: this many times a new worker's measured framework start-up, so
         * restarting it costs about a tenth of the slot's time (50 s for a ~5 s Robolectric sandbox), and at
         * least [MIN_SLOT_QUANTUM_MILLIS]. Fresh forks keep nothing warm, so without reuse it is every mutant.
         */
        private const val SLOT_QUANTUM_STARTUPS = 10L
        private const val MIN_SLOT_QUANTUM_MILLIS = 5_000L

        /**
         * Half the cores, leaving room for Gradle, the JIT and GC threads of each JVM, and tests that use
         * more than one thread (on 10 cores, 7 JVMs were no faster than 5 on kotlinpoet),
         * and no more JVMs than fit in half the physical memory at the tests' `-Xmx` (512 MB when unset)
         * plus 256 MB of JVM overhead each.
         */
        fun defaultThreads(jvmArgs: List<String>): Int {
            val byCores = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            val physical = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize
                ?: return byCores
            val heap = jvmArgs.lastOrNull { it.startsWith("-Xmx") }?.let { bytes(it.removePrefix("-Xmx")) } ?: (512L shl 20)
            val byMemory = (physical / 2 / (heap + (256L shl 20))).toInt().coerceAtLeast(1)
            return minOf(byCores, byMemory)
        }

        private fun bytes(size: String): Long? {
            val unit = when (size.lastOrNull()?.lowercaseChar()) {
                'k' -> 1L shl 10
                'm' -> 1L shl 20
                'g' -> 1L shl 30
                't' -> 1L shl 40
                else -> return size.toLongOrNull()
            }
            return size.dropLast(1).toLongOrNull()?.times(unit)
        }
    }
}
