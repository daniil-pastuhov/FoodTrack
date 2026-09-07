# Performance measurement

Three complementary measurements, because none of them is trustworthy on its own.

| | What it measures | Strength | Weakness |
|---|---|---|---|
| **Macrobenchmark** (`:macrobenchmark`) | Real startup time and frame timings on a connected device | Closest to what a user feels | Noisiest, especially on an emulator |
| **JVM microbenchmark** (`androidApp/src/test/.../perf/`) | `PantryStateHolder`'s filter/sort cost in isolation | Deterministic, isolates one change | Blind to anything Compose does |
| **Compose compiler report** | Per-composable skippability, per-class stability | Exact and reproducible | A proxy, not a timing |

## Running

```bash
./scripts/perf.sh baseline                              # before a change
./scripts/perf.sh optimized                             # after it
python3 scripts/perf_report.py baseline optimized > perf/RESULTS.md
```

Each run writes `perf/<label>/` containing `macrobenchmark.json`, `jvm.tsv`,
`compose-composables.{txt,csv}` and `compose-classes.txt`. Individual pieces can also be run
directly:

```bash
./gradlew :androidApp:testDebugUnitTest --tests "*PantryStateHolderPerfTest*" -Pperf.label=x
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR,UNLOCKED,LOW-BATTERY
```

## How the setup works, and why

**A dedicated `benchmark` build type** (`androidApp/build.gradle.kts`) — `initWith(release)` so
it is non-debuggable, since a debuggable build measures the interpreter rather than the app.
Minification is deliberately **off**: these benchmarks compare two revisions of our own source,
and R8's output shifts enough between builds to swamp that signal. Signed with the debug key so
it installs without the git-ignored release keystore.

**`<profileable android:shell="true">`** is declared explicitly in
`androidApp/src/benchmark/AndroidManifest.xml`. The `isProfileable = true` build-type flag does
*not* emit it under AGP 9, and without the element `FrameTimingMetric` silently degrades to
reporting `frameCount` and nothing else — no frame durations, no overrun. `NOT-PROFILEABLE` is
therefore **not** in the suppressed-errors list, so a regression here fails loudly.

**Deterministic data.** `shared/.../perf/PerfDataset.kt` generates items from a fixed seed, and
`androidApp/src/benchmark/.../PerfSeedReceiver.kt` writes them into Room before every iteration
via `am broadcast -W`. Without this, whatever happened to be on the device would move the
numbers more than the code change does. The receiver lives in the `benchmark` source set, so it
is compiled into that build type only — it does not exist in debug or release builds.

**Permissions are granted up front** (`PerfSeed.grantAppPermissions`). Otherwise
`MainActivity`'s launch-time POST_NOTIFICATIONS dialog covers the dashboard and the measured
frames are the dialog's.

**`CompilationMode.Full()`** removes JIT warm-up luck, which otherwise dominates the run-to-run
spread on an emulator.

## Reading the numbers

- `frameDurationCpuMs` **P90/P99** matter more than P50. Most frames are cheap in any version;
  jank lives in the tail.
- `frameOverrunMs` is how much *over* its deadline a frame ran — negative is headroom, positive
  is a missed frame. This is the single most user-visible number here.
- The JVM tests report two different things on purpose. `mainThread.*` is how long an
  interaction blocks the thread that triggered it (the UI thread in the real app), and collapses
  when work moves to a background dispatcher. `pipeline.*` is the total algorithmic work, and
  only improves from actual algorithmic changes. Reporting one without the other would
  misrepresent the result.
- The Compose report's headline is **unstable parameter count**, not the skippable/non-skippable
  split. With strong skipping (default since Kotlin 2.0.20) nearly everything is reported
  "skippable", but a parameter whose type is unstable is compared by *instance identity* rather
  than `equals()`. Every database emission rebuilds those objects, so the identity check always
  fails and the composable recomposes regardless of being marked skippable.

## Caveats

The results committed here were taken on an **emulator** (`sdk_gphone64_arm64`, API 36, 4 cores,
unlocked clocks). Absolute values are not representative of real hardware and should not be
quoted as such. The before/after *comparison* is meaningful because both runs use the same
emulator and the same seeded dataset, so the systematic error cancels.
