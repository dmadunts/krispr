# Dev loop: where the time goes, and how to go faster

All numbers below are from runs actually performed on this machine (`--max-workers=4` throughout),
not estimates, unless marked **(guess)**. See `scripts/check-fast.sh` / `scripts/check-full.sh` for the
tiered verification this doc justifies.

## Where the time goes: `./gradlew check --profile`

Full `check` (5 compiler variants + runtime + gradle-plugin unit tests + `krispr-gradle:functionalTest`),
cold, `--max-workers=4`. Total build time **8m10s**; per-project task-execution total from the profile
report (`build/reports/profile/profile-*.html`, "Task Execution" section):

| Project | Time | Notes |
|---|---|---|
| `:krispr-gradle` | 5m44.7s | almost all of it is `functionalTest`: 5m39.87s |
| `:krispr-compiler-k240` | 39.0s | `test` 21.5s + `testKotlin2_4_0` 15.8s (two Kotlin point releases) |
| `:krispr-compiler-k2320` | 28.5s | `testKotlin2_3_20` 13.4s + `test` 13.4s |
| `:krispr-compiler-k2120` | 28.3s | `test` 13.6s + `testKotlin2_1_20` 12.7s |
| `:krispr-compiler-k230` | 27.6s | `testKotlin2_3_0` 13.0s + `test` 12.8s |
| `:krispr-compiler-k220` | 26.9s | `test` 12.8s + `testKotlin2_2_0` 12.2s |
| `:krispr-runtime` | 1.3s | negligible |
| root `:` | 0.8s | negligible |

**Top-5 time sinks, ranked:**
1. `krispr-gradle:functionalTest` — 5m39.87s, ~69% of the whole `check`. Each of its 57 test cases
   spins up a nested Gradle/AGP `GradleRunner` build (its own daemon, dependency resolution, compile).
2. Each compiler variant's *older* Kotlin point-release test task (`testKotlin2_x_y`) — running the same
   suite twice (newest + one older point release) roughly doubles every variant's cost, ~13-16s extra each.
3. `:krispr-compiler-k240:test` (newest point release per variant) — 12-22s each, ×5 variants ≈ 75s total;
   unavoidable if every variant's own compiler must be exercised, but this is the floor `check-fast.sh`
   uses (one variant only).
4. Compile tasks (`compileKotlin`/`compileTestKotlin`) per variant — ~1-2s each, ×5 variants ≈ 10s; small
   individually, adds up because there are 5 near-duplicate compilations of very similar plugin code.
5. Everything else (`:krispr-runtime`, root project, jar/resources tasks) — under 2s combined, not worth
   optimizing.

## The 10 slowest functional tests (JUnit XML `time`, from `functionalTest`'s test-results)

| Time | Test |
|---|---|
| 13.34s | `AndroidFunctionalTest`: ANDROID_LIBRARY on AGP 9.4.1 |
| 10.20s | `AndroidFunctionalTest`: ANDROID_LIBRARY on AGP 8.5.2 |
| 9.46s | `JvmFunctionalTest`: a mutant that runs out of memory in a fresh JVM is a memory error, which counts as killed |
| 6.98s | `JvmFunctionalTest`: slow tests kill only when included, within the budget |
| 5.83s | `AndroidFunctionalTest`: KMP_ANDROID_LIBRARY on AGP 8.13.2 |
| 5.26s | `AndroidFunctionalTest`: KMP_ANDROID_LIBRARY on AGP 9.4.1 |
| 5.12s | `JvmFunctionalTest`: Kotest 5 specs are still recorded and kill mutants |
| 4.40s | `JvmFunctionalTest`: a second run reuses verdicts, except where a function or a test changed |
| 3.94s | `KmpFunctionalTest`: a good commonTest kills a commonMain mutant and a weak one survives, with the config cache |
| 3.93s | `JvmFunctionalTest`: showChanges reports what each survivor changed, and leaves no trace when off |

Why each is slow:
- The four `AndroidFunctionalTest` cases each build a real Android/KMP-Android sample with AGP inside a
  nested `GradleRunner`; AGP 8.x cases additionally force `withGradleVersion("8.14.3")`, a different
  Gradle distribution than the outer build, paying for a second daemon spin-up.
- The three slowest `JvmFunctionalTest` cases each run a real nested build with a second real JVM fork
  (OOM test), a real wall-clock budget/timeout, or Kotest's own discovery — all inherently slower than a
  plain unit test regardless of Gradle overhead.
- Per-class totals (`JvmFunctionalTest` 66.5s / 34 tests, `AndroidFunctionalTest` 38.3s / 5 tests,
  `OperatorFunctionalTest` 22.0s / 14 tests, `KmpFunctionalTest` 4.6s / 2, `TestValueFunctionalTest` 3.0s / 2)
  show `JvmFunctionalTest` has the most total time but `AndroidFunctionalTest` has the highest per-test cost
  (~7.7s/test) because of the nested AGP builds.

## Tiered verification

- **`scripts/check-fast.sh`** — one compiler variant (`k240`, matching the root build's own Kotlin
  version) + runtime + gradle-plugin unit tests. No functional tests, no `verify-clean-build.sh`. Measured
  warm (after touching one source file in each of the three targets): **20s**. Use for everyday
  operator/runtime iteration.
- **`scripts/check-full.sh`** — `./gradlew check` (everything: all 5 variants, functional tests) +
  `scripts/verify-clean-build.sh`. Measured warm (near-fully up-to-date): **52s** (19s + ~33s). Cold, per
  the profile run above, `check` alone is ~8m10s. Run before merging.

## Functional-test speedup: `maxParallelForks`

`krispr-gradle:functionalTest` now sets `maxParallelForks = 2` (was 1) — each fork's nested Gradle/AGP
builds already cap at `--max-workers=2`, so 2 forks fits the outer `--max-workers=4` without
oversubscribing. Fair warm A/B (same cache/daemon state both sides, `--rerun`):

- `maxParallelForks=1`: **145s**
- `maxParallelForks=2`: **138s**, ~5% faster

This is far short of a naive per-class-bin-packing estimate (the biggest class, `JvmFunctionalTest`, is
66.5s of 134s total test time, suggesting a ~68s floor with 2 forks) — the first cold-vs-warm comparison
(339.87s → 123s) mostly measured cache/daemon warmth, not forking. Each fork pays its own JVM/Gradle-daemon
startup and the nested AGP builds themselves are the bottleneck, not raw JUnit method time, so gains are
modest. Kept anyway: no coverage change (57/57 tests still run and pass) and no downside within the outer
build's worker budget.

**Speedups considered and not done:**
- *Shared TestKit dir / warm daemon*: not implemented — `GradleRunner` already reuses a warm daemon across
  same-version builds via the default TestKit dir under the Gradle user home; the AGP 8.x cases force a
  distinct Gradle distribution (`withGradleVersion("8.14.3")`) and pay a second daemon regardless. Changing
  this touches shared `~/.gradle` state on a machine with other concurrent Krispr builds, which the brief
  says to leave alone; not attempted.
- *Moving non-build-dependent checks to unit tests*: not done. Every functional test's assertion depends on
  a real Gradle/AGP build actually running Krispr end-to-end (that's the point of the suite); nothing in
  the 10-slowest list or the class breakdown looked like it was only there for convenience and could be
  downgraded without losing coverage of an actual Gradle-integration behavior.

## Compiler variants that have ever failed alone

No commit in `git log` documents a compiler variant (`k2120`/`k220`/`k230`/`k2320`/`k240`) failing when run
in isolation but passing as part of the full matrix, or vice versa — searched commit messages and diffs for
"variant", "K2", "fail", "isolated" and found none. Related history, for reference (not isolated-failure
cases):
- `ad884f6` / `7ab3517` "fix(gradle): resolve the compiler version Krispr actually runs on" — fixed
  variant *resolution* (which variant a build picks), not a variant's own tests failing.
- `6c7e801` / `5fe5b9e` "feat(compiler): test middle patch releases under `-Pkrispr.fullMatrix`" — added
  the second (`testKotlin2_x_y`) test task per variant, presumably because a point-release-specific
  regression was a concern, but no failure is recorded in the message.

No guess is offered beyond this: there is no evidence in history to guess from.
