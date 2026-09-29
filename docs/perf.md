# Whole-app runs: where a mutant's time goes

Measured with the phase timing every `krisprRun` now reports: one line per module at lifecycle level
(`krispr: phases (mean per run): ...`) and one line per JVM and per run at `--debug`
(`krispr: fork mutant-N: JVM start ... ms, framework set-up ... ms, other framework ... ms, tests ... ms`).

- **JVM start**: launching a fork until its runner's `main` runs (the parent's clock against the fork's).
- **Framework set-up**: the costliest single test's Robolectric overhead ("initialization" plus "reset
  Android state" from Robolectric's `PerfStatsReporter`), which in a fresh JVM is building the sandbox:
  loading and instrumenting `android-all`, and creating the `Application`.
- **Other framework**: every other test's Robolectric set-up and reset.
- **Tests**: the rest of the run, including class loading and JIT of the code under test.

## sample-android, before any reuse (issue #31, step 1)

`sample-android`, `krisprRun --max-workers=4`, `threads` 4, `-Pkrispr.history=false`, 10-core host at
load ~8. `:lib` has one Robolectric class (`CartTextTest`, 2 tests) and one plain JUnit class
(`CartCalculatorTest`); `:app` has plain JUnit tests only.

| Run | JVM start | Framework set-up | Tests | Total |
|---|---|---|---|---|
| `:lib` recording (whole suite) | 109 ms | 4645 ms | 714 ms | 5554 ms |
| `:lib` baseline fork | 78 ms | 3550 ms | 720 ms | 4431 ms |
| `:lib` mutant fork reaching `CartTextTest` (6 of 19, 4 at once) | 76-85 ms | 5070-5720 ms | 688-715 ms | 5.9-6.6 s |
| `:lib` mutant fork reaching only `CartCalculatorTest` (13 of 19) | 59-77 ms | 0 | 148-188 ms | 237-288 ms |
| `:app` reused-JVM run (26 mutants, 4 workers) | 96-113 ms once per worker | 0 | 6-284 ms | |

`:lib` took 17.1 s wall for 19 mutants: all of them in fresh JVMs, because the module has Robolectric
on its classpath. In a Robolectric fork, building the sandbox is ~86% of the mutant's time; starting the
JVM itself is ~1%. So the win is in keeping the sandbox, not the JVM: a pool of warm plain JVMs would save
76 ms of 6.6 s. This decides `robolectricReuse = sandbox` (step 3): keep one Robolectric JVM, and its
sandbox, per worker thread and switch the active mutant inside it.

The timeout of a fresh Robolectric fork must also cover the ~5 s set-up, which grows with load (3.5 s
for the lone baseline fork, 5.7 s with 4 at once): see `timeoutMinimumMillis` and the per-runner start-up
below.

## Robolectric sandbox reuse (step 3)

`:lib`, same settings, `-Pkrispr.maxConcurrentJvms=4`, alternating the two modes on one busy host:

| `robolectricReuse` | wall (3 runs) | JVMs | Sandboxes built | Verdicts |
|---|---|---|---|---|
| `fresh` | 15.7, 20.1, 21.5 s | 20 forks | 7 (baseline + 6 mutants) | 15 killed, 4 survived |
| `sandbox` | 19.0, 21.1, 21.9 s | 1 fork + 9 workers | 7 (baseline, check, 5 mutants) | identical, mutant by mutant |

Reuse works: in a worker that already has a sandbox, a Robolectric run's framework set-up drops from
4.7 s to 11-12 ms (the check's second round, and the first mutant after it). It saves nothing on
`:lib` because 5 of its 6 Robolectric mutants are killed by a Robolectric test, and such a kill retires
the worker, so the next Robolectric mutant builds a new sandbox; the first 4 also start at once, one
sandbox each. The saving is one sandbox build (~5 s of CPU) per Robolectric mutant that survives, or
is killed by a plain test, on a worker that already has one: it grows with modules that have many
Robolectric mutants per worker and survivors among them, which `sample-android` does not.

The verdict sets of 4 `sandbox` runs, 4 `fresh` runs and the pre-change baseline are identical.
`sandbox` stays the default because it never builds more sandboxes than `fresh` apart from the
check's, and the check is what keeps the order-dependence guard. A whole-app measurement on a
Robolectric-heavy project should confirm it.

## Confirming sandbox survivors

A reused sandbox keeps the project's classes, so a companion, `object`, `lazy` or DI-singleton value
computed by an earlier run (the reuse check's, with no mutant, or a surviving mutant's) is what the
next mutant's tests see. Retiring the worker after a Robolectric kill does not cover a survivor, and
the value can hide what a later mutant changes: in `AndroidFunctionalTest`'s `Price`, a surviving
mutant of `remember` fills a companion cache and both mutants of `format` then survive in the same
worker, where a fresh JVM kills them. `confirmSurvivors` (default on with `sandbox`) reruns every
mutant that survived a Robolectric test in a reused sandbox in a fresh JVM, whose verdict is reported,
and prints `N survivors re-checked in fresh JVMs, M changed` per module. Survivors reached only by
plain tests are not rerun: those load the project's classes afresh for each mutant.

`:lib`, same settings, alternating on one host at load ~10:

| `confirmSurvivors` | wall (3 runs) | Re-checked | Changed | Verdicts |
|---|---|---|---|---|
| off | 14.8, 14.6, 17.5 s | | | 15 killed, 4 survived |
| on | 19.1, 18.5, 19.0 s | 1 | 0 | identical |

Of `:lib`'s 4 survivors one ran a Robolectric test; its fresh JVM builds a sandbox (~4 s) and, being
the last run, adds most of that to the wall time. The cost is one sandbox build per Robolectric
survivor; `M changed` on real projects decides whether it stays on by default.

## Timeouts per runner (step 4, #34)

The same whole-sample run (`krisprRun --parallel`, cap 4, `--info`) logs the start-up each runner's
timeout allows for:

| Module | Fresh JVM | New worker | Warm worker | Per-mutant timeout |
|---|---|---|---|---|
| `:lib` | 6386 ms | 5214 ms | 12 ms | 10000-12041 ms |
| `:app` | 334 ms | 0 ms | 0 ms | 10000 ms (the minimum) |

`:lib`'s fresh-JVM start-up is the baseline's time beyond its tests' own time (6386 ms); the measured
JVM start plus sandbox set-up was 5379 ms, and the larger of the two is used. The #34 failure mode was
that subtraction alone: a recording slower than the baseline made it zero, and the mutants' fresh JVMs
timed out at the 4 s constant while still building their sandbox. Verdicts for both modules are
unchanged against the pre-change baseline; no mutant timed out.

## Sharing the cap between modules (#41)

A whole-app run on a 40-module Android app (`--max-workers=4`, cap 4) ran one module at a time: each
`krisprRun` thread took its build-wide slot for its module's whole queue, so the app module, reaching
its mutants first with 517 of them, held all 4 slots for 24 minutes. Three other `krisprRun` tasks
waited for a slot holding the other 3 Gradle workers, so no other module could compile or record, and
23 modules never recorded before the run was stopped.

Now:

- **A slot per mutant, kept while nobody else waits.** A thread takes a slot before a mutant's runs (its
  worker run and any fresh-JVM retry, one at a time) and keeps it, and its warm worker, while no other
  task waits for one. Once another task waits, the thread gives the slot back after a quantum: ten times
  a new worker's measured framework start-up, at least 5 s (50 s for a ~5 s Robolectric sandbox, so
  rebuilding it costs about a tenth of the slot's time), or after every mutant when the module runs
  fresh JVMs only. It closes its worker before it releases the slot, so live krispr JVMs never
  outnumber the cap, then queues again. The reuse check's worker is closed too rather than parked for
  the first mutant thread, which would keep a JVM alive without a slot.
- **A slot for every waiting module first.** A task holding more than one slot passes one on at once,
  after its current mutant and without waiting out the quantum, when another task waits holding none.
  That costs one worker rebuild per module that arrives, and cannot bounce back: once each holds one,
  only the quantum moves slots.
- **Fair turns.** A freed slot goes to the waiting request of the task holding the fewest slots, the
  earliest among those. The chosen request still waits for its own module's cap.
- **Record first.** Every `krisprRun` runs after every `krisprRecord` in the build (`mustRunAfter`), so
  compiling and recording never wait behind `krisprRun` tasks that hold Gradle workers while they wait
  for slots. Gradle has no public way to give a task's worker lease back while it blocks: the Worker
  API's work items hold a lease each too, and `maxParallelUsages` on a build service counts tasks, not
  JVMs. Recording first costs a little overlap at the end of the recording phase and keeps the Gradle
  workers for work that can use them.

`JvmFunctionalTest` checks it with two modules at cap 2: a small module that asks for slots only once
a large one runs mutants on both starts its mutants before the large one ends (with the old slots it
started 0.3 s after the large module's last mutant), and no more than 2 test JVMs ever run at once.
`--info` logs, per module, when its mutants ran and how often it gave a slot back.

`sample-android`, `krisprRun --parallel --max-workers=4 -Pkrispr.maxConcurrentJvms=2
-Pkrispr.history=false`, one run each: the build took 43 s before and 32 s after, with identical verdicts
mutant by mutant (`:app` 13 killed, 13 survived; `:lib` 15 killed, 4 survived) and at most 2 JVMs at
once. Before, `:app` recorded and ran its mutants while `:lib` was still recording; now both record
first. `:lib` took both slots first; without the pass-on rule above, `:app` (26 mutants, 1 s of work)
waited 14 s for one because `:lib`'s quantum (37 s) outlasted its whole run. With it, `:app` got a slot
4 s after it asked, when `:lib`'s mutant in hand finished, and ran while `:lib` ran on the other.

## Timeouts on a busy host (#42)

Timeouts are fixed early in each `krisprRun`, from the baseline fork and the worker check. In a
whole-app run where another job pushed the host's load average from ~10 to between 100 and 277 on 10
cores, Robolectric modules' set-up and tests grew past those timeouts later in the run: a design-system
module reported 17 TIMED_OUT of 160 mutants and an article module 15 of 110 (2 in the run before), and
3 mutants that had survived before, two of them real test gaps a reviewer confirmed, became TIMED_OUT
and counted as killed. The fresh-JVM retry at twice a fork's timeout had timed out too.

The rule now, for every TIMED_OUT from a fresh JVM (a first fork, or a worker timeout's retry):

1. **Control.** The mutant's tests run once more in a fresh JVM, right after, with no mutant active and
   the timeout that just ran out. Its time includes the JVM's start-up and set-up on the host as it is
   at that moment.
2. **Same formula, measured now.** The timeout is worked out again from the control's time: `time *
   timeoutFactor + timeoutConstantMillis`, never below `timeoutMinimumMillis` (with no baseline-wide
   cap: the host may be slower than it was for the baseline).
   - Within the timeout that ran out: the tests still fit it, so the mutant is what made the run long.
     TIMED_OUT stands and counts as killed.
   - Above it: the host slowed since the timeouts were set. The mutant runs once more in a fresh JVM
     with the new timeout, and that verdict counts (a TIMED_OUT then stands).
   - The control timed out as well, or failed to run: UNKNOWN, reason `host too slow: …`, which counts
     against the score like any UNKNOWN and is never a kill.

Checking only that the control finished within, say, half the timeout was rejected: a fresh
Robolectric JVM spends ~5 s building its sandbox, so an unmutated control takes about half of a
~11 s Robolectric timeout on a quiet host, and real infinite loops would have become UNKNOWN. The
formula already allows for start-up, so applying it to a time measured now separates load from the
mutant without that guess.

The cost is one control run per TIMED_OUT, as long as the tests take unmutated, plus one more mutant
run only when the host has slowed. A module prints `N timeouts came on a host slower than when the
timeouts were set: R ran again …, U are UNKNOWN` when any did, with the host's load average in each
UNKNOWN's line, and the summary line shows `N killed (T timed out)` and `U unknown (S timed out on a
slow host)`. The history ignores verdicts written before this change, whose TIMED_OUTs may be load.
## Playground baseline

`playground-android/` (shapes in its README) through `scripts/playground-bench.sh`, on origin/main as of
5fc0a15: `--max-workers=4`, `-Pkrispr.maxConcurrentJvms=4`, history off, `org.gradle.parallel` on. The host
is a 10-core Mac, but it was shared: load average 14-97 from other sessions during these runs, so walls are
2-3x what a quiet host gives, and the under-5-minute target has not been measured on a quiet one.

Baseline `testDebugUnitTest --rerun` 21.5 s; `krisprRun` 409.5 s, 19.1x (`--verify` run, load 14 → 97):

| Module | Mutants | Killed | Survived | Wall | Start | Fresh JVMs | Reused / workers started | Re-checked / changed |
|---|---|---|---|---|---|---|---|---|
| `:catalog` | 226 | 185 | 40 | 200.9 s | 12.6 s | 21 | 204 / 92 | 15 / 0 |
| `:checkout` | 109 | 92 | 16 | 23.0 s | 12.2 s | 0 | 108 / 9 | 0 / 0 |
| `:settings` | 19 | 18 | 1 | 120.7 s | 285.4 s | 1 | 18 / 18 | 1 / 0 |
| `:session` | 56 | 48 | 8 | 90.8 s | 279.9 s | 41 | 15 / 3 | 0 / 0 |
| `:pricing` | 22 | 16 | 4 | 277.3 s | 39.1 s | 9 | 11 / 12 | 9 / 5 |
| `:mocks` | 40 | 32 | 7 | 267.2 s | 14.0 s | 7 | 32 / 29 | 7 / 0 |
| `:feed` | 68 | 61 | 7 | 10.7 s | 12.3 s | 0 | 68 / 1 | - |
| `:status` | 37 | 33 | 4 | 82.8 s | 285.4 s | 0 | 37 / 2 | - |
| `:units` | 50 | 41 | 8 | 33.3 s | 368.5 s | 0 | 49 / 2 | - |

`robolectricReuse=fresh` took 344.5 s, less than sandbox reuse's 409.5 s, with one verdict different:
`SearchIndex.kt:27` (`thenBy { null }`) leaves ties in `HashMap` order, and `Product` hashes an enum by
identity, so that verdict depends on the JVM, not on reuse. An earlier run without `:feed`'s rework (feed
under Robolectric) gave 17.0 s against 438.0 s (25.8x). With 16 CPU burners from 40 s in, `krisprRun` took
866.1 s.

Whether each shape reproduces its issue:

1. Size skew, #41: **yes**. `:settings`, `:session`, `:status` and `:units` start 280-370 s in, although
   `:checkout` finished at 35 s: they wait for the build-wide JVM slots that `:catalog`, `:pricing` and
   `:mocks` hold for their whole queues.
2. Robolectric module of plain-covered mutants: **no** (handled). `:checkout` runs 108 of 109 mutants in 9
   warm workers, 23 s.
3. Kill-heavy Robolectric module, #43: **yes**. `:settings` starts 18 workers for 18 reused mutants, one per
   kill, 85-120 s for 19 mutants.
4. Fails in a reused JVM, #43: **yes**. "4 tests fail in a reused JVM"; 41 of `:session`'s 56 mutants run in
   fresh JVMs.
5. Carry-over, confirmSurvivors: **yes**. `:pricing`: "9 survivors re-checked in fresh JVMs, 5 changed" in every
   run (`MoneyFormat.compute` mutants hidden by the warm pool); without `confirmSurvivors` those 5 are
   reported as survivors.
6. MockK, #36: **no** by default (relaxed, function-type and enum mocks record and run in reused sandboxes);
   **yes** with `-Pplayground.issue36=true` (a second sandbox at SDK 34 plus `forkEvery = 1`):
   `testDebugUnitTest` passes and `krisprRecord` fails with "Can't instantiate proxy for class
   kotlin.Function1" caused by a duplicate class definition of `kotlin.jvm.functions.Function1$Subclass0`.
7. Slow tests under load, #42: **yes**, under `--stress 16 --stress-delay 40`. `Product.kt:17` "survived in a
   reused Robolectric sandbox, then TIMED_OUT in a fresh JVM" (11.3 s against a 10-11.3 s limit), and
   stress moved `:catalog`'s fresh JVMs from 21 to 75 and `:mocks`' from 7 to 28. `:feed`'s own CPU-bound
   mutants do not time out: C1-only forks make the recording 3x slower than a warm worker's run (#44).
8. Exhaustive `when`, #35: **no** (canary passes). `:status` records and runs normally, 33 of 37 killed.


## Robolectric levers: keep after a kill, routing, C1 workers (#43)

All on sample-android `:lib` (19 mutants, 6 reached by Robolectric tests), `--max-workers=4`,
`maxConcurrentJvms=4`, a 30 s minimum timeout (the shared host's load average was 25 to 40), and
`krispr.history=false`. CPU is the sum of every krispr JVM's own CPU time, from the phases line.
Verdicts were identical to `robolectricReuse=fresh` with `reuseJvms=false` (20 fresh JVMs) in
every run.

| Run | Fresh JVMs | Workers | CPU | Wall |
|---|---|---|---|---|
| sandbox, retire the worker after a Robolectric kill (before) | 2 | 9 (5 retired) | 60.5 s, 62.9 s | 34 s, 40 s |
| sandbox, keep it when a health check passes | 2 | 5 (5 kept) | 46.3 s, 45.9 s | 29 s, 29 s |
| fresh, every mutant in a fork (before) | 20 | 0 | 39.0 s | 36 s |
| fresh, mutants only plain tests reach go to workers | 7 | 3 | 32.2 s | 30 s |
| sandbox, workers on the tiered JIT (before) | 2 | 5 | 47.4 s, 46.6 s | 36 s, 31 s |
| sandbox, workers on C1 only | 2 | 5 | 31.4 s, 28.9 s | 31 s, 27 s |

- **Health check instead of retiring.** A worker whose Robolectric test killed a mutant reruns the
  failed tests with no mutant active; if they pass, the sandbox is sound and the worker stays. Each
  of the 5 kills kept its worker, and 4 worker starts (and their sandboxes) were saved.
- **Routing.** The recording notes whether each test set up a Robolectric sandbox. A mutant that only
  plain tests reach runs in a reused JVM without a sandbox, in either `robolectricReuse` mode. In
  fresh mode that took 13 of 19 mutants out of forks.
- **C1 for workers.** Forks already ran C1 only. Workers ran the tiered default, and on a worker's
  handful of short test runs C2's compile threads cost more than they saved: reused-JVM CPU fell from
  38 s to 22 s. `forkJvmTuning = "off"` restores the tiered JIT for both forks and workers.

### Why tests fail the reuse check (#43)

The check runs each test class twice in one worker with no mutant active; a test that fails the
second round is "unsafe" and its mutants fork. The causes are now written to
`build/krispr/logs/check-failures.tsv`, and the first five are logged. On the playground's `:session`
module, the 4 rejected tests fail only when run a second time in the same JVM, from state the tests
keep themselves: a dependency graph that refuses a second install ("already installed"), a static
counter, and the looper clock. Krispr can't reset those safely.
A mutant that reaches both unsafe and safe tests now runs its safe tests in a worker first; a kill
there stands, and anything else still forks. On `:session`, 41 mutants reach only unsafe tests, so
none were killed that way: 42 forks, 192 s CPU.

### AppCDS for forks: measured, not adopted

A dynamic AppCDS archive (`-XX:ArchiveClassesAtExit` on a baseline fork, then
`-XX:SharedArchiveFile`) made a `:lib` Robolectric fork faster by hand, at load 11. Wall time went
from 3.9 s to 3.45 s and user CPU from 3.2 s to 2.6 s (4 runs each). Krispr doesn't use it because:

- **Jarred class directories.** JDK 21 refuses to dump with non-empty directories on the class path,
  so the class and resource directories would need jarring. Tests would then see `jar:` resource
  URLs.
- **Disk.** The archive is 67 MB per module.
- **Sandbox classes break.** Robolectric's own sandbox classes can break under the archive. A test
  using a native-mode shadow failed with `IncompatibleClassChangeError: NativeInput and
  NativeInput$MotionEvent disagree on InnerClasses attribute`, and passed without the archive.
- **Payback.** Each module would need a validation fork, which only pays back beyond about 8 forks.
