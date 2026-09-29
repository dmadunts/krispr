package dev.krispr.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class KrisprExtension {
    /**
     * Per-mutant timeout is `(its tests' recorded time + measured start-up) * timeoutFactor +
     * timeoutConstantMillis`, at most the whole baseline's. The defaults, 1.25 and 4000, are PIT's.
     */
    abstract val timeoutFactor: Property<Double>
    abstract val timeoutConstantMillis: Property<Long>

    /**
     * No mutant run times out sooner than this. Unset (the default): 10000 when the module's tests use
     * Robolectric, since a fresh JVM on a busy host can take several seconds to start it before the first
     * test, which a short test's timeout would not cover (#34); no floor for plain JVM tests. A reused
     * worker's timeout is also retried once in a fresh JVM with twice the time.
     */
    abstract val timeoutMinimumMillis: Property<Long>

    /**
     * The most krispr test JVMs (recording, baseline, each mutant's run) that run at once across the whole
     * build, however many modules Gradle runs in parallel. 0 (the default) is half the cores, capped by
     * memory at the tests' `-Xmx`. `-Pkrispr.maxConcurrentJvms=` overrides the build script. Each module
     * waits until fewer than its own cap run, so with different values the lowest holds for that module.
     */
    abstract val maxConcurrentJvms: Property<Int>

    /**
     * JVM flags for krispr's own test JVMs. `auto` (the default) runs forks and workers with C1 only
     * (`-XX:TieredStopAtLevel=1`): they are too short-lived for C2 to pay back its compile threads, and
     * on the Robolectric sample it cut workers' CPU time by 40%. `off` runs them with the JVM's tiered
     * default, for suites with long CPU-bound tests. The test task's own JVM arguments win either way.
     * `-Pkrispr.forkJvmTuning=` overrides the build script.
     */
    abstract val forkJvmTuning: Property<String>

    /**
     * An optional lower limit for this module alone: at most this many of its mutants run at once. 0 (the
     * default) is [maxConcurrentJvms].
     */
    abstract val threads: Property<Int>

    /**
     * Path of the project whose JVM tests exercise this one (for builds that keep tests in a separate
     * module, like `:test`). Unset: this project's own tests.
     */
    abstract val testProject: Property<String>

    /**
     * Android: the variant whose Kotlin compilation is mutated and whose local unit tests
     * (`test<Variant>UnitTest`) run against it. Default `debug`.
     */
    abstract val androidVariant: Property<String>

    /**
     * Kotlin Multiplatform: the target whose compilation carries the mutants (`commonMain` code plus the
     * target's own source sets). Default: the JVM target, or the Android target when there is none.
     */
    abstract val kotlinTarget: Property<String>

    /**
     * The mutation operators, by name (see docs/PHILOSOPHY.md "Operators"). Unset or empty: the default set. `DEFAULTS`
     * stands for the default set, so `listOf("DEFAULTS", "EMPTY_RETURNS")` adds one opt-in operator.
     * `-Pkrispr.operators=A,B` overrides the build script.
     */
    abstract val operators: ListProperty<String>

    /**
     * `default`, or `extreme`: one mutant per function that empties its whole body, reported as the list
     * of pseudo-tested functions (covered, yet no test fails without their body) with no score.
     * `-Pkrispr.mode=extreme` overrides the build script.
     */
    abstract val mode: Property<String>

    /**
     * Where verdicts are kept between runs, to reuse those whose code and tests did not change (default
     * `build/krispr/history.json`). Point it outside `build/` to keep it in a CI cache.
     */
    abstract val historyFile: RegularFileProperty

    /** Reuse verdicts from [historyFile]. Default true; `-Pkrispr.history=false` overrides the build script. */
    abstract val useHistory: Property<Boolean>

    // --- Arid code (W1): categories skipped by default, see docs/tuning.md "Arid code". Unset means false. ---

    /** Mutate `@Composable` functions and lambdas, and `@Preview` functions. */
    abstract val mutateComposables: Property<Boolean>

    /** Mutate logging calls and their arguments (android.util.Log, Timber, SLF4J, kotlin-logging, println...). */
    abstract val mutateLogging: Property<Boolean>

    /** Mutate Dagger/Hilt and Metro modules and providers, and Koin module definitions. */
    abstract val mutateDependencyInjection: Property<Boolean>

    /** Mutate `toString` overrides. */
    abstract val mutateToString: Property<Boolean>

    /** Mutate custom getters that only read a field, value, constant or another property. */
    abstract val mutateTrivialGetters: Property<Boolean>

    /** Mutate cache lookups guarding a computation and the keys passed to `getOrPut`/`computeIfAbsent`. */
    abstract val mutateCaches: Property<Boolean>

    /** Mutate `delay`, `Thread.sleep` and `withTimeout*` calls and their durations (never the block under a timeout). */
    abstract val mutateDelays: Property<Boolean>

    /** Mutate analytics and metrics calls and their arguments (`analytics.track*`, `metrics.*`, `counter.inc*`). */
    abstract val mutateMetrics: Property<Boolean>

    /** Mutate classes and functions annotated `@Generated`. */
    abstract val mutateGenerated: Property<Boolean>

    // Performance and test selection.

    /**
     * Run mutants in long-lived worker JVMs that reload the project's classes for each mutant, instead of
     * one fresh JVM per mutant. Default true; see [robolectricReuse] for Robolectric tests. Tests that
     * fail in a reused JVM without any mutant fall back to fresh JVMs by themselves.
     */
    abstract val reuseJvms: Property<Boolean>

    /**
     * How a module with Robolectric on its test classpath reuses JVMs, with [reuseJvms] on. `sandbox`
     * (default): each worker JVM keeps its Robolectric sandbox, whose set-up is most of a fresh JVM's
     * time, and switches the active mutant inside it. The sandbox loads the project's classes once for
     * every mutant its worker runs, so a companion, `object`, `lazy` or DI-singleton value computed
     * earlier (with no mutant active, or another one) is what a later mutant's tests see. What is retired:
     * the worker after a mutant a Robolectric test killed, unless [robolectricKeepAfterKill] finds it
     * healthy (and after a timeout, a stray thread, 100 mutants or a nearly full heap); mutants in class
     * initializers never run in a sandbox. A survivor's
     * state is not retired, so with [confirmSurvivors] (the default) every mutant that survived in a
     * sandbox runs again in a fresh JVM, whose verdict is the one reported. `fresh`: every mutant a
     * Robolectric test reaches gets a fresh JVM and sandbox, as before. Either way, a mutant that only
     * tests recorded without Robolectric reach runs in a reused JVM like in a plain module, initializer
     * mutants included. `-Pkrispr.robolectricReuse=` overrides it.
     */
    abstract val robolectricReuse: Property<String>

    /**
     * With [robolectricReuse] `sandbox`, rerun every mutant that survived in a reused sandbox once more in
     * a fresh JVM and report that run's verdict, since state an earlier run left in the sandbox can hide
     * what the mutant changes. Runs no Robolectric test reached are not rerun: plain tests load the
     * project's classes afresh for each mutant. Default true; `-Pkrispr.confirmSurvivors=` overrides it.
     */
    abstract val confirmSurvivors: Property<Boolean>

    /**
     * With [robolectricReuse] `sandbox`, keep a worker after a Robolectric test failed in it (a kill) if
     * the failed tests pass when rerun in it with no mutant active; otherwise retire it, as with this
     * off. The rerun costs the failed tests' time, and saves the next Robolectric mutant building a new
     * sandbox. Default true; `-Pkrispr.robolectricKeepAfterKill=` overrides it.
     */
    abstract val robolectricKeepAfterKill: Property<Boolean>

    /**
     * Let screenshot tests (Roborazzi, Paparazzi, Shot, Dropshots and similar) kill mutants. Default
     * false: they fail on any pixel change, so they would kill nearly every mutant in UI code without
     * saying anything about behaviour. A mutant only they reach is reported as NOT_MEASURED.
     */
    abstract val useScreenshotTests: Property<Boolean>

    /**
     * Tests that never run against mutants, in Gradle's `--tests` syntax (`*` wildcards over
     * `pkg.Class` or `pkg.Class.method`; a leading uppercase letter matches the simple class name). A mutant
     * only they reach is reported as NOT_MEASURED.
     */
    abstract val excludeTests: ListProperty<String>

    /**
     * Known-flaky tests, in the same syntax as [excludeTests]: they never count as killing a mutant, and a
     * mutant only they reach is reported as NOT_MEASURED.
     */
    abstract val quarantinedTests: ListProperty<String>

    /**
     * Rerun every killed mutant once more in a fresh JVM, and report it as UNKNOWN when the second run does
     * not kill it too. Default false; `-Pkrispr.confirmKills=true` overrides the build script.
     */
    abstract val confirmKills: Property<Boolean>

    /**
     * Tests whose recording-run duration is over this many milliseconds (default 2000) may not kill
     * mutants unless [includeSlowTests] is set; a mutant only they reach is reported as NOT_MEASURED.
     */
    abstract val slowTestThresholdMs: Property<Long>

    /** Let slow tests kill mutants, within [slowTestBudgetMs] per mutant. Default false. */
    abstract val includeSlowTests: Property<Boolean>

    /**
     * With [includeSlowTests], the most recorded time a mutant spends on slow tests (default 10000). A
     * mutant's tests run fastest first, so the slow ones come last; those past the budget are left out.
     */
    abstract val slowTestBudgetMs: Property<Long>

    /**
     * Diff mode: mutate only the lines changed between the merge base of this ref and HEAD, and the
     * working tree (untracked files count whole). `-Pkrispr.diffBase=<ref>` overrides the build script;
     * `krisprRun --since <ref>` sets it for one run, task-only, without narrowing instrumentation (see
     * [targetFiles]).
     */
    abstract val diffBase: Property<String>

    /**
     * Fail `krisprRun` if a mutant on a line [diffBase] (or `--since`) selected survives; otherwise diff
     * mode always succeeds, since it is meant for review, not a merge gate. No effect without diff mode.
     * Default false; `-Pkrispr.diffFailOnSurvivors=true` overrides the build script.
     */
    abstract val diffFailOnSurvivors: Property<Boolean>

    /**
     * Only these source files get mutants (paths relative to the project directory, or absolute); every
     * other file compiles as it is. Unset or empty: all of them. Pairs with diff mode when only a few files
     * changed: the rest of the module is not instrumented, so compiling and recording it costs less.
     * `-Pkrispr.targetFiles=a.kt,b.kt` overrides the build script.
     */
    abstract val targetFiles: ListProperty<String>

    /**
     * Survivors printed per file, in line order (default 3; 0 prints all). The report keeps every one.
     */
    abstract val maxSurvivorsPerFile: Property<Int>

    /**
     * Run every covering test against each mutant instead of stopping at the first kill, so the report can
     * name every test that kills a mutant, not just the first. Costs extra time (the fast path stops as
     * soon as one test fails); default false. `-Pkrispr.killMatrix=true` overrides the build script.
     */
    abstract val killMatrix: Property<Boolean>

    /**
     * Rerun each survivor's tests with the mutant off and then on, recording the value at its site, and
     * report what changed ("original: 3 → mutant: 0"). Default false: the instrumented build then carries
     * no probes. `-Pkrispr.showChanges=true` overrides the build script.
     */
    abstract val showChanges: Property<Boolean>
}
