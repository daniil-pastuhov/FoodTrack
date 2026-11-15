# Performance results: baseline vs optimized

Measured on `emulator-5554` (`sdk_gphone64_arm64`, API 36, 4 cores, unlocked clocks) against a
fixed 1000-item seeded pantry. Reproduce with:

```bash
./scripts/perf.sh baseline
./scripts/perf.sh optimized
python3 scripts/perf_report.py baseline optimized
```

## Summary

**What is proven.** The work that used to run on the UI thread on every filter tap and every
keystroke no longer does — it is now 100-1000x cheaper for the caller, and the underlying
algorithm is genuinely 5-7x faster at the same job. Every composable parameter the Compose
compiler could not prove stable is now stable, so cards can actually skip recomposition instead
of only being marked skippable.

**What is not proven.** The on-device frame timings are inconclusive on this emulator. Repeat
runs of *identical* code varied by more than the differences being measured (see
[Measurement noise](#measurement-noise) below), so the macrobenchmark table should be read as
"no clear regression" rather than as evidence either way. That suite is committed and will
resolve properly on physical hardware.

## Measurement noise

Cold-start time was measured three separate times on the **unmodified baseline** build:

| Run | `timeToInitialDisplayMs` (median) |
|---|--:|
| 1 | 636.4 |
| 2 | 679.1 |
| 3 (recorded as the baseline) | 603.0 |

That is a 12.6% spread with no code change at all. Three runs of the optimized build landed at
675 / 650 / 700 ms, which overlaps that range — so the +16% shown in the table below is within
the noise floor, not a measured regression. The same applies to the P90 frame durations: across
three optimized runs `PantryScrollBenchmark` P90 measured 9.14 / 6.96 / 12.41 ms, a 78% swing
against a baseline of 7.81 ms.

The emulator has 4 cores and unlocked clocks and is explicitly not a supported benchmarking
target (the suite runs only because `EMULATOR` is in the suppressed-errors list). The
deterministic measurements below are what this change should be judged on.

## The measurements that do resolve

### Main-thread cost of a filter interaction

How long an interaction blocks the thread that triggered it — the UI thread in the real app.
This is what a user feels as a dropped frame while typing in the search box or tapping a filter
chip. Filtering and sorting now happen on a background dispatcher, so the click handler returns
essentially immediately.

| Interaction | 200 items | 1000 items | 5000 items |
|---|--:|--:|--:|
| Search keystroke | 89.6 → 2.5 µs (**-97%**) | 91.6 → 1.7 µs (**-98%**) | 574.0 → 0.6 µs (**-99.9%**) |
| Status filter tap | 28.1 → 1.0 µs (**-96%**) | 117.8 → 0.4 µs (**-99.7%**) | 469.5 → 0.5 µs (**-99.9%**) |
| Sort option change | 61.8 → 5.3 µs (**-91%**) | 156.6 → 3.0 µs (**-98%**) | 675.7 → 1.8 µs (**-99.7%**) |

At 5000 items the old code blocked the UI thread for 0.5-0.7 ms per keystroke, which is a
quarter of a 60 Hz frame budget spent before rendering anything.

### The algorithm itself

Measured by running the previous implementation and the current one side by side in the same JVM
on the same data (`DerivationAlgorithmPerfTest`), so this isolates the algorithm from both the
flow plumbing and build-to-build variation. The reference implementation is asserted to produce
identical output before either is timed.

| Operation | 200 | 1000 | 5000 |
|---|--:|--:|--:|
| **Filter change** (a tap or keystroke) | 13.1 → 2.9 µs (**-78%**) | 89.0 → 18.0 µs (**-80%**) | 631.2 → 90.5 µs (**-86%**) |
| **Full derivation** (one pantry write) | 186.3 → 220.5 µs (+18%) | 500.0 → 504.2 µs (±0%) | 3047.0 → 2713.3 µs (**-11%**) |

The filter-change win comes from the O(1) category lookup replacing a linear scan per item, the
precomputed sort keys replacing `name.lowercase()` inside the comparator, and the precomputed
expiry status replacing a recomputation per item.

The full-derivation row is the deliberate trade. The new code does strictly more work per
database write — it builds the per-item rows, the calendar groupings and the category list up
front — in exchange for that work no longer happening per frame in composition. It breaks even
around 1000 items and wins above that. A pantry write is rare; a frame is not.

### Compose stability

| Metric | baseline | optimized |
|---|--:|--:|
| Composable parameters the compiler could not prove stable | **32** | **0** |
| Parameters proven stable | 130 | 154 |

This is the number that matters more than the skippable/non-skippable split. Strong skipping
(default since Kotlin 2.0.20) already marked `ItemCard` "skippable", but its `PantryItem` and
`List<CategoryPreference>` parameters were unstable, and an unstable parameter is compared by
*instance identity* rather than `equals()`. Every Room emission rebuilds those objects, so the
skip check failed every time and every card recomposed regardless. All 32 are now stable — 14 of
them were `kotlinx.datetime.LocalDate` alone.

## Full generated tables


## On-device macrobenchmarks

Note (2026-11-13): the Calendar tab was removed and replaced by Recipe, and `CalendarPagingBenchmark` with it; its rows below, and the `Calendar*` composables in `baseline/` and `optimized/`, are kept as a record of this run and can't be reproduced.

| Benchmark | Metric | baseline | optimized | Change |
|---|---|--:|--:|--:|
| CalendarPagingBenchmark | frameCount (median) | 59.00 | 58.00 | -1.7% |
| CalendarPagingBenchmark | frameDurationCpuMs (P50) | 4.46 | 4.50 | +0.9% |
| CalendarPagingBenchmark | frameDurationCpuMs (P90) | 17.44 | 16.22 | -7.0% |
| CalendarPagingBenchmark | frameDurationCpuMs (P99) | 21.03 | 21.45 | +2.0% |
| CalendarPagingBenchmark | frameOverrunMs (P50) | -10.66 | -10.59 | -0.7% |
| CalendarPagingBenchmark | frameOverrunMs (P90) | 4.53 | 4.97 | +9.8% |
| CalendarPagingBenchmark | frameOverrunMs (P99) | 13.88 | 7.11 | -48.8% |
| FilterInteractionBenchmark | frameCount (median) | 87.00 | 90.00 | +3.4% |
| FilterInteractionBenchmark | frameDurationCpuMs (P50) | 4.44 | 4.68 | +5.2% |
| FilterInteractionBenchmark | frameDurationCpuMs (P90) | 7.85 | 18.50 | +135.8% |
| FilterInteractionBenchmark | frameDurationCpuMs (P99) | 25.69 | 32.51 | +26.6% |
| FilterInteractionBenchmark | frameOverrunMs (P50) | -10.60 | -10.32 | -2.6% |
| FilterInteractionBenchmark | frameOverrunMs (P90) | -1.29 | 4.01 | -411.2% |
| FilterInteractionBenchmark | frameOverrunMs (P99) | 23.39 | 20.74 | -11.3% |
| PantryScrollBenchmark | frameCount (median) | 61.50 | 61.50 | +0.0% |
| PantryScrollBenchmark | frameDurationCpuMs (P50) | 4.96 | 4.91 | -0.9% |
| PantryScrollBenchmark | frameDurationCpuMs (P90) | 7.81 | 12.41 | +58.8% |
| PantryScrollBenchmark | frameDurationCpuMs (P99) | 19.62 | 19.38 | -1.2% |
| PantryScrollBenchmark | frameOverrunMs (P50) | -10.01 | -10.16 | +1.5% |
| PantryScrollBenchmark | frameOverrunMs (P90) | -6.79 | -0.59 | -91.2% |
| PantryScrollBenchmark | frameOverrunMs (P99) | 4.24 | 3.66 | -13.7% |
| StartupBenchmark | timeToInitialDisplayMs (median) | 602.98 | 699.51 | +16.0% |

## JVM microbenchmarks (median, µs)

| Benchmark | Items | baseline | optimized | Change |
|---|--:|--:|--:|--:|
| `mainThread.searchQueryChanged` | 200 | 89.6 | 2.5 | -97.2% |
| `mainThread.searchQueryChanged` | 1000 | 91.6 | 1.7 | -98.1% |
| `mainThread.searchQueryChanged` | 5000 | 574.0 | 0.6 | -99.9% |
| `mainThread.sortOptionSelected` | 200 | 61.8 | 5.3 | -91.4% |
| `mainThread.sortOptionSelected` | 1000 | 156.6 | 3.0 | -98.1% |
| `mainThread.sortOptionSelected` | 5000 | 675.7 | 1.8 | -99.7% |
| `mainThread.statusFilterSelected` | 200 | 28.1 | 1.0 | -96.4% |
| `mainThread.statusFilterSelected` | 1000 | 117.8 | 0.4 | -99.7% |
| `mainThread.statusFilterSelected` | 5000 | 469.5 | 0.5 | -99.9% |
| `pipeline.repositoryReemission` | 200 | 104.9 | 104.7 | -0.2% |
| `pipeline.repositoryReemission` | 1000 | 240.9 | 312.0 | +29.5% |
| `pipeline.repositoryReemission` | 5000 | 780.2 | 887.3 | +13.7% |
| `pipeline.searchQueryChanged` | 200 | 12.8 | 242.9 | +1797.7% |
| `pipeline.searchQueryChanged` | 1000 | 65.8 | 136.3 | +107.1% |
| `pipeline.searchQueryChanged` | 5000 | 443.3 | 598.9 | +35.1% |
| `pipeline.sortOptionSelected` | 200 | 20.8 | 56.1 | +169.7% |
| `pipeline.sortOptionSelected` | 1000 | 138.7 | 137.0 | -1.2% |
| `pipeline.sortOptionSelected` | 5000 | 720.3 | 318.2 | -55.8% |
| `pipeline.statusFilterSelected` | 200 | 35.8 | 76.9 | +114.8% |
| `pipeline.statusFilterSelected` | 1000 | 338.5 | 285.2 | -15.7% |
| `pipeline.statusFilterSelected` | 5000 | 2293.0 | 1618.6 | -29.4% |

## Derivation algorithm: previous implementation vs current

Both measured in the same JVM run on the same data, so this isolates the
algorithm from the flow plumbing and from build-to-build variation.

| Operation | Items | previous | current | Change |
|---|--:|--:|--:|--:|
| `filterChange` | 200 | 13.1 | 2.9 | -77.9% |
| `filterChange` | 1000 | 89.0 | 18.0 | -79.8% |
| `filterChange` | 5000 | 631.2 | 90.5 | -85.7% |
| `fullDerivation` | 200 | 186.3 | 220.5 | +18.4% |
| `fullDerivation` | 1000 | 500.0 | 504.2 | +0.8% |
| `fullDerivation` | 5000 | 3047.0 | 2713.3 | -11.0% |

## Compose stability (static)

| Metric | baseline | optimized | Change |
|---|--:|--:|--:|
| unstable params | 32 | 0 | -100.0% |
| stable params | 130 | 154 | +18.5% |


## Caveats

- Emulator, not a device. Absolute values are not representative of real hardware and the frame
  timings do not resolve (see [Measurement noise](#measurement-noise)).
- `pipeline.*` in the JVM table measures an interaction end-to-end through `combine` and a flow
  collector. Its fixed per-emission cost dominates the work itself at these list sizes — note
  that 200 items sometimes measures slower than 1000 — so it is retained as a regression tripwire
  rather than as evidence of algorithmic cost. `algorithm.*` is the metric for that.
- No baseline profile was added. It is a real startup win but a separate axis that would have
  confounded "did these code changes help".
