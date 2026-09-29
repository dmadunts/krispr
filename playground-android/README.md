# playground-android

A small multi-module Android project whose only job is to reproduce, in a few minutes, the whole-app
behaviour of Krispr on a large Robolectric-heavy app: slot starvation between modules, sandbox reuse that
degenerates to fresh JVMs, carry-over between mutants in a reused sandbox, MockK proxies in the recording
run, and timeouts under host load. All code here is written for the playground.

Run it through the benchmark, from the repo root:

```
scripts/playground-bench.sh              # unit-test baseline, then krisprRun across all modules, one table
scripts/playground-bench.sh --verify     # also krisprRun with robolectricReuse=fresh and diff the verdicts
scripts/playground-bench.sh --stress 20 --stress-delay 60   # CPU burners during the Krispr run (#42)
scripts/playground-bench.sh -- -Pkrispr.confirmSurvivors=false   # extra -P flags go to every krisprRun
```

or by hand: `cd playground-android && ../gradlew --max-workers=4 krisprRun -Pkrispr.maxConcurrentJvms=4 -Pkrispr.history=false`.
It needs `local.properties` with `sdk.dir` (the bench copies `sample-android`'s), JDK 21, and Robolectric's
`android-all` jars for SDK 35 (and 34 for the #36 opt-in). `gradle.properties` turns on
`org.gradle.parallel`, so modules run their Krispr tasks side by side as in a real app.

## Shapes

| # | Shape | Module (classes) | Issue |
|---|---|---|---|
| 1 | Size skew: one big Robolectric module and eight small ones, mixed Robolectric and plain JUnit | `:catalog` (~225 mutants; Robolectric `CatalogText`, `CatalogPresenter`, `CatalogFilter` tests, plain `PriceRules`, `Pagination`, `SearchIndex` tests) against `:checkout`, `:settings`, `:session`, `:pricing`, `:mocks`, `:feed`, `:status`, `:units` (19-110 each) | #41: the big module's threads hold every build-wide JVM slot for their whole queue; the bench's start offsets show the small modules waiting |
| 2 | A Robolectric module whose mutants are mostly covered only by plain JUnit tests | `:checkout` (`CheckoutTextTest` is Robolectric; `CardValidatorTest`, `ShippingTest`, `AddressValidatorTest` are plain) | #31/#43: the module is classed as Robolectric, but nearly all its mutants should run in warm workers with no sandbox cost |
| 3 | High kill rate, every test Robolectric | `:settings` (`SettingsStore` over `SharedPreferences`) | #43: each Robolectric kill retires the worker, so "reused" workers serve one mutant each (workers started ≈ mutants) |
| 4 | Tests that pass in a fresh JVM and fail when a reused one runs them again | `:session`: `AppGraph` (a DI holder whose `install` throws the second time, from `@BeforeClass`), `SessionManager` (a process-wide counter), `RefreshThrottle` (static state against Robolectric's per-test main-looper clock), `AuditLog` (a `java.util.logging` handler: JDK state a reused plain JVM keeps) | #43: the worker check marks these tests unsafe and their mutants fall back to fresh JVMs |
| 5 | Carry-over: a process-wide pool a run fills, which hides later mutants in a reused sandbox | `:pricing` (`MoneyFormat`'s canonical-string pool; `Rates`' `by lazy` tables, which Krispr treats as caches and skips by default) | #43 / `confirmSurvivors`: mutants of `compute()` survive in a warm sandbox and die in a fresh JVM; with `-Pkrispr.confirmSurvivors=false` those survivors are reported |
| 6 | MockK: relaxed mocks, mocks of function types and of an enum, in a Robolectric module | `:mocks` (`NotifierTest`, `BillingTest` under Robolectric, `TierTest` plain) | #36, opt-in: `-Pplayground.issue36=true` adds `LegacySdkNotifierTest` (a second sandbox, SDK 34) and `forkEvery = 1`; `testDebugUnitTest` passes and `krisprRecord` fails with "Can't instantiate proxy for class kotlin.Function1" (duplicate class definition of `kotlin.jvm.functions.Function1$Subclass0`) |
| 7 | Slow but legitimate tests: CPU-bound, about a second each in Krispr's recording | `:feed` (plain JUnit: `FeedRanker` simulates spread through a follower graph; `FeedSigner` hashes ~200 MB per signature with SHA-256) | #42: `--stress N` loads the host after the recording measured the tests. Krispr's forks run C1-only (`-XX:TieredStopAtLevel=1`), so these tests take 1.4 s in the recording against 40 ms under Gradle, and the 10 s timeout floor leaves mutant runs (0.3-0.5 s) a wide margin |
| 8 | Exhaustive `when` over an enum and a sealed interface, no `else` | `:status` (`OrderFlow`) | #35 canary: the recording must not hit `NoWhenBranchMatchedException` |

`:units` is a plain-JUnit module that only adds to the mix of runners.

## Notes

- CPU-bound tests are much slower under Krispr than under Gradle: forks run C1-only, and the recording adds
  coverage probes. `FeedRanker`'s tests take 10-20 ms under Gradle and 300-500 ms in the recording;
  `FeedSigner`'s take 40 ms under Gradle and 1.4 s in the recording. Tests over `slowTestThresholdMs` (2 s) in
  the recording are excluded, so keep each one well under it.
- `:session`'s tests pass under `testDebugUnitTest` because each class runs once per JVM there; they fail
  only when the same JVM (and, for the Robolectric ones, the same sandbox) runs them a second time.
- The numbers measured on this project are in `docs/perf.md` ("Playground baseline").
