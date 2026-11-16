#!/usr/bin/env bash
#
# The UI screenshot check: renders every Android @Preview (plus the Home, shopping list and
# add/edit screens of ScreenScreenshotTest) on Robolectric and compares each with its baseline
# in androidApp/src/test/snapshots/images/.
#
#   scripts/ui_check.sh            # verify: did any screen change?
#   scripts/ui_check.sh --record   # accept the current UI as the new baseline
#
# Exit status: 0 nothing changed / 1 differences (or a preview with no baseline yet) / 2 the
# check could not run (a build error, a preview that crashed).
#
# The baselines are recorded on macOS; Robolectric's rendering differs slightly on Linux, so CI
# does not run this. Plain ./gradlew :androidApp:testDebugUnitTest renders but compares nothing.
# Not to be confused with scripts/screenshot_check.py, which re-takes the store screenshots on an
# emulator and a simulator.
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MODE=verify
case "${1:-}" in
    "") ;;
    --record) MODE=record ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: $0 [--record]" >&2; exit 2 ;;
esac

BASELINES=androidApp/src/test/snapshots/images
OUTPUTS=androidApp/build/outputs/roborazzi
RESULTS=androidApp/build/test-results/roborazzi
REPORT=androidApp/build/reports/roborazzi/debug/index.html
LOG=androidApp/build/ui-check.log

# Only this run's diff images and results, so the summary below is about this run.
rm -rf "$OUTPUTS" "$RESULTS"
if [[ $MODE == record ]]; then
    # Recorded afresh, so a preview that was renamed or removed loses its old image.
    rm -rf "$BASELINES"
fi
mkdir -p androidApp/build

# --rerun: the test task must run even when Gradle thinks nothing changed.
./gradlew :androidApp:testDebugUnitTest --rerun \
    --tests "com.github.takahirom.roborazzi.RoborazziPreviewParameterizedTests" \
    --tests "com.foodtracker.android.screenshots.*" \
    -Proborazzi.test.$MODE=true \
    --console=plain >"$LOG" 2>&1
GRADLE_STATUS=$?

python3 - "$MODE" "$GRADLE_STATUS" "$RESULTS" "$REPORT" "$OUTPUTS" "$LOG" "$BASELINES" <<'PY'
import glob, json, os, sys
import xml.etree.ElementTree as ET

mode, status, results, report, outputs, log, baselines = sys.argv[1:]
status = int(status)

summaries = glob.glob(os.path.join(results, "**", "results-summary.json"), recursive=True)
entries = []
for path in summaries:
    with open(path) as f:
        entries += json.load(f).get("results", [])

failed_tests = []
for path in glob.glob("androidApp/build/test-results/testDebugUnitTest/TEST-*.xml"):
    for case in ET.parse(path).getroot().iter("testcase"):
        if case.find("failure") is not None or case.find("error") is not None:
            failed_tests.append(case.get("name"))

def name(entry):
    path = entry.get("golden_file_path") or entry.get("actual_file_path") or ""
    return os.path.basename(path)

if mode == "record":
    count = len(glob.glob(os.path.join(baselines, "*.png")))
    if status != 0 or not count:
        print(f"Recording failed ({len(failed_tests)} tests failed). Log: {log}")
        for test in failed_tests[:20]:
            print("  " + test)
        sys.exit(2)
    print(f"Recorded {count} baseline images in {baselines}/.")
    print("Review them with git status / git diff before committing.")
    sys.exit(0)

changed = sorted(name(e) for e in entries if e.get("type") == "changed")
added = sorted(name(e) for e in entries if e.get("type") == "added")
print(f"{len(entries)} screenshots compared: {len(entries) - len(changed) - len(added)} unchanged, "
      f"{len(changed)} changed, {len(added)} without a baseline.")
for n in changed:
    print("  changed:  " + n)
for n in added:
    print("  new:      " + n + "  (record it with scripts/ui_check.sh --record)")

differences = len(changed) + len(added)
crashed = len(failed_tests) - differences
if differences:
    print(f"Diff images (<name>_compare.png, reference | diff | new): {outputs}/")
    if os.path.exists(report):
        print(f"Report: {report}")
if crashed > 0 or (status != 0 and not differences) or not entries:
    print(f"The check did not run cleanly ({max(crashed, 0)} other failures, Gradle exit {status}). Log: {log}")
    sys.exit(2)
sys.exit(1 if differences else 0)
PY
