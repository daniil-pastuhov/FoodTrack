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
# always read right. SETTLE=<seconds> waits longer before each capture on a slow machine. It sets
# the simulator's own region to en_US (with a reboot, the first time), so use a simulator kept for
# this.
# The Recipe shot is the tab itself, as on Android (demoTab recipe). For a written recipe instead,
# RootTabView also takes -demoRoute recipeResult (or recipeMenu, buyRecipes) and
# -demoRecipeCredits <n>; Android has no such arguments, so the two sets would differ.
set -euo pipefail

ROOT=${0:A:h:h}
OUT=${1:-$ROOT/build/ios-screenshots}
DEVICE_NAME=${DEVICE_NAME:-"iPhone 17 Pro Max"}   # e.g. DEVICE_NAME="iPad Pro 13-inch (M5)"
# Which of the simulator's displays to capture. iPhone Duo has two: the default is whichever is
# on, so open the simulator for the inner display; folded, its outer display is SCREEN=1.
SCREEN=${SCREEN:-}
BUNDLE_ID=com.foodbest.ios
DERIVED=$ROOT/build/ios-screenshots-derived-data

# UDID=<udid> picks one simulator when several share a name (it wins over DEVICE_NAME).
# KEEP_REGION=1 skips the region change below and its reboot: an unfolded iPhone Duo can come back
# folded after a reboot, and only Xcode's Device Hub can unfold it again. The status bar then
# keeps the simulator's own clock style ("09:41" in a 24-hour region).
UDID=${UDID:-$(xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" in runtime:
        for d in devices:
            if d["name"] == name:
                print(d["udid"]); sys.exit()
sys.exit("no available simulator named " + name)' "$DEVICE_NAME")}

xcrun simctl boot "$UDID" 2>/dev/null || true
xcrun simctl bootstatus "$UDID" -b >/dev/null
# The status bar formats the override time in the simulator's own region, which a new simulator
# copies from this Mac: a 24-hour one shows "09:41". A US region without a forced clock shows
# "9:41"; the app's own dates and times follow -AppleLocale below. Takes a reboot to show.
sim_defaults() { xcrun simctl spawn "$UDID" defaults "$@" 2>/dev/null; }
if [[ -n ${KEEP_REGION:-} ]]; then
  :
elif [[ "$(sim_defaults read -g AppleLocale)" != en_US ]] || sim_defaults read -g AppleICUForce24HourTime >/dev/null \
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
mkdir -p "$DATA/tmp"

# A fresh simulator's first few launches can stay blank white for a while (the app waits on
# system services there), so launch until a capture shows the page's cream rather than white.
for TRY in {1..40}; do
  xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE_ID" >/dev/null
  sleep ${SETTLE:-4}
  xcrun simctl io "$UDID" screenshot ${SCREEN:+--display="$SCREEN"} "$DATA/tmp/warmup.png" >/dev/null 2>&1
  # Pillow, as frame_screenshots.py needs: white a third of the way down the left edge means
  # nothing has been drawn yet.
  python3 -c 'import sys; from PIL import Image; im = Image.open(sys.argv[1]).convert("RGB")
sys.exit(im.getpixel((8, im.height // 3)) == (255, 255, 255))' "$DATA/tmp/warmup.png" && break
  echo "warming up ($TRY)"
done

# Each language with its listing's region, so dates read "22 Nov" and times "09:00" as in the
# U.K., Russia and Norway, like the Google Play set.
typeset -A REGIONS=(en en_GB ru ru_RU nb nb_NO)
for LANG_CODE in en ru nb; do
  mkdir -p "$OUT/$LANG_CODE"
  python3 "$ROOT/scripts/demo_backup.py" "$LANG_CODE" "$DATA/tmp/demo.json"
  N=1
  for TAB in home recipe shopping settings; do
    xcrun simctl launch --terminate-running-process "$UDID" "$BUNDLE_ID" \
      -demoBackup "$DATA/tmp/demo.json" -demoTab "$TAB" \
      -AppleLanguages "($LANG_CODE)" -AppleLocale "${REGIONS[$LANG_CODE]}" >/dev/null
    sleep ${SETTLE:-4}
    xcrun simctl io "$UDID" screenshot ${SCREEN:+--display="$SCREEN"} "$OUT/$LANG_CODE/$N-$TAB.png" >/dev/null 2>&1
    N=$((N + 1))
  done
  echo "$LANG_CODE: $(ls "$OUT/$LANG_CODE" | wc -l | tr -d ' ') screenshots"
done

xcrun simctl status_bar "$UDID" clear
echo "Saved to $OUT"
