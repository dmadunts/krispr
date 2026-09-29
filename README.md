# Krispr

Prototype mutation testing for Kotlin/JVM, Android (local unit tests) and Kotlin Multiplatform (through
its JVM or Android target). It mutates at the **Kotlin IR level** with a K2 compiler
plugin and compiles every mutant into one binary behind runtime switches (**mutation schemata**).

Bytecode mutators such as PIT see the code the Kotlin compiler generates, so on Kotlin they create
many junk mutants: null-check intrinsics, data class `equals`/`hashCode`, coroutine state machines,
default-argument bridges. Krispr runs before lowering, where only user-written code exists. It
builds the main code once, not once per mutant.

This is a prototype. It is not a product. [docs/validation.md](docs/validation.md) has results on
kotlinpoet, kotlin-result, clikt and nowinandroid.

## Running it on the sample

```sh
./gradlew build                      # builds runtime, compiler plugin and Gradle plugin; runs the IR tests
cd sample && ../gradlew krisprRun   # records coverage, then runs the covering tests against each mutant
scripts/verify-clean-build.sh        # proves the sample jar, AAR, APK and KMP jar are byte-identical without the plugin
```

Two more samples, each a separate build with deliberately weak tests:

```sh
cd sample-android && ../gradlew :lib:krisprRun   # Android library: JUnit 4 + Robolectric, Compose, Parcelize, Moshi (KSP)
cd sample-android && ../gradlew :app:krisprRun   # Android app: Hilt ViewModel with StateFlow, coroutines-test
cd sample-kmp && ../gradlew krisprRun            # KMP: commonMain + jvmMain through jvmTest
cd sample-kmp && ../gradlew krisprRun -PkrisprTarget=android   # commonMain + androidMain through testAndroidHostTest
```

The Android samples need an SDK: set `ANDROID_HOME` or write `sdk.dir` to `local.properties`.

`sample/` is a separate build. It pulls in the root build with `includeBuild("..")`, so it uses the
local plugin, compiler plugin and runtime. The output is `sample/build/krispr/`:

- `mutants.json`: the manifest written by the compiler plugin (id, file, line, column, operator,
  description, enclosing declaration). The next normal compile removes it; the report keeps the same
  data.
- `coverage.tsv`: which tests reached which mutant, from the recording run
- `report.json`: the two scores and per-status counts under `summary`, then the status of each
  mutant, the tests that may kill it, and a `reason` for NOT_MEASURED and UNKNOWN
- `history.json`: the verdicts the next run may reuse (see [Incremental runs](#incremental-runs))
- `logs/` and `record/`: the output of each forked JVM
- `build/`: the instrumented build itself (see below)
- `html/index.html`, `pr-summary.md`, `krispr.sarif`, and (diff mode only) `diff.md` and
  `diff-annotations.json`: human-facing reports, written from `report.json` by `krisprReport` (see below)

The terminal summary prints a headline (counts by status and both scores), then the survivors grouped
by file, ordered by a heuristic that puts branch/condition operators first, state/return-value operators
second, and arithmetic/bitwise operators last, since the former are more often a real test gap. Each
survivor shows its source line, the mutant as an inline before/after, and a one-line plain-English
description, at most `maxSurvivorsPerFile` (default 3) per file with `+N more in the report` for the
rest. NO_COVERAGE mutants are summarised per file (a count), not listed line by line, since an uncovered
line usually means a whole untested function. Colour is used when stdout is a terminal, plain text
otherwise. The survivors in the sample come from the deliberately weak `TemperatureTest`; `Flaky.kt` and
`Badge.kt` show UNKNOWN and NOT_MEASURED.

### Reports

`krisprRun` is `finalizedBy` a `krisprReport` task, which turns `report.json` into three outputs
under `build/krispr/`:

- `html/index.html`: a single self-contained file (no external CSS, JS or fonts, works with `file://`)
  with a summary panel showing both scores, NOT_MEASURED and UNKNOWN as separate counts beside them,
  and an overview table (per file, worst score first) with a stacked bar per row. Below it, one source
  view per mutated file with a gutter marker on every mutated line, coloured by status; click a marker
  to expand the mutants on that line, each with its operator, an inline before/after, and a plain-English
  description. Checkboxes filter by status and operator, `j`/`k` step between visible findings, and a
  fallback list is shown when the source file cannot be found under the project directory. Light and dark
  themes follow the OS (`prefers-color-scheme`), and the layout stays readable down to phone width. Open
  it straight from disk; nothing needs a server.
- `pr-summary.md`: a GitHub-flavoured markdown PR comment: the headline covered score, then
  survivors grouped by file, capped at `maxSurvivorsPerFile` per file, each one worded as a test
  someone could write, e.g. "No test fails if `a < b` becomes `a <= b` at Foo.kt:42".
- `krispr.sarif`: SARIF 2.1.0, one `note`-level result per survivor, for GitHub code scanning line
  annotations.
- `diff.md` and `diff-annotations.json`, written only when the run was scoped by `diffBase`/`--since`
  (see [Diff mode](#diff-mode)): a PR-comment markdown summary capped at 20 survivors total, and the
  same survivors as GitHub check-run style line annotations (`path`, `start_line`, `end_line`,
  `annotation_level`, `message`) for a workflow to turn into inline comments. Krispr makes no GitHub
  API calls itself; see [docs/diff-mode.md](docs/diff-mode.md) for a sample workflow.

Both `krisprRun` and `krisprReport` run whether or not there are survivors, so a CI job can post
the summary and upload the SARIF unconditionally:

```yaml
- run: ./gradlew krisprRun
  continue-on-error: true   # krisprReport (finalizedBy) still runs and writes its outputs
- uses: marocchino/sticky-pull-request-comment@v2
  with:
    path: build/krispr/pr-summary.md
- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: build/krispr/krispr.sarif
```

## Statuses and scores

| Status | Meaning |
|---|---|
| KILLED | A test failed with the mutant active, and that test passes without it. |
| TIMED_OUT | The tests ran past the mutant's timeout in a fresh JVM, and still fit that timeout without the mutant, measured right after. Counted as killed. |
| MEMORY_ERROR | The tests ran out of memory with the mutant active, in a fresh JVM. Counted as killed, as in PIT. |
| SURVIVED | Every test that may kill it ran with the mutant active and passed. |
| NO_COVERAGE | No test reached the mutant in the recording run. |
| NOT_MEASURED | Only tests that may not kill reached it: screenshot tests, slow tests, `excludeTests`, `quarantinedTests`, or slow tests past `slowTestBudgetMs`. |
| UNKNOWN | The verdict is not trustworthy; `reason` says why (below). Never counted as killed. |
| RUN_ERROR | The test JVM failed in a way that is neither a pass nor a test failure. |

The headline score is **killed / covered**, where covered is every mutant except NOT_MEASURED and
NO_COVERAGE; the second is **killed / valid**, where valid is every mutant except NOT_MEASURED.
Killed includes TIMED_OUT and MEMORY_ERROR; UNKNOWN and RUN_ERROR stay in both denominators. The summary line reads
`krispr: 120 mutants: 98 killed (3 timed out), 12 survived, 4 unknown (1 timed out on a slow host), …;
score 91% of covered, 84% of valid, 2 not measured`: timeouts are shown on their own, not only inside killed.

A mutant is UNKNOWN when:

- **its tests fail without the mutant**: every test that reached it also fails in the baseline run.
  A test that fails in the baseline never counts as a kill.
- **its tests did not activate the mutant, twice**: they passed without ever taking the mutant's
  branch, and a rerun in a fresh JVM did not either. The recording run reached it, so coverage is
  flaky.
- **a test failed without activating the mutant**, or **the failing test has no passing run without
  the mutant** (the baseline did not isolate that test).
- **killed once, then … in a fresh JVM**: with `confirmKills`, a kill that a rerun in a fresh JVM does
  not repeat. On clikt and kotlinpoet, 0 of 1432 kills disagreed, so this is off by default.
- **host too slow**: the mutant timed out, and so did its tests in a fresh JVM without the mutant,
  run right after with the same timeout (or that run failed to start). The host, not the mutant, was
  slow; the summary line counts these as `N timed out on a slow host`.

## Incremental runs

`krisprRun` keeps its verdicts in `historyFile` and reuses them next time, following PIT 1.14.0's
incremental analysis. A verdict is keyed by the mutant's stable id and a hash of its enclosing
declaration's source text:

- the declaration changed: the mutant runs again, and the test that killed it last time runs first;
- KILLED is reused while the killing test still reaches the mutant and its test class (with its nested
  and synthetic classes) is byte-for-byte unchanged;
- SURVIVED, TIMED_OUT and MEMORY_ERROR are reused while the set of tests that reach the mutant and all
  their classes are unchanged (PIT reuses a timeout unconditionally; Krispr is stricter);
- UNKNOWN, NO_COVERAGE, NOT_MEASURED and RUN_ERROR are never reused.

A history written by another Krispr version or other timeout settings is ignored. The summary says
`reused N of M verdicts`; each reused mutant has `"runner": "history"` in the report. Put
`historyFile` outside `build/` to keep it in a CI cache. On the sample a second run reuses every
reusable verdict (43 of 49) with identical results. Tests that inline mutated code (a library's
`inline` functions) change when the operator set changes, so switching `operators` reruns their mutants.

## Extreme mode

`krisprRun -Pkrispr.mode=extreme` (or `krispr { mode = "extreme" }`) makes one mutant per function,
which replaces the whole body with a default return: nothing for Unit, `0`, `false`, `null`, `""` or
an empty collection. The output is a list of **pseudo-tested functions**: covered by tests, yet no test
fails when the body is gone. There is no score. It is a cheap first pass (extreme mutation, as in PIT's
Descartes engine): on clikt it tests 270 functions in 9 s and lists 9. Its history is kept apart
(`history-extreme.json`).

## Diff mode

`./gradlew krisprRun -Pkrispr.diffBase=origin/main` (or `krispr { diffBase = "origin/main" }`) mutates
only the lines added or changed between the merge base of that ref and the working tree, uncommitted
edits and untracked files included, renames followed. It is the intended use on pull requests: a short
list of survivors on the lines under review.

With no explicit `targetFiles`, `diffBase` also scopes instrumentation to the module's own changed Kotlin
files: everything else compiles unchanged and gets no mutants, so the recording run is proportional to
the diff, not the module. A diff that touches nothing in a given module instruments nothing there (a
clean "0 mutants" run), never the whole module. Set `targetFiles` explicitly to keep instrumenting a
fixed set of files regardless of what changed.

`krisprRun --since <ref>` is the older, task-only form: it still filters the report to lines changed
since `<ref>`, but leaves instrumentation and the recording run covering the whole module, since it does
not touch the build script or a `-P` property. Prefer `-Pkrispr.diffBase`/`diffBase` on CI, where the
smaller recording run is the point; `--since` is for a quick local look at survivors on your own changes
without editing anything.

`krispr.diffFailOnSurvivors=true` (or `krispr { diffFailOnSurvivors = true }`) fails `krisprRun` when a
mutant survives on a changed line; otherwise diff mode always succeeds, since it is meant for review
feedback, not a merge gate.

See [docs/diff-mode.md](docs/diff-mode.md) for the `diff.md`/`diff-annotations.json` outputs and a sample
GitHub Actions workflow that posts them on a pull request.

## Test value

The mutant-by-mutant report answers "is this line tested?". A **Test value** section, printed in the
terminal summary and in `html/index.html`, answers the inverse question per test: does it protect
anything? It is worded as evidence, never a verdict — "kills 0 of 4 it covers", not "useless test" —
since a test with no kills yet may still be documentation or a regression guard for something Krispr
does not mutate.

By default, a mutant's fast path stops at the first test that kills it, so only some questions can be
answered:

- **kills nothing of what it covers**: a test that ran against a mutant (as the confirmed killer, or a
  survivor's covering test) and never killed one.
- per-test kill counts, for tests that do kill something.

`krispr { killMatrix = true }` (or `-Pkrispr.killMatrix=true`, default off) runs every covering test
against each mutant instead of stopping at the first kill, so every killer is recorded, not just the
first. That costs the difference between a fast-path run and a full one; measure it on your own suite
before turning it on for CI. With `killMatrix`, the section adds:

- **redundant tests**: a test whose every kill is also killed by one specific other test, or by the
  rest of the suite with no single test covering all of them.
- **a greedy minimal killing set**: the smallest set of tests that kills everything the whole suite
  kills, with its size against the full kill-capable test count and, when every chosen test has a
  recorded time, a time comparison against running all of them.

Without `killMatrix`, the section says so and names the option instead of guessing.

## Showing what changed

`krisprRun -Pkrispr.showChanges=true` (or `krispr { showChanges = true }`) shows, for each survivor,
what the mutant did to the value at its site while its tests ran:

```
  src/main/kotlin/dev/krispr/sample/Temperature.kt:5  fun celsiusToFahrenheit(celsius: Double): Double = celsius * 9 / 5 + 32
      celsius * 9 / 5 → celsius * 9 * 5  MATH
      `celsius * 9 / 5` became `celsius * 9 * 5`; no test failed.
      what changed: original: 180.0 → mutant: 4500.0
```

- **How.** After the normal run, each survivor's covering tests run twice more in fresh JVMs, first
  with the mutant off and then with it on. A probe records the first 3 distinct values at the site:
  - the mutated expression's result;
  - for a condition, the branch taken (`true`/`false`);
  - for `REMOVE_ASSIGNMENT`, the field after the store, read directly and never through a getter.
    For a state holder's `value`, it records the value stored, or `(skipped)`.
- **Verdicts.**
  - `original: X → mutant: Y` means the value changed and no test looked at it. That is a missing
    assertion.
  - `same values observed — no test input makes the mutant differ (a missing case, or equivalent)` means the tests never produced an
    input for which the mutant differs. Examples: a boundary no test hits, or a clause that is always
    true in the tests.
  - `same types observed` means the values could only be recorded by type.
  - `not captured` covers two cases. Some sites have no value to record: removed calls, safe-call
    bodies, Flow emits and operators, `launch` bodies, swallowed catches, coroutine contexts, and
    extreme mode. A probe run can also time out.
- **Rendering.** Values are rendered without side effects, and each is cut at 80 characters.
  Rendered through `toString` (inside a try/catch):
  - nulls, primitives, strings, enums and data classes;
  - JDK and Kotlin collections and arrays of those.

  Any other object is shown as `<TypeName>`, and none of its code runs.
- **Report outputs.**
  - `report.json` gets a `change` object per survivor: `verdict`, `original`, `mutant`, `text`, and
    `firstDifference` when the same values came in another order.
  - The terminal and the HTML report show its `text`.
- **Cost.** Two extra runs of each survivor's tests. On the sample, 31 survivors took the run from
  6.0 s to 10.3 s.
- **When off (the default).** The probe is not compiled in at all: `report.json` and the
  instrumented classes are as before. Normal builds never carry Krispr code either way.

## Using it on another project

Apply `id("dev.krispr")` to one module and run `./gradlew :module:krisprRun` **in its own
invocation**. The module can be:

- **Kotlin/JVM** (`org.jetbrains.kotlin.jvm`): `main`, tested by `test`.
- **Android** (`com.android.library`, `com.android.application`, `com.android.dynamic-feature`;
  AGP 9 with its built-in Kotlin, or AGP 8.5.2+ with `kotlin-android`; see [AGP versions](#agp-versions)):
  one variant's Kotlin (default `debug`), tested by its
  local unit test task (`testDebugUnitTest`), Robolectric included. The forks use the classpath AGP
  builds for that task: `android.jar` or Robolectric's runtime, the R classes and the unit test
  resource config. Instrumented (on-device) tests are out of scope.
- **Kotlin Multiplatform** (`org.jetbrains.kotlin.multiplatform`): the JVM target's `main`
  compilation, which is `commonMain` + `jvmMain`, tested by `jvmTest`, which runs `commonTest` too.
  Without a JVM target, or with `kotlinTarget` naming it, it uses the Android target instead:
  from `com.android.kotlin.multiplatform.library`, `commonMain` + `androidMain` tested by
  `testAndroidHostTest`; from `com.android.library` with `androidTarget()` (AGP 8), a variant as above. Common code is covered only through that one JVM-hosted target. Native, JS
  and Wasm tests are out of scope.

Until the plugin is published, a composite build is the way to get it:

```kotlin
// settings.gradle.kts
pluginManagement { includeBuild("../krispr") }
includeBuild("../krispr") // substitutes dev.krispr:krispr-compiler-<variant> and krispr-runtime
```

Settings, all optional:

```kotlin
krispr {
    timeoutFactor = 1.25           // per-mutant timeout = (its tests' recorded time + start-up) * factor + constant;
    timeoutConstantMillis = 4000L  // 1.25 and 4000 are PIT's defaults
    timeoutMinimumMillis = 10000L  // no mutant run times out sooner; covers a fresh JVM's Robolectric start-up on a busy host
    operators = listOf("DEFAULTS", "SWAP_COLLECTION_CALL") // or -Pkrispr.operators=...; see Operators below
    mode = "extreme"               // or -Pkrispr.mode=extreme; see Extreme mode
    historyFile = file("ci-cache/krispr-history.json") // default build/krispr/history.json
    useHistory = false             // or -Pkrispr.history=false: run every mutant, keep no history
    maxConcurrentJvms = 4          // krispr JVMs running at once across the whole build (or -Pkrispr.maxConcurrentJvms=4);
                                   // default 0 is half the cores, capped by memory
    threads = 2                    // an optional lower limit for this module alone; default 0 is maxConcurrentJvms
    testProject = ":test"          // run another module's JVM tests, when tests live apart from the code
    androidVariant = "demoDebug"   // Android: the variant to mutate and test; default "debug"
    kotlinTarget = "android"       // KMP: which JVM or Android target; default the single JVM target
    mutateComposables = true       // arid code (see below) is skipped unless turned back on; all default false
    mutateLogging = true
    mutateDependencyInjection = true
    mutateToString = true
    mutateTrivialGetters = true
    mutateCaches = true
    mutateDelays = true
    mutateMetrics = true
    mutateGenerated = true
}
```

The project must be on Kotlin 2.1.20 up to 2.4.x (see [Kotlin versions](#kotlin-versions)). The test task can
use JUnit 4 (`useJUnit()`, `kotlin-test-junit`) or the JUnit Platform (Jupiter, Kotest, Vintage).
For JUnit 4, Krispr brings its own Platform launcher and Vintage engine.

## Kotlin Multiplatform

Krispr mutates Kotlin IR, which all backends share, so it mutates `commonMain` itself: the report
names `src/commonMain/kotlin/...` files and lines, not JVM classes. PIT and other bytecode tools only
see one JVM compilation's output.

- **How it runs.** The compiler plugin instruments one JVM-hosted target's `main` compilation
  (`commonMain` + `jvmMain`, or `commonMain` + `androidMain`), and `commonTest` runs against it through
  that target's test task (`jvmTest`, `testAndroidHostTest`). Choose with `kotlinTarget`; the default is
  the single `jvm()` target, else the Android target.
- **Other targets.** `js`, `wasmJs`, native and Apple targets in the same module still build normally
  and are never instrumented. A module with no JVM or Android target fails `krisprRun` with a message
  saying so; add `jvm()` to mutation-test its common code.
- **Try it.** `cd sample-kmp && ../gradlew krisprRun` (JVM, Android, JS and macOS targets; a `Flow`
  pipeline and deliberately weak tests), or `-PkrisprTarget=android`.
- **Native, JS and Wasm test runs** are not supported yet. A probe compiled the same mutants with the
  unchanged compiler plugin for JS, Wasm and macOS, got identical mutant ids on every backend, and ran
  `commonTest` once per mutant on each with the expected verdicts. What remains is a multiplatform
  runtime and a test runner per backend; see [docs/kmp.md](docs/kmp.md) for the results and an
  estimate.

## Kotlin versions

Krispr runs on Kotlin 2.1.20 through 2.4.x. The IR API a compiler plugin uses is not binary compatible
between Kotlin releases, not even between feature releases of one minor: a plugin compiled against
2.3.0 fails on 2.3.20 with `NoSuchMethodError`. So, like the Compose compiler and KSP, the compiler
plugin ships once per range of releases, each built against the oldest release it serves:

| Artifact | Kotlin |
|---|---|
| `krispr-compiler-k2120` | 2.1.20 – 2.1.x |
| `krispr-compiler-k220` | 2.2.x |
| `krispr-compiler-k230` | 2.3.0 – 2.3.1x |
| `krispr-compiler-k2320` | 2.3.20 – 2.3.x |
| `krispr-compiler-k240` | 2.4.x |

The Gradle plugin picks the artifact from the Kotlin compiler version the build actually resolves
(`kotlin-compiler-embeddable` on the `kotlinCompilerClasspath` or `compileClasspath` configuration,
falling back to the Kotlin Gradle plugin's own version when neither resolves one), and fails the
build with the supported range when it has none. The floor is 2.1.20 because Krispr reads and
writes calls through the unified parameter and argument API (`IrFunction.parameters`,
`IrCall.arguments`), which 2.1.0 and 2.1.10 do not have. Before 2.3.0 there is no
`-Xcompiler-plugin-order`, so on 2.1 and 2.2 Krispr cannot ask to run ahead of the Compose
compiler; it still leaves Compose's own code alone (see [Android and Compose](#android-and-compose)).

`krispr-compiler/src` holds the shared sources and tests, and each `krispr-compiler/k*` directory
holds a variant's build file and its `VersionCompat.kt`, the few APIs that differ (plugin id, symbol
lookup). The `krispr.compiler-variant` convention plugin in `build-logic` compiles each variant and
runs the shared compiler tests on the oldest and newest release of its range (`test` and
`testKotlin<version>`, both part of `check`). To try the JVM sample on another release, pass
`-Psample.kotlin=2.1.20` (for example) to its build.

Pass `-Pkrispr.fullMatrix=true` to also run the shared compiler tests against a middle patch release
of a range, where one exists between its tested endpoints (currently `k220` on 2.2.10 and `k240` on
2.4.10, as `testKotlin2_2_10`/`testKotlin2_4_10`). This is off by default because it roughly doubles
those variants' test time for a release the endpoints already cover most of; CI can opt in.

## Arid code

Some code has mutants that tests are not expected to kill, or that nobody would write a test to
kill. Their survivors are noise that hides the useful ones, and each costs a fork. Krispr skips
these by default. Each category can be turned back on in `krispr { }`:

- **`mutateComposables`**: `@Composable` functions and every lambda inside them, lambdas passed to
  `@Composable` function parameters (`setContent { }`), and `@Preview` functions, including
  multipreview annotations. UI is verified by screenshot and UI tests, which Krispr does not run
  per mutant, and a surviving `Modifier.padding(8.dp)` mutant says nothing about the logic. Move the
  logic into plain functions or state holders, where it gets mutants.
- **`mutateLogging`**: calls to android.util.Log, Timber, SLF4J, kotlin-logging, Log4j, JUL,
  Kermit, Napier, `println`/`print`, functions named `log`/`logXxx`, and methods of classes called
  `*Logger` or `Log`, together with their arguments. Tests rarely assert on log output.
- **`mutateDependencyInjection`**: Dagger and Hilt `@Module` classes, `@Provides`, `@Binds` and
  multibinding functions, the same for Metro, kotlin-inject and Anvil, and Koin's module DSL
  (`module { single { } }`). This is wiring, which the DI framework validates or which fails on
  first use.
- **`mutateToString`**: `toString` overrides, which are debug output.
- **`mutateTrivialGetters`**: custom getters that only read a field, parameter, constant, object or
  another property (`get() = _state.value` is skipped; `get() = items.size > 10` is not). Their
  mutants just replace the value with a default.
- **`mutateCaches`**: memoization. The key arguments of `getOrPut` and `computeIfAbsent` (the lambda
  that computes the value keeps its mutants), and `if` conditions that only look a key up in a cache:
  `cache[key] != null`, `key in cache`, `!cache.containsKey(key)`, `cache.getIfPresent(key)`, on a
  receiver named `*cache*` or `*memo*`, or typed `*Cache*`. A cache that misses recomputes the same
  value, so these mutants are equivalent unless a test counts computations.
- **`mutateDelays`**: `delay`, `Thread.sleep`, `TimeUnit.sleep`, `SystemClock.sleep` and every
  `withTimeout*` call, with their duration arguments (`delay(attempt * 100L)`). The block passed to
  `withTimeout` keeps its mutants; the value the call returns does not, since negating it repeats the
  block's own return-value mutant. A changed duration only makes a test slower or flakier.
- **`mutateMetrics`**: analytics and metrics calls with their arguments: `track*` calls on a receiver
  named or typed `*Analytics*`, any call on a receiver named or typed `*metrics`, and `inc*` calls on a
  receiver named or typed `*counter*` that is not a number (`counter++` on an `Int` keeps its mutant).

The rules go by names, so they are conservative: a lookup of a map called `prices` is ordinary code.

- **`mutateGenerated`**: classes and functions annotated `@Generated`, from any package
  (`javax.annotation.processing`, `jakarta.annotation`, a code generator's own).

`equals` and `hashCode` overrides are always skipped. `AridCodeTest` has a test per category.
Krispr also never makes a mutant that cannot change the result: `x + 0` and `x - 0` on whole
numbers, `x * 1`, `x / 1`, `x * -1` and `x / -1` (`0 - x` and `1 / x` keep theirs).

### Excluding mutants

A line ending in `// krispr:ignore` (optionally followed by a reason) gets no mutants; the other
mutants of the function keep their ids:

```kotlin
val backoff = attempt * 250L // krispr:ignore tuned by hand, not tested
```

For whole files, classes or operators, put a `.krispr-exclude` in the module or at the build root.
Each line is one rule of whitespace-separated `key=glob` terms that must all match; `#` starts a comment:

```
src/main/kotlin/com/example/legacy/**          # a bare term is a file glob
**/*Mapper.kt
class=com.example.generated.*                   # class, or package for top-level code
class=Cart function=toString                    # simple or fully qualified names
operator=INCREMENTS
file=Pricing.kt lines=40-60,72
```

File globs match the path relative to the module or the build root, or the file name when they have
no `/`; `**` crosses directories. In `class`, `function` and `operator` globs `*` matches anything.
The run prints how many mutants the rules excluded; they are left out of the report.

## Speed and test selection

`krisprRun` spends its time running tests, so it runs as few as it can, in as few JVMs as it can:

- **Reused JVMs.** Mutants run in long-lived worker JVMs, each mutant in a fresh classloader over the
  build's own classes (the instrumented code, the test classes, other modules), so static state does
  not leak between mutants while the JDK, JUnit and libraries stay loaded and JIT-compiled. System
  properties, the default locale and time zone are restored after each mutant. A worker is replaced
  after 100 mutants, after a timeout, when a thread a test started is still running, or when its heap
  is nearly full. Before using workers, Krispr runs the covering tests twice in one worker with no
  mutant; a test that fails there (it depends on state a fresh classloader does not reset) sends every
  mutant it covers to a fresh JVM, after the mutant's other tests had a chance to kill it in a worker.
  The log names the first few such tests with their failure, and `build/krispr/logs/check-failures.tsv`
  lists them all with the round they failed in (1: the project's classes loaded apart, 2: run again
  in the same JVM). A worker that dies or errors has its mutant rerun in a fresh JVM, so
  statuses never depend on how a worker failed.
- **Reused Robolectric sandboxes.** Robolectric caches its sandbox, with `android-all` and the
  project's classes loaded and instrumented in it, for the life of the JVM; building it is ~85% of a
  fresh JVM's time for a Robolectric mutant (about 5 s against 0.7 s of tests, see
  [docs/perf.md](docs/perf.md)). With `robolectricReuse = "sandbox"` (the default) a worker keeps its
  sandbox, and the sandbox's copy of the mutant switch follows the worker's. The sandbox loads the
  project's classes once for all the mutants its worker runs, so a companion, `object`, `lazy` or
  DI-singleton value computed earlier is what later mutants see. When a Robolectric test kills a
  mutant, its worker reruns the failed tests with no mutant active and is kept only if they pass
  (`robolectricKeepAfterKill`; the summary counts workers kept and retired); a worker that timed
  out, left a thread running, ran 100 mutants or filled its heap retires, and mutants in class
  initializers (which a sandbox runs once) get fresh JVMs. A
  survivor does not retire its worker, so with `confirmSurvivors` (the default) every mutant that
  survived a Robolectric test in a reused sandbox runs again in a fresh JVM, and that verdict is the
  one reported; the summary line `N survivors re-checked in fresh JVMs, M changed` counts how often
  the sandbox's verdict was wrong. `"fresh"` gives every mutant a Robolectric test reaches its own JVM
  and sandbox. In both modes a mutant that only plain tests reach (the recording notes which tests
  ran under Robolectric) runs in a reused JVM as in a plain module; the summary line `N mutants
  reached by Robolectric tests, M only by plain tests` shows the split.
- **One cap for the whole build.** Gradle runs several modules' `krisprRun` at once, so a per-module
  limit multiplied: 4 Gradle workers × 4 threads started 16 test JVMs on 10 cores. Every krispr JVM
  (recording, baseline, and each mutant's worker or fork) now takes a slot from one build-wide
  `maxConcurrentJvms` (default half the cores, capped by memory at the tests' `-Xmx`). A thread keeps
  its slot, and its warm worker, from mutant to mutant while no other module waits for one. When one
  does, it gives the slot back after a quantum (ten times a new worker's measured start-up, at least
  5 s; every mutant for fresh JVMs), retiring its worker first so no more JVMs are alive than the cap,
  and a freed slot goes to the waiting module that holds fewest, so modules take turns instead of
  queueing behind the largest. A module holding several slots passes one on straight away to a waiting
  module that holds none. Every module records before any module's mutants run, so `krisprRun`
  tasks waiting for slots never hold the Gradle workers that compiling and recording need. `threads`
  only lowers one module's share.
- **Stop at the first kill.** The tests that reached a mutant run cheapest first, by the durations the
  recording run measured, in batches of 1, 1, 2, 4, … tests, and stop at the first failing batch.
  A test whose class name matches the mutated file or class (`CartTest` for `Cart.kt`) gets a 1 s head
  start, as in PIT's direct-hit ordering, and the test that killed the mutant last time runs first.
- **Costliest mutants first.** Mutants go to the workers in descending order of their tests' recorded
  time, so a long mutant does not start last and hold up the end of the run. With both changes kotlinpoet
  went from 207 s to 136 s of mutant time and clikt from 22.9 s to 22.2 s.
- **Per-mutant timeouts** come from the recorded own time of that mutant's tests plus the measured
  start-up of the JVM that runs it, times `timeoutFactor` plus `timeoutConstantMillis` (1.25 and
  4000 ms, PIT's defaults), capped by the baseline's and never below `timeoutMinimumMillis` (10 s).
  Start-up is measured per runner: a fresh JVM's (JVM start and Robolectric sandbox, from the
  baseline fork), a new worker's and a warm worker's (from the reuse check's two rounds). A mutant that
  times out in a reused worker runs once more in a fresh JVM with twice a fresh JVM's timeout, and
  that verdict counts, so a slow worker never makes a TIMED_OUT kill.
- **A slow host never makes a TIMED_OUT kill either.** Timeouts are set from times measured early in
  the run, so when load rises later a mutant can time out with no help from the mutant (#42). Before a
  TIMED_OUT from a fresh JVM counts, the same tests run once more in a fresh JVM with no mutant and the
  same timeout, and the timeout is worked out again from their time, by the same formula. If they
  still fit, TIMED_OUT stands. If the host has slowed so they no longer fit, the mutant runs once more
  in a fresh JVM with the new timeout, and that verdict counts. If they time out too, the mutant is
  UNKNOWN ("host too slow"), not killed. See [docs/perf.md](docs/perf.md).
- **Out of memory.** A mutant that makes its tests throw `OutOfMemoryError` in a fresh JVM is
  MEMORY_ERROR, counted as killed, as in PIT. In a reused worker the heap may hold earlier mutants'
  leaks, so there it only recycles the worker and reruns the mutant in a fresh JVM.
- **Screenshot tests are left out.** Roborazzi, Paparazzi, Shot, Dropshots, Facebook's
  screenshot-tests-for-android, Compose Preview Screenshot Testing, Emerge snapshots and Testify
  tests fail on any pixel change, so they "kill" nearly every UI mutant without checking behaviour,
  and they are the slowest tests. Krispr finds them by reading the constant pools of the test classes
  (a class that calls a capture API, holds a rule, or names a runner or annotation from one of these
  libraries is a screenshot test; one that only calls a helper that does is not). A mutant reached
  only by left-out tests is NOT_MEASURED, with the reason in `report.json`.

- **Slow tests are left out.** A test whose recorded time is over `slowTestThresholdMs` (default
  2000) may not kill. With `includeSlowTests`, slow tests run after the fast ones, up to
  `slowTestBudgetMs` (default 10000) of recorded time per mutant; a mutant whose fast tests all pass
  and whose slow tests are over budget is NOT_MEASURED. A test is judged by its own time, from its
  start to its finish less Robolectric's sandbox set-up and reset, so the first test of a class is not
  charged for the framework's start-up.

```kotlin
krispr {
    maxConcurrentJvms = 0                     // build-wide; 0: half the cores, capped by memory at the tests' -Xmx
    threads = 0                               // this module only; 0: maxConcurrentJvms
    reuseJvms = false                         // one fresh JVM per mutant (or -Pkrispr.reuseJvms=false)
    robolectricReuse = "fresh"                // Robolectric: a fresh sandbox per mutant; default "sandbox" (or -Pkrispr.robolectricReuse=)
    useScreenshotTests = true                 // let screenshot tests kill mutants
    excludeTests = listOf("*IntegrationTest") // Gradle --tests patterns that may not kill mutants
    quarantinedTests = listOf("*FlakyTest")   // known-flaky tests: never kill, reported as quarantined
    slowTestThresholdMs = 2000L               // tests slower than this may not kill...
    includeSlowTests = true                   // ...unless included, run last,
    slowTestBudgetMs = 10000L                 // up to this much recorded time per mutant
    confirmKills = true                       // rerun every kill in a fresh JVM (or -Pkrispr.confirmKills=true)
    confirmSurvivors = false                  // Robolectric: trust survivors of a reused sandbox; default true (or -Pkrispr.confirmSurvivors=)
    robolectricKeepAfterKill = false          // Robolectric: retire a worker after every kill, no health check; default true (or -Pkrispr.robolectricKeepAfterKill=)
    forkJvmTuning = "off"                     // krispr's JVMs on the tiered JIT; default "auto": C1 only (or -Pkrispr.forkJvmTuning=)
    diffBase = "origin/main"                  // diff mode by default; --since overrides it
    targetFiles = listOf("src/main/kotlin/a/A.kt") // only these files get mutants (or -Pkrispr.targetFiles=...)
    maxSurvivorsPerFile = 3                   // survivors printed per file; the report has all
    showChanges = true                        // rerun survivors to show what changed (or -Pkrispr.showChanges=true)
}
```

On kotlinpoet, `krisprRun` went from 1564 s to 149 s and on clikt from 99 s to 26 s, with the same
kill classes; see [docs/validation.md](docs/validation.md#performance).

## Architecture

- **Separate instrumented build.**
  - The compiler plugin applies only in a Gradle invocation that requests a Krispr task (or passes
    `-Pkrispr.instrument=true`). An unqualified task name counts for every project; `:a:krisprRun`
    counts only for `:a`.
  - That invocation moves the project's whole build directory to `build/krispr/build`. Instrumented
    classes, jars and incremental caches never touch `build/classes` or `build/libs`.
  - The runtime is `compileOnly` there, and reaches the tests only through the Krispr tasks' own
    classpath.
  - Every other invocation compiles exactly as if the plugin were absent: same bytes, no runtime
    dependency, normal incremental compilation. `scripts/verify-clean-build.sh` checks this by
    comparing every class in the sample jar against a build without the plugin.
  - Mixing other tasks into a Krispr invocation is an error. `clean`, `help` and similar
    housekeeping tasks are allowed.
- **`krispr-compiler`** is an `IrGenerationExtension`, registered through `CompilerPluginRegistrar`
  (K2 only), built once per Kotlin release range (see [Kotlin versions](#kotlin-versions)). `MutationTransformer` walks each file bottom-up. It rewrites every site into
  `{ val t1 = a; val t2 = b; if (Mutants.isActive(N)) t1 - t2 else t1 + t2 }`. Operands go into
  temporaries so they are evaluated exactly once, and nested sites keep their own switches.
- **Sites**: function bodies (including lambdas and local functions), property and field
  initializers, `init` blocks, and default parameter values. `const val` initializers are left
  alone because the compiler inlines them.
- **Operators**:
  - `MATH`: `+↔-`, `*↔/`, `%→*` on numeric primitives, and the same swaps for `+=`, `-=`, `*=`,
    `/=` and `%=`
  - `CONDITIONALS_BOUNDARY`: `<↔<=`, `>↔>=`
  - `NEGATE_EQUALITY`: `==↔!=`
  - `BOOLEAN_LOGIC`: `&&↔||`, short-circuiting kept
  - `NEGATE_IF`: an `if` condition is negated, where `CONDITION_TRUE` and `CONDITION_FALSE` do not
    both apply (below)
  - `RETURN_VALUE`: a Boolean return is negated; an Int return becomes 0, or 1 when it was already 0
  - `INCREMENTS`: `++↔--`, prefix and postfix
  - `INVERT_NEGS`: `-x → x`
  - `REMOVE_CALL`: a call statement that returns Unit is removed (`validate(x)`), unless it is arid
    (logging, metrics, delays, …)
  - `NULL_RETURNS`: a nullable return value becomes `null`
  - `SKIP_IS_BRANCH`: an `is`/`!is` condition of a `when` becomes `false`, so the branch is never taken
    and the value falls through to the next branch or `else` (`is Loading -> … → false`). Negating it
    would run the branch on a value of the wrong type, which any test reaching the `when` kills.
  - `ELVIS`: `a ?: b → a!!`, so the fallback is never used and a null `a` throws. `?: return`,
    `?: continue` and `?: break` count; a fallback that already throws (`?: error(…)`, `?: throw …`)
    and `?: null` get no mutant. The switch sits after `a` is evaluated, once either way, so a test
    that never passes null leaves a survivor, not a mutant without coverage.
  - `REMOVE_CHAIN_CALL`: a call whose result has its receiver's type is skipped: `filter*`,
    `sorted*`, `distinct*`, `take*`, `drop*`, `reversed`, a same-type `map`, and Flow's `filter`,
    `distinctUntilChanged`, `take` and `drop`. So is a value-preserving adjustment: `coerceIn`,
    `coerceAtLeast`, `coerceAtMost`, `abs`, `absoluteValue`, `uppercase`, `lowercase`, `trim*` (not
    `trimIndent`/`trimMargin`), and a two-operand `minOf`/`maxOf` with one constant operand
    (`maxOf(0, x - 1) → x - 1`).
  - `BITWISE`: PIT's bitwise swaps on Int, Long, UInt and ULong: `and↔or`, `xor→and`, `shl↔shr`,
    `ushr→shl`, and `x.inv() → x`
  - `RANGE_BOUNDARY`: an `in`/`!in` check against `a..b`, `a..<b`, `a until b` or `a downTo b` on
    primitives, UInt or ULong gets one mutant per bound, flipping whether that bound is included:
    `x in lo..hi → lo < x && x <= hi` and `→ lo <= x && x < hi`. A `step` range gets one mutant that
    leaves out its first element (`x in 0..59 step 15 → x != 0 && x in 0..59 step 15`); its last
    element depends on the step, so it is left alone. Loops over ranges are left alone.
  - `CONDITION_TRUE` / `CONDITION_FALSE`: PIT's remove-conditional. The condition of an `if` or of a
    `when` branch without a subject becomes `true` (the branch always runs) or `false` (it never
    does): `if (total >= 100) → if (true)`. Negation is killed by a test of either side; these survive
    when only one side is tested. A condition forced both ways gets no `NEGATE_IF` or, for `a == b`,
    `NEGATE_EQUALITY` mutant: any test that kills one of the pair kills those too. An operand of `&&`
    becomes `true` and an operand of `||` becomes `false`, dropping that clause
    (`member && total >= 50 → true && total >= 50`), which survives when no test depends on the clause
    alone. `while` and `do … while` conditions only become `false` (`true` would loop to a timeout).
    Left alone: `else`, constants, cache guards, a `when` with a subject (`NEGATE_EQUALITY` covers it, except over an enum: see Skipped),
    `is` branches (`SKIP_IS_BRANCH`), the loops `for` desugars to, and the null checks `?.` and `?:`
    desugar to. A check that smart-casts (`x != null`, `x is T`, `!s.isNullOrEmpty()`, also inside
    `&&`/`||`) is only forced the way that skips the code relying on the cast, and `if (c) call()` is
    not forced `false`, which would repeat `REMOVE_CALL`'s mutant of `call()`.
  - `ARGUMENT_PROPAGATION`: PIT's argument propagation for standard library transforms whose result
    has their receiver's type: the call is replaced by its receiver. Text edits (`removePrefix`,
    `removeSuffix`, `removeSurrounding`, `replace*`, `substring*`, `padStart`/`padEnd`, `repeat`),
    `ifEmpty`/`ifBlank` fallbacks, `takeIf`/`takeUnless` (the receiver stands in for the nullable
    result), collection, sequence and Flow `plus`/`minus` (`xs + x → xs`), and `round`, `floor`, `ceil`
    and `truncate` (`floor(x) → x`). A survivor means no test passes an input the transform changes:
    `s.removePrefix("v") → s` survives when no test has a `v` prefix. User functions are left alone
    (`normalize(x) → x` is too often a wrapper or idempotent).
  - `SAFE_CALL_BODY`: a `x?.let { }`, `?.also`, `?.run`, `?.apply` or `x?.call()` statement whose value
    nothing reads is skipped, as if `x` were null (`album?.let { tags.put("ALBUM", it) } → (skipped)`).
    `x` is still evaluated once. A body that only logs, and a single Unit call that `REMOVE_CALL`
    already removes, get no mutant.
  - `REMOVE_ASSIGNMENT`: a store to a member `var` (`lastQuery = query`) or to a MutableStateFlow,
    MutableState or MutableLiveData `value` (`_loading.value = true`) is skipped; the receiver and value
    are still evaluated. Left alone: constructors, initializers and `init` blocks, `lateinit`
    properties, locals, properties named like a cache, and private properties nothing in the file reads
    (removing a store nobody reads back cannot be observed). `if (c) x = v` is not also forced `false`.
  - `EMPTY_STRING_RETURNS`: a named function's String return becomes `""`
    (`return "$artist · $album" → return ""`), and so does a String? return whose value may be null
    (`return null → return ""`). `toString()`, a literal `""` and lambdas (left to `EMPTY_RETURNS`) get
    no mutant; the value is still evaluated.
  - Coroutines and Flow (issue #17). The five together:
    - `FLOW_EMIT`: an `emit(x)`, `emitAll(f)`, `send(x)`, `trySend(x)` or `tryEmit(x)` statement on a
      Flow collector, channel or shared flow is removed; the argument is still evaluated. These calls
      were `REMOVE_CALL` sites; `trySend` and `tryEmit`, whose result is discarded, are new.
    - `FLOW_OPERATOR`: an intermediate Flow operator that keeps the element type is skipped
      (`flow.onEach { … } → flow`): `onEach`, `onStart`, `onCompletion`, `onEmpty`, `catch`, `retry`,
      `retryWhen`, `debounce`, `sample`, `filterNotNull`. Its arguments are still evaluated once. An
      `onEach`/`onStart`/`onCompletion` that only logs gets no mutant.
    - `COROUTINE_CONTEXT`: `withContext(ctx) { … }` runs its block in the caller's context
      (`coroutineScope { … }`), and `flow.flowOn(ctx) → flow`. A survivor means no test notices a
      `NonCancellable` cleanup, a Job or a context element. A context that is only a dispatcher
      (`Dispatchers.IO`, an injected `CoroutineDispatcher`) gets no mutant: tests run every dispatcher on
      one test scheduler, so the switch is invisible there.
    - `CATCH_SWALLOW`: `throw e` of the exception the `catch` caught is swallowed; the catch yields its
      type's default (`catch (e: IOException) { throw e }`). Throwing a different exception, and a `try`
      of type `Nothing`, get no mutant. Nor does a catch of `CancellationException` or a subtype, one that
      calls `ensureActive()`, or one that rethrows `if (e is CancellationException)`: a swallowed
      cancellation lets a cancelled coroutine carry on, which no test should have to catch.
    - `LAUNCH_BODY`: the body of `scope.launch { … }` (or a Unit `async`) is skipped; the job still starts
      and completes. A body that only logs, or is one call `REMOVE_CALL` already removes, gets no mutant.
  - Opt-in, with `operators = listOf("DEFAULTS", …)` or `-Pkrispr.operators=DEFAULTS,…`:
    - `EMPTY_RETURNS`: a String, List, Set, Map, Collection, Iterable, Sequence or Flow return value
      becomes empty (`""`, `emptyList()`, `emptyFlow()`, …); a named function's String return is left to
      `EMPTY_STRING_RETURNS` while that is on
    - `SWAP_COLLECTION_CALL`: `any {}↔all {}`, `any()→none()`, `none→any`, `first*↔last*`
      (`firstOrNull` too), `min*↔max*`, `sorted↔sortedDescending`, `sortedBy↔sortedByDescending`,
      `take*↔drop*` (`takeLast`, `takeWhile`, …). `REMOVE_CHAIN_CALL` still skips the `sorted*`, `take*`
      and `drop*` calls it swaps.
    - `COPY_ARG_DROP`: a data class `copy` leaves out one argument, one mutant per argument
      (`state.copy(loading = false, items = xs) → state.copy(items = xs)`), so that property keeps the
      copied object's value. Every argument is still evaluated; `s.copy(a = s.a)` gets no mutant.
    - `PRECONDITION_REMOVAL`: `require(c)` and `check(c) { … }` are skipped, in `init` blocks (a value
      class's included) and anywhere else; `requireNotNull(x)` and `checkNotNull(x)` return `x` unchecked.
      While it is on, a `require(c)` statement is its site rather than `REMOVE_CALL`'s.
    - `NAMED_DEFAULT_DROP`: an argument written with its parameter's name, where that parameter has a
      default, is left out, one mutant per argument (`xs.joinToString(separator = ";") →
      xs.joinToString()`, `Box(width = 2) → Box()`), in calls and constructor calls. Every argument is still
      evaluated; a constant equal to the module's own default, and a data class `copy`, get no mutant.
    - `SEALED_WHEN_ROUTE`: in a `when` over a sealed type, an `is A ->` or `B ->` branch runs the next
      such branch's body instead (`is Loading → runs the Empty branch`), as if the case were another.
      Never towards a body that relies on a smart cast of the subject, nor one with the same text.

    On clikt and kotlinpoet the opt-in operators and `REMOVE_CHAIN_CALL` together added 340 and 119
    mutants; most are killed, and of 15 sampled new survivors 9 were real test gaps and 6 could not be
    caught (`first↔last` on a one-element list), none junk. The two that remain opt-in stay so for
    their cost and the share of equivalents in `SWAP_COLLECTION_CALL` (`firstOrNull → lastOrNull` on
    a unique key, `sorted` swaps). `COPY_ARG_DROP`, `PRECONDITION_REMOVAL`, `NAMED_DEFAULT_DROP` and
    `SEALED_WHEN_ROUTE` find no gap the defaults miss, so they stay opt-in too.
  - `REMOVE_BODY`: extreme mode only (see [Extreme mode](#extreme-mode))
- **Stable ids.** A mutant's id is the first 31 bits of SHA-256 over four things:
  - the file path relative to the root project
  - the enclosing declaration's fully qualified name, with parameter types, so overloads differ
  - the operator
  - its ordinal among that operator's sites in that declaration

  Editing one function leaves every other function's ids alone. A collision is rehashed with an
  attempt counter.
- **Skipped**:
  - functions whose `IrDeclarationOrigin` is not DEFINED, LOCAL_FUNCTION or LAMBDA (data class
    members, default accessors, enum helpers, plugin-generated code)
  - classes a compiler plugin generated
  - `toString`/`hashCode`/`equals` overrides
  - calls named `println`, `print` or `log*`, and calls on `*Logger`/`Log` receivers
  - nodes without source offsets
  - `tmp == null` checks on `IR_TEMPORARY_VARIABLE`s, which is how `?.` and `?:` desugar (`?:` has
    its own `ELVIS` mutant instead)
  - the `A ->` and `A, B ->` conditions of a `when` over an enum, which the JVM backend compiles to a
    switch on the ordinal only while they keep their exact shape. Inside a schema they compare by
    identity, and an instance that is none of the entries (a mocking library's relaxed enum value)
    would match no branch and throw `NoWhenBranchMatchedException` with no mutant active. Their
    bodies still get mutants.
  - duplicate mutants: `if (a == b)` and `return a == b` get only the equality mutant
  - return values nothing can read: the result of a lambda passed to a standard pass-through call
    (`let`, `run`, `with`, `use`, `withContext`, …) whose own value is discarded, as in
    `title?.let { tags.put("TITLE", it) }` used as a statement

  Coroutine state machines, `$default` and `@JvmOverloads` bridges, enum `values`/`entries`,
  delegated-property accessors and value-class boxing are all generated after this plugin runs or
  outside user bodies, so they never get mutants. `GeneratedCodeTest` covers each of these.
  - When the plugin replaces the value of an `i++` or `i += c`, it clears that origin marker. Otherwise
    the JVM backend's `iinc` rewrite would pattern-match the marker and crash.
- APIs that moved between Kotlin 2.1.20 and 2.4 are in `IrCompat.kt` (the unified parameter and
  argument lists, source positions and declaration names) and in each variant's `VersionCompat.kt`
  (symbol lookup and the registrar's plugin id).
- **`krispr-runtime`** (Java 8 bytecode) provides `Mutants.isActive(id)`, which reads
  `-Dkrispr.active` or `KRISPR_ACTIVE` once. In recording mode (`-Dkrispr.record=<file>`),
  `Recorder` logs each mutant id against the test that is running.
- **Test attribution.** The current test comes from a JUnit Platform `TestExecutionListener`
  registered through ServiceLoader. I chose that over a Jupiter extension for two reasons: it needs
  no change to the tests being analysed, and it sees every engine. Tests are keyed by JUnit unique
  id, so the runner can re-select exactly them. Kotest (5 and 6) records each test, nested ones
  included, but runs the whole spec of any test selected by unique id; a test that already ran with
  an earlier batch of the same mutant is not run again.
- **`krispr-gradle`** (`id("dev.krispr")`) is a `KotlinCompilerPluginSupportPlugin` for one
  compilation: `main`, the chosen Android variant, or the chosen KMP target's `main`. The forks' classpath
  is the test task's own classpath (for Android, the one AGP assembles), built from instrumented classes. It registers two tasks, both of which launch `ForkedRunner` in fresh JVMs:
  - `krisprRecord` runs the whole suite once with recording on.
    - It is forced serial: Jupiter parallel execution is off and Kotest parallelism is 1. Per-test
      attribution uses one global "current test" so that work a test hands to other threads still
      counts, and that only holds when tests run one at a time.
    - It honours the test task's tag, engine and class-name filters.
    - It selects the test directories as classpath roots, and every Kotest spec in them by class:
      Kotest 6 finds specs only through class selectors.
  - `krisprRun` works through the covered mutants.
    - It runs a baseline fork to set the timeouts, then each covered mutant in a reused worker JVM or
      a fork, selecting only the tests that reached it; see [Speed and test selection](#speed-and-test-selection).
    - Exit code 1 with a failing test that passed in the baseline means KILLED. A timeout means
      TIMED_OUT, counted as killed, once the same tests without the mutant are shown to fit the timeout
      (else a rerun with a longer one, or UNKNOWN on a host too slow to tell); an `OutOfMemoryError` in a fork means MEMORY_ERROR, counted as killed. Exit 0 means SURVIVED if the mutant's branch was taken, else a
      rerun, then UNKNOWN. A mutant no test reached is NO_COVERAGE; one only excluded tests reached is
      NOT_MEASURED. See [Statuses and scores](#statuses-and-scores).
    - A mutant in a top-level or object initializer runs once per class load, so the recording run
      sees only the test that loaded the class first. Such mutants may be killed by every test.

  Every fork gets the test task's Java launcher, `allJvmArgs` (system properties, heap, `-ea`,
  argument providers; JaCoCo agents and debuggers are dropped), environment and working directory.

### Android and Compose

- **AGP public API only.** `AgpCompat.kt` uses `androidComponents.onVariants`,
  `HasHostTests.hostTests["UnitTest"]` and `HostTest.configureTestTask` (AGP 8.5+) to find each
  variant's unit test task, falling back to `HasUnitTest` and the task's name on older AGP. AGP is
  `compileOnly`, and the plugin is built against AGP 9.4.
- **Generated sources.** R and BuildConfig are Java. KSP, kapt, Room, Moshi, Hilt, SafeArgs and
  similar tools write their Kotlin into the build directory, so the compiler plugin skips every file
  under the module's original build directory (`excludeDir`). Parcelize and Hilt's bytecode changes
  come from generated declarations, which were already skipped.
- **Compose.** The Compose compiler is also an IR plugin. The instrumented compile passes
  `-Xcompiler-plugin-order=dev.krispr>androidx.compose.compiler.plugins.kotlin`, so Krispr mutates
  the code as written, before Compose adds `$composer`, `$changed`, groups and default masks. Without
  Compose the flag is a no-op. If Compose ran first, its code has no source offsets or operator
  origins and would still get no mutants. `AndroidGeneratedCodeTest` checks both orders. Composable
  code itself is [arid](#arid-code) by default, so this matters with `mutateComposables = true`.
- **Robolectric** loads app and runtime classes again in its sandbox classloader. The sandboxed
  `Recorder` copy forwards every hit to the system classloader's copy, which knows the current
  test, and the sandboxed `Mutants` copy registers with the system copy to follow its active mutant.
- **Test plugins' `doFirst` settings.** Forks copy the test task's `allJvmArgs`, which does not include
  system properties that a plugin sets in a `doFirst`. Roborazzi does this for record, compare and
  verify. Krispr forwards the same `roborazzi.*` Gradle properties, so screenshot tests verify in
  the forks when `useScreenshotTests` lets them run.

### AGP versions

- **Supported: AGP 8.5.2 to 9.4.** 8.5.2 is the oldest AGP the Kotlin 2.4.20 Gradle plugin accepts,
  and that Kotlin version is what the compiler plugin is built against. `AgpCompat.kt` also falls
  back to `HasUnitTest` for AGP 8.1 to 8.4, untested until an older Kotlin can be used with them.
- **Kotlin plugin.** AGP 9 compiles Kotlin itself; AGP 8 needs `org.jetbrains.kotlin.android`.
  Kotlin Multiplatform works with `com.android.kotlin.multiplatform.library` on both, and with
  `com.android.library` + `androidTarget()` on AGP 8. AGP 8 reports the multiplatform Android
  library target as a `jvm` target, so Krispr recognises it by its AGP type instead.
- **Gradle.** AGP 8 does not run on Gradle 9.6 or newer (it uses an internal API 9.6 removed).
  `scripts/agp-gradlew <agp> <args>` runs Gradle 8.14.3 for AGP 8 and the repository's wrapper for
  AGP 9, passing `-Pkrispr.agp`.
- **sample-android** builds against any of them:
  `cd sample-android && ../scripts/agp-gradlew 8.5.2 krisprRun`, and
  `KRISPR_AGP=8.5.2 scripts/verify-clean-build.sh`. With AGP 8 it applies `kotlin-android`, KSP
  2.3.4 below AGP 8.12 (KSP 2.3.6+ requires 8.12) and the Hilt 2.57.2 Gradle plugin (2.59+ requires
  AGP 9); the Hilt libraries stay on 2.60.1, the first to read Kotlin 2.4 metadata. Mutant results
  are the same on 8.5.2, 8.13.2 and 9.4.1.
- **Functional tests.** `./gradlew :krispr-gradle:functionalTest` (part of `check`) builds small
  Android and multiplatform projects with Gradle TestKit on AGP 9.4.1, 8.13.2 and 8.5.2: the tasks are
  registered, a normal build compiles no mutants, and `krisprRun` writes a report. They need an
  Android SDK and skip without one. The first run downloads Gradle 8.14.3 and each AGP.

## Known gaps

- **Android**: only local unit tests. Instrumented and on-device tests, `testProject`, and
  mutating more than one variant per run are not supported. Paparazzi's `doFirst` settings are not
  reproduced, which matters only with `useScreenshotTests`. Robolectric 4.17 cannot set up SDK 36 on JDK 21; the sample pins `sdk=35`.
- **One JVM-hosted target, one test task.** Only a module's own JVM or Android unit tests are used,
  or those of one `testProject`. JS, Wasm and native tests of a multiplatform module do not count
  (see [docs/kmp.md](docs/kmp.md)).
  TestNG is not supported.
- **Cross-module test runs** compile the test project against the instrumented jar in its normal
  build directory. Its next normal build recompiles, because that input has changed.
- **Filters**: an `--tests`/`filter` include that names a method selects the whole class, and a
  method-level exclude is ignored.
- **Incremental**: the instrumented compilation is not incremental, because the manifest is written
  per compilation. Verdicts are reused (see [Incremental runs](#incremental-runs)), but the build and the
  recording run still happen every time.
- **Equivalent mutants**: only swaps a literal makes equivalent are filtered (`x + 0`, `x * 1`, `x / -1`;
  see [Arid code](#arid-code)). In the validation run, about half of the sampled survivors were
  equivalent. Common cases:
  - boundary flips on assignments that give the same value
  - `> 0` → `>= 0` where the value is never 0
  - `&&`/`||` flips hidden by an earlier branch
  - collection-capacity arithmetic
- **Not mutated**: conditions of a `when` without a subject other than `is` checks (only `if` is negated),
  ranges iterated by a `for` loop,
  string templates, and return values other than Boolean, Int, nullable and (opt-in) empty ones. Equality checks in a subject `when` are
  mutated, but their descriptions are poor.
- **K2 API stability**: the plugin uses `IrElementTransformerVoidWithContext`,
  `DeclarationIrBuilder` and the `@UnsafeDuringIrConstructionAPI` symbol owners. These are internal
  compiler APIs with no compatibility promise, and each Kotlin minor may break them. Kotlin is
  pinned to 2.4.20.
- **Reused JVMs isolate the build's classes only.** A library that loads project classes through
  its own classloader, or state kept in the JDK or a library, is shared between the mutants a worker
  runs. The baseline check catches tests that depend on it, not mutants that disturb it. In a reused
  Robolectric sandbox the project's classes are loaded once for every mutant the worker runs, so a
  `lazy` or `object` value computed while a surviving mutant was active is what later mutants see.
  Survivors are confirmed in a fresh JVM (`confirmSurvivors`), but a kill that such a value caused is
  not, unless `confirmKills` is on. Set `robolectricReuse = "fresh"`, or `reuseJvms = false`, if
  statuses look wrong.
- **Screenshot detection is by constant pool.** Only a test class that uses a screenshot library
  directly counts, so a test that captures through its own helper (nowinandroid's `captureMultiTheme`)
  is a killer like any other; add it to `excludeTests`. `useScreenshotTests = true` turns detection
  off. The recording run still runs every test.
- Configuration cache and isolated projects worked on nowinandroid, which enables both, but no test
  covers them.

## Developing Krispr

- **`scripts/check-fast.sh`** for everyday operator/runtime iteration: one compiler variant, runtime and
  gradle-plugin unit tests, no functional tests. ~20s warm.
- **`scripts/check-full.sh`** before merging: every compiler variant, functional tests (Gradle TestKit
  builds of sample projects), and `scripts/verify-clean-build.sh`. ~1 min warm, ~8-12 min cold.

See [docs/dev-loop.md](docs/dev-loop.md) for the measurements behind these numbers (where build time goes,
the slowest functional tests, and why) and each script's header for details.

## License

Copyright 2026 Tim Malseed. Licensed under the [Apache License, Version 2.0](LICENSE).
