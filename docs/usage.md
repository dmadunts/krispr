# Using Krispr

Running it, what it reports, and the run modes. Setup for your own project is in
[setup.md](setup.md); tuning what gets mutated and how fast it runs is in [tuning.md](tuning.md).

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

## Reports

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
  API calls itself; see [diff-mode.md](diff-mode.md) for a sample workflow.

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
edits and untracked files included, renames followed. Lines whose only change is whitespace (re-indenting,
a formatter run) do not count. It is the intended use on pull requests: a short list of survivors on the
lines under review.

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

See [diff-mode.md](diff-mode.md) for the `diff.md`/`diff-annotations.json` outputs and a sample
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
