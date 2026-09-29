# Robolectric cold-start spike

Why does a fresh Robolectric JVM spend 4 to 5 seconds before it runs the first test body, and what can Krispr do
about it? Krispr forks a fresh JVM per batch of mutants. [perf.md](perf.md) shows the fresh sandbox is ~86% of a
mutant's time, and a reused sandbox costs 12 ms. So this cold-start cost is the dominant per-fork cost for Android
modules.

**Short answer.** About 40% of the cold start is not sandbox building. On SDK 35 (V), Robolectric
eagerly loads its native runtime in every JVM, even when a test only needs SQLite. That load unzips ~213 MB of
fonts, ICU data and a 17 MB dylib into a brand-new temp directory, dlopens it (macOS then scans the fresh
binary), and deletes it all at exit. Conscrypt does the same with its own dylib. Fixing those two, plus CDS and C1,
takes the measured cold JVM from 4.1 s to 2.0–2.1 s, and process CPU from 6.5 s to 1.8–2.0 s. The remaining ~2 s
is real sandbox work: defining ~9,500 classes, ~1,800 invokedynamic bootstraps, resource tables, and application
creation.

## Setup

- Robolectric 4.17, `sdk=35`, preinstrumented `android-all-instrumented-15-robolectric-13954326-i7`,
  `nativeruntime-dist-compat-1.0.19`.
- JBR 21.0.6 (plus GraalVM 25.0.1 for the AOT-cache test). M1 Pro, 10 cores, macOS.
- Subject: `sample-android/lib` `CartTextTest` (2 tests), run as a plain `java` process with the exact
  `testDebugUnitTest` classpath and JVM args. The classpath was dumped by a Gradle init script that writes
  `t.classpath.files.join(":")` in a `Test` task's `doFirst`.
- **Harness:** a JUnitCore main that timestamps the start and end of each test from JVM uptime. Robolectric's
  `PerfStatsReporter` SPI provides the internal phase timings.
  - Configurations run interleaved (A, B, C, A, B, C, …) so that load from other jobs lands on all of them alike.
  - Each result is the median of 5 cold JVMs; process CPU and the load average are recorded too.
  - The load average swung between 5 and 300 during the day because this is a shared machine. Deltas between
    interleaved configs are the reliable signal; absolute walls are not.
  - `scripts/robolectric-coldstart-bench.sh` is a self-contained version of the harness.
- **Profiler:** async-profiler 4.1, in wall and cpu modes on the Robolectric SDK thread, plus JFR for class-load
  counts. Stacks were classified by phase and by leaf activity (inflate, file write, define/verify/link, indy
  linkage, ASM).

## Where the time goes

Baseline: 5 interleaved rounds, load 47 → 8. Median wall is **4127 ms** and process CPU is **6.5 s**.

| Segment | Time | Scope |
|---|---|---|
| JVM start → `main` | ~55 ms | per JVM |
| `main` → first test start (runner, plugin discovery, ShadowMap, sandbox classloader) | ~575 ms | per JVM |
| First test (Robolectric `initialization` 3227 ms, of which `loadNativeRuntime` 1788 ms, `installAndCreateApplication` 654 ms) | ~3.4 s | mostly per JVM, see below |
| Second test | 61 ms | per test |
| Exit (TempDirectory shutdown-hook deletion) | ~100 ms | per JVM |
| JIT compiler threads | ~3.0 s CPU | per JVM (CPU, off the critical path) |

Buckets on the SDK thread come from the wall profile. The first test totals ~3.1 s sampled.

| # | Bucket | ms | Scope | Notes |
|---|---|---|---|---|
| 1 | Copy fonts out of the native-runtime jar | **1060** | per JVM | 793 ms inflate, 177 ms write. ~200 MB of it is CJK fonts |
| 2 | Conscrypt JNI load | **~550** | per JVM | A fresh random-named dylib; macOS scans it inside `dlopen`. async-profiler only sees ~33 ms. Measured as the delta of `conscryptMode=OFF` |
| 3 | `System.load(libandroid_runtime)` + `JNI_OnLoad` | **331** | per JVM | ~200 ms of it is the macOS first-sight scan of a freshly written dylib |
| 4 | Runner and bootstrapping (ShadowMap, config, sandbox) | 311 | per JVM / per sandbox | |
| 5 | Application create (`installAndCreateApplication` minus parse) | 285 | per sandbox, then per test | |
| 6 | Parse package (manifest) | 217 | per sandbox | |
| 7 | BouncyCastle provider construction | 183–211 | per JVM | `new BouncyCastleProvider()` in a static initializer, ~700 classes from a signed jar |
| 8 | `setUpApplicationState` other | 187 | per sandbox | |
| 9 | reset / tearDown | 177 | per test (first one is cold) | |
| 10 | Binary framework resources (arsc) | 148 | per sandbox | |
| 11 | ICU data copy | 131 | per JVM | 25 MB |
| 12 | Hyphenation data copy | 23 | per JVM | |

The same wall time broken down by leaf activity:

| Activity | ms |
|---|---|
| zip inflate (almost all native-runtime extraction) | ~1200 |
| class define / verify / link (~9,500 classes) | ~830 |
| invokedynamic / MethodHandle linkage (~1,800 bootstraps, ~2,950 LambdaForm hidden classes) | ~430 |
| ASM (preinstrumented jars, so only ClassDetails parsing) | ~60 |

### Per JVM, per sandbox, per test

- **Per JVM (the whole native-runtime load, Conscrypt, BouncyCastle, JIT warm-up, and loading of non-sandboxed
  classes):** ~2.3 s of the 4.1 s. None of it depends on the SDK sandbox's contents. It is redone only because
  the JVM is new.
- **Per sandbox (android-all class definition, framework resources, ShadowMap, parse package):** ~1.2 s.
  Krispr's sandbox reuse already amortizes this within a fork.
- **Per test (application create, reset):** 6–60 ms warm. The second test takes 61 ms and a reused sandbox takes
  12 ms (perf.md).

## Tweaks measured

Final matrix: 5 interleaved rounds, load 47 → 8. Deltas are median wall against the baseline in the same rounds.
Some rows also have a second matrix of 5 rounds at load 12–30; its deltas are in brackets.

| Config | Δ wall (ms) | CPU (s) | Semantics | Notes |
|---|---|---|---|---|
| baseline | 0 (4127) | 6.5 | — | |
| `-XX:TieredStopAtLevel=1` (C1 only) | −71 | 3.7 | none | Halves JIT CPU. The effect on long test bodies is untested |
| `-XX:-BytecodeVerificationRemote` | −179 | — | none, but a diagnostic flag | Not recommended: it skips verification of the classes being mutated |
| `-XX:+UseSerialGC` | +192 | — | none | Worse |
| AppCDS (dynamic archive of a baseline run) | −99 (min −504) [−629] | 5.4 | none | Maps ~4,480–5,600 of 9,500 classes, **including sandboxed android.\* classes** |
| AppCDS + C1 | −417 | 3.4 | none | |
| **Persistent native-runtime cache (prototype patch)** | **−1306** [−1557] | 5.4 | none | `loadNativeRuntime` 1788 → 250–377 ms |
| patch + CDS + C1 | **−1993 (2134 ms)** | **2.0** | none | |
| `robolectric.sqliteMode=LEGACY` | −1564 [−1801] | — | changes SQLite implementation | Skips the native runtime entirely |
| sqlite LEGACY + CDS + C1 | **−2129 (1998 ms)** | **1.8** | as above | |
| `sdk=34` | −1574 | — | changes SDK under test | The native runtime is lazy below V |
| `sdk=33` | −1507 | — | changes SDK under test | |
| `robolectric.conscryptMode=OFF` | −520 (first test) | — | drops Conscrypt; BouncyCastle only | |
| `robolectric.lazyApplication=true` | +90 | — | app created on first access | Moves app creation, doesn't remove it |
| `robolectric.offline=true` + `dependency.dir` | +79 | — | none | Resolution is already cheap |
| `usePreinstrumentedJars=false` | +939 | 9.9 | none | Confirms that preinstrumentation is doing its job |
| JDK 25 (GraalVM 25.0.1) | +145 | — | JDK change | |
| JDK 25 + AOT cache (`-XX:AOTCache`) | −370 | — | JDK change | 4,752 classes from the cache. The training run reported a test failure; runs using the cache passed |

A separate `dlopen` micro-test found:

- **Conscrypt's dylib:** a freshly written copy takes 442/128/104/100 ms to load; a copy the OS has already seen
  takes 1 ms.
- **`libandroid_runtime.dylib`:** 455/253/220 ms fresh against 6 ms seen.

This per-file cost is macOS-specific and does not appear in async-profiler's samples.

### What works on this JDK/macOS and what doesn't

- **Works now:**
  - The flags above.
  - Dynamic AppCDS via `-XX:ArchiveClassesAtExit`, or `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=…`,
    which dumps on first use for ~2–3 s once and then maps every later run.
    - The archived classpath prefix must be jars only; a non-empty directory on it fails the dump.
    - Directories appended *after* the archived prefix at run time are fine (4,479 classes still shared).
    - HotSpot matches custom-loader classes by CRC of the defined bytes, which is why Robolectric's sandboxed
      classes are archived at all.
    - Under Krispr's mutation schemata, every fork of a module sees identical class bytes, so one archive per
      module serves all its forks.
  - The native-runtime cache patch.
  - The JDK 25 AOT cache.
- **Theoretical only:**
  - **CRaC:** JBR has no CRaC flags, and macOS has no CRIU. It is Linux-only, with a CRaC JDK such as Azul
    Zulu CRaC.
  - **Fork server:** HotSpot cannot fork a running JVM safely, so a zygote would have to be a long-lived JVM
    that hands out sandboxes, not forked processes.
  - **Leyden AOT with linked indy call sites:** as far as I can tell, JDK 25's AOT linking of indy call sites only
    covers the built-in loaders, not Robolectric's sandbox loader, so the ~430 ms indy bucket stays. This wasn't
    verified beyond the class counts.

## Design critique

Paths are relative to the Robolectric repo at tag `robolectric-4.17`.

1. **The native runtime is extracted from scratch into a new temp dir in every JVM, then deleted.**
   - `nativeruntime/src/main/java/org/robolectric/nativeruntime/DefaultNativeRuntimeLoader.java:221` creates
     `new TempDirectory("nativeruntime")`.
   - Its deletion is registered as a shutdown hook at `utils/src/main/java/org/robolectric/util/TempDirectory.java:61,71,83-92`.
   - Everything is copied into it:
     - ICU (`DefaultNativeRuntimeLoader.java:289-316`)
     - every font file, found by a zipfs walk (`:323-360`, walk at `:340`)
     - hyphen data (`:367-400`)
     - the dylib itself (`:404-409`)
   - The inputs are immutable, versioned jar contents, so this is a textbook content-addressed cache that is
     never cached. It costs ~1.2 s of inflate/write and 213 MB of disk churn per JVM. On macOS, a fresh dylib
     path also pays the code-signing/assessment scan on every `dlopen`.
2. **Fonts are copied even when graphics is not native.**
   - `DefaultNativeRuntimeLoader.java:223-224` says "Only copy fonts if graphics is supported, not just SQLite",
     but the guard only checks SDK ≥ O. With `graphicsMode` defaulting to LEGACY
     (`robolectric/src/main/java/org/robolectric/plugins/GraphicsModeConfigurer.java:19`), the 1.06 s font
     bucket, then `Typeface.loadPreinstalledSystemFontMap` (`:253`) and `Hyphenator.init` (`:258`), are wasted
     for the default configuration.
   - This looks like a plain bug against the comment's stated intent.
3. **The load is eager on V+ in every JVM, even for SQLite-only use.**
   - `robolectric/src/main/java/org/robolectric/android/internal/AndroidTestEnvironment.java:159-163` loads
     eagerly because the runtime "does not support being lazy-loaded" on V. A second eager path is at `:325-329`.
   - `shouldLoadNativeRuntime` (`:727-730`) is true whenever SQLite is NATIVE, which is the default
     (`plugins/SQLiteModeConfigurer.java:23`).
   - The result is that every Robolectric test on SDK 35 pays for the font and graphics runtime whether it
     touches SQLite or not. SDK 34 and below don't pay it, which accounts for the −1.5 s `sdk=34` delta.
4. **Conscrypt's JNI library is re-extracted to a random temp name on each JVM.**
   - This is in Conscrypt's `NativeLibraryLoader`, not Robolectric: it tries `loadFromWorkdir` before
     `loadLibrary`, with a random file name.
   - Its `org.conscrypt.native.workdir` and `deleteLibAfterLoading` knobs can't make the name stable. The 4.17
     wiring is at `AndroidTestEnvironment.java:176-211`.
   - On macOS each JVM therefore pays a 100–450 ms first-sight scan.
5. **BouncyCastle is built eagerly in a static initializer.**
   - `AndroidTestEnvironment.java:113` constructs `new BouncyCastleProvider()` whether or not a test does any
     crypto: ~0.2 s and ~700 classes.
   - Those classes come from a signed jar, so CDS can't archive them either.
6. **Sandbox class loading is sound but inherently heavy.**
   - `sandbox/src/main/java/org/robolectric/internal/bytecode/SandboxClassLoader.java` (a `URLClassLoader`,
     `:30`, `:68`) reads bytes (`:147-166`), builds `ClassDetails`, checks whether instrumentation is needed,
     and then calls `defineClass` (`~:215-236`).
   - With preinstrumented jars, ASM is negligible (~60 ms). The cost is the JVM's define/verify/link of ~9,500
     classes (~830 ms) and ~200 ms of jar reads.
   - Nothing is recomputed that could be cached; CDS attacks exactly this cost from outside.
7. **Invokedynamic interception pays at link time.**
   - Each instrumented call site bootstraps through
     `sandbox/src/main/java/org/robolectric/internal/bytecode/InvokeDynamicSupport.java:74-138` and
     `ShadowWrangler.java:152`, costing ~1,800 bootstraps and ~2,950 LambdaForm hidden classes (~430 ms).
   - This is by design (fast after linking) and can't be archived by CDS or the JDK 25 AOT cache for custom
     loaders.
8. **Dependency resolution takes a global lock every JVM.**
   - `plugins/maven-dependency-resolver/.../MavenDependencyResolver.java:80-118` takes `~/.robolectric-download-lock`
     even when `MavenArtifactFetcher.java:67` then only checks that the file exists.
   - This is cheap here (offline mode saved nothing), but it serializes parallel forks briefly.
9. **These parts are already fine:**
   - preinstrumented jars (+0.94 s wall and +3.4 s CPU without them)
   - resource and ShadowMap construction
   - the per-test reset, which is ~60 ms cold and 12 ms warm
   - `lazyApplication`, which only moves the cost

## Recommendations

Savings are per fork (per cold JVM), measured on this Mac unless marked as estimated. Recommendations are ranked
within each group.

### (a) Krispr-only: JVM flags, CDS, config

| # | Change | Saving per fork | Effort | Risk |
|---|---|---|---|---|
| a1 | **Per-module AppCDS for Robolectric forks.** Add `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=<build>/krispr/<module>.jsa`, with a jar-only classpath prefix (jar the class dirs, or put the dirs after the jars) | −0.1 to −0.6 s wall, −1.1 s CPU. More when combined with C1 | S (a day) | Low. A stale archive is ignored by HotSpot with a warning; key the file by classpath hash. JDK ≥ 19 for AutoCreate |
| a2 | **`-XX:TieredStopAtLevel=1` for Robolectric forks** | −0.1 s wall alone, −2.8 s CPU. With a1, −0.4 s wall. The CPU matters most when forks run in parallel | XS | Low–medium. Long-running test bodies may run slower; measure on a big suite before making it the default |
| a3 | **Opt-in `robolectric.sqliteMode=LEGACY` (and `conscryptMode=OFF`)** as a documented Krispr setting for projects that don't depend on native SQLite or Conscrypt | −1.6 s (+ −0.5 s) | XS | Medium: this changes behaviour under test, so the project must opt in. Never default it |
| a4 | Ship the persistent native-cache loader as a classpath override, pinned to the Robolectric version | −1.3 to −1.6 s | S | Medium: it shadows a Robolectric class, so it must be version-gated. Prefer b1 upstream and use this only as a bridge |

### (b) Robolectric patch / upstream PR

| # | Change | Saving per fork | Effort | Risk |
|---|---|---|---|---|
| b1 | **Content-addressed persistent extraction in `DefaultNativeRuntimeLoader`.** Extract to `tmpdir/robolectric-nativeruntime-cache/<hash of the native jar and build.prop URLs>`, copying each file once via tmp-file + atomic move and never deleting it. The dylib path is then stable, so macOS scans it once. Prototyped: 204 MB cache, both tests pass | **−1.3 to −1.6 s** wall on macOS, ~−1.1 s estimated on Linux (inflate/write only); −213 MB of I/O per JVM | S (~100 lines + test) | Low. Concurrent forks are handled by atomic moves; needs a cleanup policy for old keys |
| b2 | **Copy fonts and load the font map only when `graphicsMode=NATIVE`**, as the comment at `:223-224` intends | −1.0 s when b1 is absent; small with b1 | XS | Low–medium: SQLite-only tests must not touch Typeface (verify against Robolectric's suite) |
| b3 | **Conscrypt: load from a stable content-hashed path** (a patch to Conscrypt's loader, or have Robolectric pre-extract it and call `System.load`) | −0.3 to −0.5 s on macOS | S | Low |
| b4 | Construct `BouncyCastleProvider` lazily (on first provider lookup) | ~−0.2 s | S | Low |
| b5 | Skip the global download lock when the artifact already exists | ~0 serial; less contention in parallel | XS | Low |

### (c) Re-architecture

| # | Change | Saving per fork | Effort | Risk |
|---|---|---|---|---|
| c1 | **Long-lived Robolectric worker JVM that reuses sandboxes across mutant batches**, reset between batches, instead of a cold JVM per fork. This builds on Krispr's existing sandbox reuse (12 ms warm) | Almost all of ~4 s (≈−3.9 s); leaves ~12–60 ms per batch | L | Medium–high: isolation between mutants rests on Robolectric's reset and on Krispr's schemata switch (static state must reset); a crashed or timed-out mutant must kill the worker |
| c2 | CRaC checkpoint of a warmed, sandbox-built JVM, restored per fork | Estimated −3.5 s | M–L | High: Linux-only, needs a CRaC JDK, and native runtime file handles and threads complicate checkpointing. Not testable on this Mac |
| c3 | JDK 25+ AOT cache (Leyden) per module in place of a1 | −0.4 s measured | S once on JDK 25 | Medium: the training-run failure is unexplained; custom-loader indy isn't covered |

**Top 3 overall:**

1. **b1**, the persistent native cache: −1.3 to −1.6 s per fork, small effort. Upstream it, and bridge it with a4
   if upstream is slow.
2. **a1 + a2**, CDS + C1: −0.4 s wall and −3.1 s CPU per fork, with no semantic change, entirely inside Krispr.
3. **c1**, a warm worker JVM: removes nearly the whole cold start. It is the only option that attacks the ~2 s
   floor left after 1 and 2 (class definition, indy linkage, resources and app creation are inherent per
   sandbox).

## Open questions

- C1-only on larger suites: does it cost more in long test bodies than it saves in start-up?
- The CDS classpath shape in real Krispr forks: must the class dirs be jarred, or is appending them after the jars
  enough for Krispr's fork classpath? The dirs-appended layout measured fine here.
- The JDK 25 AOT training run reported a test failure (the runs using the cache passed); this is not understood.
- Linux numbers: the macOS dylib scan is absent there, so b1/b3 should save less (inflate/write only). CRaC can
  only be tried there.
- Whether b2 is safe: SQLite-only use on V may still reach Typeface/Hyphenator code paths that expect the font
  map. Robolectric's own suite would tell.
