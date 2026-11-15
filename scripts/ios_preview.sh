#!/bin/zsh
# Records the raw clips of the App Store app preview: builds the debug app, loads the demo pantry
# in each of the app's languages and records, on a 6.9" iPhone simulator, the story the preview
# tells. scripts/ios_preview_assemble.py cuts the clips into the preview.
#
#   scripts/ios_preview.sh [output-dir]      # default: build/ios-preview
#
# Three clips per language, in <output-dir>/<en|ru|nb|es|de|fr|sv|da>/:
#   scan.mp4    the scanner opens on a pack, reads its barcode (the product card comes up) and then
#               its printed date; Continue, Add item with the date filled in, Save; Home with
#               "saved" and the list scrolled to the new item; then the Recipe tab, the items that
#               expire first already ticked.
#   recipe.mp4  a written recipe, scrolled from the dish down to the method.
#   menu.mp4    the three-day menu, scrolled from today to the third day.
# and timeline.json: for each clip, the seconds into the recording at which its moments happened
# (the screen first drawn, each tap and swipe), which the assembly script cuts by, so a slow
# machine doesn't shift the cuts.
#
# Like scripts/ios_screenshots.sh it drives the debug-only demo launch arguments
# (RootTabView.applyScreenshotLaunchArguments): -demoBackup with scripts/demo_backup.py's pantry,
# -demoRoute scan with scripts/demo_scan_image.py's picture and -demoScanPace (the scan demo waits
# that long before reading the barcode and again before the date, so the recording shows each
# moment), recipeResult and recipeMenu with scripts/demo_recipes.py's files. Demo launches never
# show the rating prompt or the Recipe introduction. Taps and swipes are Maestro flows (`maestro`,
# https://maestro.dev) run on the same simulator; their times come from Maestro's debug output.
#
# It makes its own simulator ("Larder Preview 17 Pro Max", iOS 27.2) and deletes it at the end,
# so it never changes the simulators the screenshot scripts keep; KEEP_SIM=1 leaves it for a
# rerun, UDID=<udid> uses that one instead (and never deletes it). Every language is recorded on
# that one simulator, after one build; LANGS="en" (or "es de") records only those. On a busy machine the recording drops frames: run it when the load is low.
set -euo pipefail

ROOT=${0:A:h:h}
OUT=${1:-$ROOT/build/ios-preview}
OUT=${OUT:A}
BUNDLE_ID=com.foodbest.ios
DERIVED=$ROOT/build/ios-preview-derived-data
SIM_NAME="Larder Preview 17 Pro Max"
DEVICE_TYPE=com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max
RUNTIME=${RUNTIME:-com.apple.CoreSimulator.SimRuntime.iOS-27-2}
LANGS=(${=LANGS:-en ru nb es de fr sv da})
PACE=${PACE:-2.5}
command -v maestro >/dev/null || { echo "needs maestro (https://maestro.dev) on PATH" >&2; exit 1; }

if [[ -n ${UDID:-} ]]; then
  OWN_SIM=
else
  UDID=$(xcrun simctl list devices -j | python3 -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["name"] == sys.argv[1] and runtime == sys.argv[2]:
            print(d["udid"]); sys.exit()' "$SIM_NAME" "$RUNTIME")
  [[ -n $UDID ]] || UDID=$(xcrun simctl create "$SIM_NAME" "$DEVICE_TYPE" "$RUNTIME")
  OWN_SIM=1
fi
RECORDER=
cleanup() {
  [[ -n $RECORDER ]] && kill -INT $RECORDER 2>/dev/null || true
  xcrun simctl status_bar "$UDID" clear 2>/dev/null || true
  if [[ -n $OWN_SIM && -z ${KEEP_SIM:-} ]]; then
    xcrun simctl shutdown "$UDID" 2>/dev/null || true
    xcrun simctl delete "$UDID"
  fi
}
trap cleanup EXIT

xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
# A US region without a forced clock shows the status bar's override time as "9:41" (see
# ios_screenshots.sh); the app's own dates follow -AppleLocale below. Takes a reboot to show.
sim_defaults() { xcrun simctl spawn "$UDID" defaults "$@" 2>/dev/null; }
if [[ "$(sim_defaults read -g AppleLocale)" != en_US ]] || sim_defaults read -g AppleICUForce24HourTime >/dev/null \
    || sim_defaults read -g AppleICUForce12HourTime >/dev/null; then
  sim_defaults write -g AppleLocale en_US || true
  sim_defaults delete -g AppleICUForce24HourTime || true
  sim_defaults delete -g AppleICUForce12HourTime || true
  xcrun simctl shutdown "$UDID"
  xcrun simctl boot "$UDID"
  xcrun simctl bootstatus "$UDID" -b >/dev/null
fi
xcrun simctl ui "$UDID" appearance light
xcrun simctl status_bar "$UDID" override --time 9:41 --dataNetwork wifi --wifiMode active \
  --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState discharging --batteryLevel 100

xcodebuild -quiet -project "$ROOT/iosApp/FoodTracker.xcodeproj" -scheme FoodTracker \
  -configuration Debug -destination "id=$UDID" -derivedDataPath "$DERIVED" build
xcrun simctl install "$UDID" "$DERIVED/Build/Products/Debug-iphonesimulator/FoodTracker.app"
DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE_ID" data)
# The scanner asks for the camera before it shows the demo picture; granted, it asks nothing.
xcrun simctl privacy "$UDID" grant camera "$BUNDLE_ID"
mkdir -p "$DATA/tmp" "$OUT/flows"

# drawn: whether the screen shows the app's cream page rather than a blank white one (Pillow).
drawn() {
  xcrun simctl io "$UDID" screenshot "$DATA/tmp/probe.png" >/dev/null 2>&1 || return 1
  python3 -c 'import sys; from PIL import Image; im = Image.open(sys.argv[1]).convert("RGB")
sys.exit(im.getpixel((8, im.height // 3)) == (255, 255, 255))' "$DATA/tmp/probe.png"
}
# wait_drawn: waits up to a minute for the app to draw (a fresh simulator's first launches can
# take a while, waiting on system services).
wait_drawn() { for _ in {1..60}; do drawn && return 0; sleep 1; done; echo "the app never drew" >&2; return 1; }
launch() {
  xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE_ID" -demoBackup "$DATA/tmp/demo.json" \
    "$@" -AppleLanguages "($LANG_CODE)" -AppleLocale "${REGIONS[$LANG_CODE]}" >/dev/null
}
NOW() { python3 -c 'import time; print(time.time())'; }
# start_recording <file>: records the simulator's screen in the background and returns once the
# recorder has started; T0 is when it did.
start_recording() {
  rm -f "$1"
  # A script's background jobs start with SIGINT ignored, and SIGINT is what makes the recorder
  # finish its file: perl puts the default back before it becomes the recorder.
  perl -e '$SIG{INT} = "DEFAULT"; exec @ARGV' xcrun simctl io "$UDID" recordVideo --codec=h264 --force "$1" 2>"$1.log" &
  RECORDER=$!
  for _ in {1..100}; do grep -q "Recording started" "$1.log" 2>/dev/null && break; sleep 0.1; done
  T0=$(NOW)
}
stop_recording() {
  kill -INT $RECORDER; wait $RECORDER 2>/dev/null || true; RECORDER=
  rm -f "$1.log"
}
# mark <name> [unix-time]: a moment of the clip being recorded ($CLIP), now or at the given time,
# in seconds since the recording started, into $DIR/marks.txt.
mark() {
  python3 -c 'import sys, time; print(sys.argv[1], sys.argv[2], round(float(sys.argv[4] or time.time()) - float(sys.argv[3]), 2))' \
    "$CLIP" "$1" "$T0" "${2:-}" >> "$DIR/marks.txt"
}
# flow <name>: runs $OUT/flows/<name>.yaml and marks each command labelled by a comment
# "# mark: <name>" on the line before it, at the time Maestro started it (from its debug output).
flow() {
  local debug=$(mktemp -d "$OUT/flows/debug.XXXXXX")
  maestro --device "$UDID" test --debug-output "$debug" ${=FLOW_ENV} "$OUT/flows/$1.yaml" >/dev/null
  python3 - "$OUT/flows/$1.yaml" "$debug" <<'EOF' | while read -r NAME AT; do mark $NAME $AT; done
import json, re, sys
from pathlib import Path
flow, debug = sys.argv[1], sys.argv[2]
# Under <debug>/.maestro/tests/<run>/<flow>/ (pathlib's ** goes into hidden folders, glob's doesn't).
commands = json.loads(next(Path(debug).rglob("commands.json")).read_text())
# The flow's own commands, in order, without Maestro's preamble (variables, configuration).
steps = [c for c in commands
         if not {"defineVariablesCommand", "applyConfigurationCommand"} & c["command"].keys()]
names, pending = [], None
for line in open(flow).read().split("\n---\n", 1)[1].splitlines():
    if m := re.match(r"# mark: (\w+)", line):
        pending = m.group(1)
    elif line.startswith("- "):
        names.append(pending)
        pending = None
for name, step in zip(names, steps):
    if name:
        print(name, step["metadata"]["timestamp"] / 1000)
EOF
  rm -r -- "$debug"
}
# pause <ms>: a Maestro wait (Maestro has no plain sleep).
pause() { print -- "- extendedWaitUntil:\n    visible: \"zz-never-shown\"\n    timeout: $1\n    optional: true"; }

# The flows. Points rather than labels, as labels change with the language; the Save button is
# the Add item sheet's full-width one at the bottom, the Recipe tab the second in the tab bar.
{
  print "appId: $BUNDLE_ID\n---"
  print "# mark: continue\n- tapOn: \${CONTINUE}"
  pause 1800
  print "# mark: save\n- tapOn:\n    point: \"50%,92%\""
  pause 2000
  print "# mark: list\n- swipe:\n    start: 50%, 80%\n    end: 50%, 45%\n    duration: 1400"
  pause 1800
  print "# mark: recipe\n- tapOn:\n    point: \"39%,93%\""
  pause 2500
} > "$OUT/flows/scan.yaml"
{
  print "appId: $BUNDLE_ID\n---"
  pause 800
  print "# mark: swipe\n- swipe:\n    start: 50%, 72%\n    end: 50%, 40%\n    duration: 3000"
  pause 1500
} > "$OUT/flows/scroll.yaml"

typeset -A REGIONS=(en en_GB ru ru_RU nb nb_NO es es_ES de de_DE fr fr_FR sv sv_SE da da_DK)
# The scan card's Continue button, by language ("continue_button" in *.lproj/Localizable.strings).
typeset -A CONTINUE=(en Continue ru Продолжить nb Fortsett es Continuar de Weiter fr Continuer
  sv Fortsätt da Fortsæt)
for LANG_CODE in $LANGS; do
  [[ -n ${CONTINUE[$LANG_CODE]:-} ]] || { echo "unknown language $LANG_CODE (one of ${(k)REGIONS})"; exit 1; }
done
SCAN_DATE=$(python3 -c 'import datetime; print(datetime.date.today() + datetime.timedelta(days=12))')
SCAN_BARCODE=$(cd "$ROOT/scripts" && python3 -c 'from demo_scan_image import BARCODE; print(BARCODE)')

# A fresh simulator's first launches can stay blank for a while: launch until the app draws.
LANG_CODE=en
python3 "$ROOT/scripts/demo_backup.py" en "$DATA/tmp/demo.json"
for TRY in {1..5}; do launch -demoTab home; wait_drawn && break; done

for LANG_CODE in $LANGS; do
  DIR=$OUT/$LANG_CODE
  mkdir -p "$DIR"
  FLOW_ENV="-e CONTINUE=${CONTINUE[$LANG_CODE]}"
  python3 "$ROOT/scripts/demo_scan_image.py" "$LANG_CODE" "$SCAN_DATE" "$DATA/tmp/scan.png" "$DATA/tmp/scan-thumb.png"
  python3 "$ROOT/scripts/demo_backup.py" "$LANG_CODE" "$DATA/tmp/demo.json" "file://$DATA/tmp/scan-thumb.png"
  python3 "$ROOT/scripts/demo_recipes.py" "$LANG_CODE" "$DATA/tmp/recipe.json" "$DATA/tmp/menu.json"
  : > "$DIR/marks.txt"

  # The scan, saved, in the list, then the Recipe tab. A launch first, unrecorded, so the
  # recorded one starts warm.
  CLIP=scan
  launch -demoTab home; wait_drawn
  start_recording "$DIR/scan.mp4"
  launch -demoRoute scan -demoScanImage "$DATA/tmp/scan.png" -demoScanBarcode "$SCAN_BARCODE" \
    -demoScanDate "$SCAN_DATE" -demoScanPace "$PACE"
  mark launched
  wait_drawn
  mark drawn
  sleep $(( PACE * 2 + 1.5 ))
  flow scan
  mark end
  stop_recording "$DIR/scan.mp4"

  for CLIP in recipe menu; do
    ROUTE=recipeResult; FILE=recipe.json
    [[ $CLIP == menu ]] && ROUTE=recipeMenu FILE=menu.json
    launch -demoTab home; wait_drawn
    start_recording "$DIR/$CLIP.mp4"
    launch -demoRoute $ROUTE -demoRecipe "$DATA/tmp/$FILE"
    mark launched
    wait_drawn
    mark drawn
    flow scroll
    mark end
    stop_recording "$DIR/$CLIP.mp4"
  done

  python3 - "$DIR/marks.txt" "$PACE" > "$DIR/timeline.json" <<'EOF'
import json, sys
timeline = {}
for line in open(sys.argv[1]):
    clip, name, at = line.split()
    timeline.setdefault(clip, {})[name] = float(at)
timeline["scan"]["pace"] = float(sys.argv[2])
print(json.dumps(timeline, indent=1))
EOF
  rm "$DIR/marks.txt"
  echo "$LANG_CODE: $(tr -d '\n ' < "$DIR/timeline.json")"
done
echo "Saved to $OUT"
