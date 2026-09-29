# Kotlin Multiplatform

Krispr mutates Kotlin IR, which every Kotlin backend shares, so it can mutate `commonMain` code.
Bytecode tools such as PIT only see what one JVM compilation produced, and have no way into common
code except through that JVM output. This page covers what works today (the JVM-hosted path) and what
running the same mutants on the native, JS and Wasm backends would take, with the results of a probe
that compiled and ran mutants on all three.

## What works: commonMain through a JVM-hosted target

Apply `id("dev.krispr")` to a module with `kotlin("multiplatform")` and run `krisprRun`. The Gradle
plugin picks one JVM-hosted target:

- the module's single `jvm()` target (any name: `jvm("desktop")` is tested by `desktopTest`), or
- without a JVM target, its Android target (`com.android.kotlin.multiplatform.library`, tested by
  `testAndroidHostTest`; or `com.android.library` with `androidTarget()` on AGP 8), or
- whichever of those `krispr { kotlinTarget = "..." }` names.

Only that target's `main` compilation gets the compiler plugin, in the separate instrumented invocation
that builds into `build/krispr/build`. It compiles `commonMain` plus the target's own source set
(`jvmMain`), and its test task runs `commonTest` plus `jvmTest`. Mutants are reported against the
source file they come from: `src/commonMain/kotlin/...` or `src/jvmMain/kotlin/...`, with their real
lines. Native, JS and Wasm targets in the same module are left alone: the normal build compiles them
exactly as without the plugin, and the instrumented invocation never builds them.

A module with only native, JS or Wasm targets fails `krisprRun` with its target list and a note that
it needs `jvm()`.

Checked by:

- `sample-kmp/`: `jvm()`, an Android library target, `js(IR)` and `macosArm64()`. `commonMain` has a
  data class with comparisons, branches, `expect`/`actual`, and a `Flow` pipeline; the tests are
  deliberately weak in places. `cd sample-kmp && ../gradlew krisprRun` reports 89 mutants, 83 in
  `commonMain` and 6 in `jvmMain`, with the expected survivors (a download threshold tested only away
  from its boundary, the 0.x caret rule). `-PkrisprTarget=android` runs the same `commonMain` through
  `testAndroidHostTest`. The sample's own tests pass on all four targets.
- `KmpFunctionalTest`: a `jvm()` + `js(IR)` project under the configuration cache (stored and reused
  entry). A normal build compiles both targets uninstrumented; `krisprRun` attributes mutants to
  `commonMain` and `jvmMain`, a strong `commonTest` test kills `score >= 50 → score > 50`, and a weak
  one lets `score >= 90 → score > 90` survive. A JS-only module fails with the message above.
- The Android KMP variants in `AndroidFunctionalTest` (AGP 9.4.1 and 8.13.2, `androidTarget()` on 8.13.2).

What this does not cover: code that only runs on another platform (`iosMain`, `jsMain`, `nativeMain`
actuals), and behaviour of common code that differs by backend (number overflow and `toString()` on JS,
`Double` formatting, threading on native). A mutant that `commonTest` kills on the JVM is killed there;
it may behave differently elsewhere.

## Native, JS and Wasm: feasibility

### The probe

A throwaway two-module build (Kotlin 2.4.20, this machine: macOS arm64, Xcode beta, Node 22) with:

- `:runtime`, a multiplatform stand-in for krispr-runtime: `object dev.krispr.runtime.Mutants` with
  the `isActive(id: Int): Boolean` the compiler plugin calls, reading the active id from the
  `KRISPR_ACTIVE` environment variable (`getenv` on native, `process.env` on JS and Wasm) and noting
  each id reached;
- `:lib`, with `js(IR)`, `wasmJs`, `macosArm64` and `jvm` targets, a `Grade.kt` in `commonMain` and a
  `commonTest` with one strong and one weak test. Each target's main compile task got the unmodified
  `krispr-compiler-k240` jar through `-Xplugin=` and `-P plugin:dev.krispr:manifest=...`, bypassing
  the Gradle plugin, which only instruments JVM-hosted compilations.

Results:

- **The compiler plugin works unchanged on all four backends.** Each compile wrote a manifest; the four
  manifests list the same 9 mutants with the **same ids**, since ids hash the file path relative to the
  root, the enclosing declaration and the operator, not anything backend-specific. A JVM run and a
  native run of the same mutant can be matched directly. No compiler change was needed.
- **The schemata work at run time.** The linked macOS executable and the JS executable under `node`
  print identical results for each `KRISPR_ACTIVE` value: with none, original behaviour; with a mutant
  id, that mutant's behaviour, and the switch records it as reached.
- **Per-mutant test runs work without recompiling.** The `commonTest` binary is linked once
  (`test.kexe` on native; the `kmp-probe-lib-test.js` bundle, run with the Mocha that the Kotlin plugin
  installs, on JS; `wasmJsNodeTest` with the variable set on the test task, on Wasm). Running it once
  per mutant with `KRISPR_ACTIVE` set gave the same verdicts on native and JS for all 9 mutants: the
  strong test fails for the 6 mutants it should catch, and the three boundary/`false` mutants on
  `grade` survive the weak test. The two Wasm runs tried agreed. A run of the two tests took about
  70 ms on native and 95 ms on JS, process start included.

Nothing here needed a large download: the Kotlin/Native 2.4.20 toolchain went to `~/.konan` (the build
fetched it for `sample-kmp`'s `macosArm64` target) and Node came from the Kotlin Gradle plugin's own
setup. iOS simulator targets were not tried: running `iosSimulatorArm64` tests needs a simulator runtime
from Xcode, a multi-gigabyte download not present here. `linuxX64` and `mingwX64` were not tried either.

### What a real implementation needs

**1. A multiplatform runtime.** krispr-runtime is JVM-only: `Mutants` uses `@JvmStatic`, `Runnable`,
class loaders (for Robolectric sandboxes) and reads a system property; the JVM runner, worker and
JUnit listener sit beside it. It needs a small multiplatform part (switch plus hit recording) with the
JVM variant unchanged: the JVM artifact is still compiled for Java 8 and Kotlin 2.1.20 metadata, and
must keep its class and member names. Native needs the hit set and the "activated" flag to be
thread-safe (`kotlin.concurrent` atomics or a lock); coroutines on `Dispatchers.Default` run on other
threads. JS in a browser has no environment variables, so browser runs (Karma) would read the id from a
global set in the Karma config; Node runs can use `process.env` as in the probe.
Estimate: 2 to 3 days, most of it publishing and keeping the JVM artifact byte-compatible.

**2. Gradle: instrument the non-JVM test binaries.** `isApplicable` would accept the chosen native, JS
or Wasm target's `main` compilation too; the instrumented invocation already moves the build directory,
so linked test binaries with mutants stay out of normal outputs. Unlike on the JVM, the runtime must be
linked into the test binary, so it is an `implementation` dependency of that compilation in the
instrumented invocation (it never leaks into normal builds, which do not apply the compiler plugin).
Each backend writes its own manifest; since ids agree, they merge by id. The compile-time cost is one
extra instrumented compile and link per target, not per mutant. Estimate: 1 to 2 days, plus the
configuration-cache and functional tests.

**3. A runner per backend.** This is the bulk of the work. The JVM runner runs tests through the JUnit
Platform in a recording JVM, then per mutant in reusable worker JVMs with fresh class loaders. None of
that applies:

- *Process per mutant, always.* Native code cannot be reloaded, and a fresh Node process is cheap
  enough (tens of milliseconds) that `vm` contexts are not worth their isolation problems. Timeouts
  kill the process, as they do forks today.
- *Test selection.* Native test binaries take `--ktest_filter=Class.method` patterns; Mocha takes
  `--grep`. Both can run exactly the covering tests.
- *Coverage recording.* The JVM uses a JUnit listener that knows the current test. Native test
  binaries emit TeamCity service messages (`--ktest_logger=TEAMCITY`) with test start and finish; if the
  runtime prints its hits between them (or the recording run writes hits per test to a file on a
  test-finished hook), the runner can attribute them. Mocha's reporter events do the same for JS and
  Wasm. Hits reached outside any test (object and top-level property initializers) need
  care, as on the JVM; the manifest already flags mutants in class initializers.
- *Verdicts.* Exit codes and the TeamCity or Mocha output give pass/fail and the failing tests, which
  map onto the existing statuses (KILLED, SURVIVED, TIMED_OUT, RUN_ERROR, NO_COVERAGE).
- *Reporting.* Which backend killed a mutant becomes part of the report: a mutant can survive on the
  JVM and be killed on JS (or the reverse) when common code behaves differently there.

Estimate: native (host targets: macOS, Linux, Windows) about a week; JS and Wasm on Node about a week
together, sharing the Mocha side; browser and iOS simulator runs another few days each, mostly tooling.

**Overall:** about 3 weeks for native host targets plus JS and Wasm on Node, reusing the compiler plugin
as is. The risk is in the runners (test frameworks and output formats per backend), not in mutation:
the probe showed the schemata compile, run and flip behaviour identically on every backend.
