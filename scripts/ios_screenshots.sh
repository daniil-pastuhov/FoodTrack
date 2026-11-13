#!/bin/zsh
# Takes the raw App Store screenshots: builds the debug app, loads a demo pantry in each of the
# app's languages and captures every tab on a 6.9" iPhone simulator (1320x2868, the size App
# Store Connect requires).
#
#   scripts/ios_screenshots.sh [output-dir]      # default: build/ios-screenshots
#
# The demo data is loaded through the debug-only -demoBackup / -demoTab launch arguments (see
# RootTabView.applyScreenshotLaunchArguments), so release builds are unaffected. Dates in the demo
# pantry (scripts/demo_backup.py) are relative to today, so the "expires today / tomorrow" badges
# always read right. SETTLE=<seconds> waits longer before each capture on a slow machine.
set -euo pipefail

ROOT=${0:A:h:h}
OUT=${1:-$ROOT/build/ios-screenshots}
DEVICE_NAME=${DEVICE_NAME:-"iPhone 17 Pro Max"}   # e.g. DEVICE_NAME="iPad Pro 13-inch (M5)"
# Which of the simulator's displays to capture. iPhone Duo has two: the default is whichever is
# on, so open the simulator for the inner display; folded, its outer display is SCREEN=1.
SCREEN=${SCREEN:-}
BUNDLE_ID=com.foodbest.ios
DERIVED=$ROOT/build/ios-screenshots-derived-data

UDID=$(xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" in runtime:
        for d in devices:
            if d["name"] == name:
                print(d["udid"]); sys.exit()
sys.exit("no available simulator named " + name)' "$DEVICE_NAME")

xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
xcrun simctl ui "$UDID" appearance light
xcrun simctl status_bar "$UDID" override --time 9:41 --dataNetwork wifi --wifiMode active \
  --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100

xcodebuild -quiet -project "$ROOT/iosApp/FoodTracker.xcodeproj" -scheme FoodTracker \
  -configuration Debug -destination "id=$UDID" -derivedDataPath "$DERIVED" build
xcrun simctl install "$UDID" "$DERIVED/Build/Products/Debug-iphonesimulator/FoodTracker.app"
DATA=$(xcrun simctl get_app_container "$UDID" "$BUNDLE_ID" data)
mkdir -p "$DATA/tmp"

# A fresh simulator's first few launches can stay blank white for a while (the app waits on
# system services there), so launch until a capture shows the page's cream rather than white.
for TRY in {1..12}; do
  xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE_ID" >/dev/null
  sleep ${SETTLE:-4}
  xcrun simctl io "$UDID" screenshot ${SCREEN:+--display="$SCREEN"} "$DATA/tmp/warmup.png" >/dev/null 2>&1
  # Pillow, as frame_screenshots.py needs: white a third of the way down the left edge means
  # nothing has been drawn yet.
  python3 -c 'import sys; from PIL import Image; im = Image.open(sys.argv[1]).convert("RGB")
sys.exit(im.getpixel((8, im.height // 3)) == (255, 255, 255))' "$DATA/tmp/warmup.png" && break
  echo "warming up ($TRY)"
done

for LANG_CODE in en ru nb; do
  mkdir -p "$OUT/$LANG_CODE"
  python3 "$ROOT/scripts/demo_backup.py" "$LANG_CODE" "$DATA/tmp/demo.json"
  N=1
  for TAB in home calendar shopping settings; do
    xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE_ID" \
      -demoBackup "$DATA/tmp/demo.json" -demoTab "$TAB" \
      -AppleLanguages "($LANG_CODE)" -AppleLocale "$LANG_CODE" >/dev/null
    sleep ${SETTLE:-4}
    xcrun simctl io "$UDID" screenshot ${SCREEN:+--display="$SCREEN"} "$OUT/$LANG_CODE/$N-$TAB.png" >/dev/null 2>&1
    N=$((N + 1))
  done
  echo "$LANG_CODE: $(ls "$OUT/$LANG_CODE" | wc -l | tr -d ' ') screenshots"
done

xcrun simctl status_bar "$UDID" clear
echo "Saved to $OUT"
