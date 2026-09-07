#!/usr/bin/env bash
#
# Runs the full performance suite and collects the results under perf/<label>/.
#
#   ./scripts/perf.sh baseline     # before a change
#   ./scripts/perf.sh optimized    # after it
#   python3 scripts/perf_report.py baseline optimized > perf/RESULTS.md
#
# Three complementary measurements are taken, because no single one is trustworthy alone:
#
#   1. macrobenchmark  — real startup and frame timings on a connected device. Closest to what
#                        a user feels, but the noisiest, especially on an emulator.
#   2. JVM micro       — the state holder's filter/sort cost in isolation, plus a direct
#                        legacy-vs-current comparison of the derivation algorithm itself.
#                        Deterministic, but blind to anything Compose does.
#   3. Compose report  — static per-composable skippability and per-class stability. Exact and
#                        reproducible, but a proxy rather than a timing.
#
set -euo pipefail

LABEL="${1:-}"
if [[ -z "$LABEL" ]]; then
    echo "usage: $0 <label>   (e.g. baseline, optimized)" >&2
    exit 2
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

OUT="perf/$LABEL"
mkdir -p "$OUT"

# Benchmarking on an emulator is not representative of real hardware and the library refuses by
# default. It is suppressed rather than worked around because the comparison here is between two
# revisions on the *same* emulator, where the systematic error cancels out.
#
# NOT-PROFILEABLE is deliberately NOT suppressed: without <profileable android:shell="true"> in
# the app manifest, FrameTimingMetric silently degrades to reporting frameCount only, with no
# frame durations at all. Letting that fail loudly is the whole point.
SUPPRESS="EMULATOR,UNLOCKED,LOW-BATTERY"

echo "==> [1/3] JVM microbenchmarks"
rm -f "perf/raw/jvm-$LABEL.tsv"
./gradlew :androidApp:testDebugUnitTest \
    --tests "*PerfTest*" \
    -Pperf.label="$LABEL" \
    --rerun-tasks --console=plain
cp "perf/raw/jvm-$LABEL.tsv" "$OUT/jvm.tsv"

echo "==> [2/3] Compose compiler stability report"
./gradlew :androidApp:assembleBenchmark --rerun-tasks --console=plain
cp androidApp/build/compose-reports/androidApp-composables.txt "$OUT/compose-composables.txt"
cp androidApp/build/compose-reports/androidApp-composables.csv "$OUT/compose-composables.csv"
cp androidApp/build/compose-reports/androidApp-classes.txt "$OUT/compose-classes.txt"

echo "==> [3/3] Macrobenchmarks on device"
if ! adb get-state >/dev/null 2>&1; then
    echo "    !! no device/emulator connected — skipping macrobenchmarks" >&2
else
    # A stale test APK signed with a different key blocks reinstall.
    adb uninstall com.foodtracker.macrobenchmark >/dev/null 2>&1 || true
    ./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors="$SUPPRESS" \
        --console=plain
    find macrobenchmark/build/outputs/connected_android_test_additional_output \
        -name '*benchmarkData.json' -exec cp {} "$OUT/macrobenchmark.json" \;
fi

echo
echo "==> results written to $OUT/"
ls -1 "$OUT"
