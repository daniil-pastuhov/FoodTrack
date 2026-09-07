#!/usr/bin/env python3
"""Renders a before/after comparison of two perf/<label>/ result sets as Markdown.

    python3 scripts/perf_report.py baseline optimized > perf/RESULTS.md

Reads whatever is present and silently skips what isn't, so it still produces a useful report
when (say) no device was connected for the macrobenchmark half.
"""
import csv
import json
import os
import sys

PERF_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "perf")


def pct(before, after):
    """Signed percentage change, phrased so that negative always means 'got faster'."""
    if before == 0:
        return "n/a"
    delta = (after - before) / before * 100.0
    return f"{delta:+.1f}%"


def read_macro(label):
    """Flattens a benchmarkData.json into {(benchmark, metric): value}.

    Two different shapes have to be handled. Whole-iteration measurements (startup time, frame
    count) land in `metrics` with a `median`. Per-frame distributions land in `sampledMetrics`
    with percentiles instead — `frameDurationCpuMs` is the interesting one and it lives only
    there, which is easy to miss because `metrics` still carries a plausible-looking
    `frameCount` alongside it.
    """
    path = os.path.join(PERF_DIR, label, "macrobenchmark.json")
    if not os.path.exists(path):
        return {}
    with open(path) as handle:
        data = json.load(handle)
    out = {}
    for bench in data.get("benchmarks", []):
        name = bench["className"].rsplit(".", 1)[-1]
        for metric, values in bench.get("metrics", {}).items():
            if "median" in values:
                out[(name, f"{metric} (median)")] = values["median"]
        for metric, values in bench.get("sampledMetrics", {}).items():
            for percentile in ("P50", "P90", "P99"):
                if percentile in values:
                    out[(name, f"{metric} ({percentile})")] = values[percentile]
    return out


def read_jvm(label):
    path = os.path.join(PERF_DIR, label, "jvm.tsv")
    if not os.path.exists(path):
        return {}
    out = {}
    with open(path) as handle:
        for row in csv.DictReader(handle, delimiter="\t"):
            out[(row["benchmark"], int(row["size"]))] = float(row["median_us"])
    return out


def read_compose(label):
    """Counts composable parameters the compiler could not prove stable.

    This, rather than the raw skippable/non-skippable split, is the number that matters: with
    strong skipping (on by default since Kotlin 2.0.20) almost every composable is reported
    'skippable', but a parameter whose type is unstable is compared by *instance identity*
    rather than by equals(). Since each database emission rebuilds these objects, an unstable
    parameter means the skip check fails every time and the composable recomposes anyway.
    """
    path = os.path.join(PERF_DIR, label, "compose-composables.txt")
    if not os.path.exists(path):
        return None
    unstable = 0
    stable = 0
    with open(path) as handle:
        for line in handle:
            if line.startswith("  unstable "):
                unstable += 1
            elif line.startswith("  stable "):
                stable += 1
    return {"unstable_params": unstable, "stable_params": stable}


def section(title):
    print(f"\n## {title}\n")


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: perf_report.py <before-label> <after-label>")
    before_label, after_label = sys.argv[1], sys.argv[2]

    print(f"# Performance: `{before_label}` vs `{after_label}`\n")
    print("Negative percentages are improvements (less time, fewer unstable parameters).")

    # --- Macrobenchmark -----------------------------------------------------
    before, after = read_macro(before_label), read_macro(after_label)
    if before and after:
        section("On-device macrobenchmarks")
        print(f"| Benchmark | Metric | {before_label} | {after_label} | Change |")
        print("|---|---|--:|--:|--:|")
        for key in sorted(before.keys() & after.keys()):
            name, metric = key
            b, a = before[key], after[key]
            print(f"| {name} | {metric} | {b:.2f} | {a:.2f} | {pct(b, a)} |")

    # --- JVM ----------------------------------------------------------------
    before, after = read_jvm(before_label), read_jvm(after_label)
    if before and after:
        section("JVM microbenchmarks (median, µs)")
        print(f"| Benchmark | Items | {before_label} | {after_label} | Change |")
        print("|---|--:|--:|--:|--:|")
        for key in sorted(before.keys() & after.keys(), key=lambda k: (k[0], k[1])):
            name, size = key
            b, a = before[key], after[key]
            print(f"| `{name}` | {size} | {b:.1f} | {a:.1f} | {pct(b, a)} |")

    # --- Compose ------------------------------------------------------------
    before, after = read_compose(before_label), read_compose(after_label)
    if before and after:
        section("Compose stability (static)")
        print(f"| Metric | {before_label} | {after_label} | Change |")
        print("|---|--:|--:|--:|")
        for metric in ("unstable_params", "stable_params"):
            b, a = before[metric], after[metric]
            print(f"| {metric.replace('_', ' ')} | {b} | {a} | {pct(b, a)} |")


if __name__ == "__main__":
    main()
