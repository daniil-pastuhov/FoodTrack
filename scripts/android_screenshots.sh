#!/bin/zsh
# Takes the raw Google Play screenshots: installs the debug app on a running emulator, loads the
# demo pantry in each of the app's languages and captures Home, Recipe, Shopping, an item's Edit
# screen and Settings.
#
#   ANDROID_SERIAL=emulator-5554 scripts/android_screenshots.sh [output-dir]   # default: build/android-screenshots
#   scripts/frame_screenshots.py build/android-screenshots phone-screenshots
#
# The demo pantry (scripts/demo_backup.py) goes in through the debug-only demoBackup / demoTab
# intent extras (see MainActivity.loadDemoFromIntent), so release builds are unaffected. It
# replaces whatever the app holds on that device, so use an emulator kept for this. Build the
# APK first with ./gradlew :androidApp:assembleDebug. SETTLE=<seconds> waits longer before each
# capture on a slow machine.
set -euo pipefail

ROOT=${0:A:h:h}
OUT=${1:-$ROOT/build/android-screenshots}
PACKAGE=com.foodtracker.android
ACTIVITY=$PACKAGE/.MainActivity
APK=$ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk
SETTLE=${SETTLE:-4}
: ${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator to use, e.g. emulator-5554}
export ANDROID_SERIAL

adb wait-for-device
adb install -r "$APK" >/dev/null
# The demo file goes in the app's private files folder through run-as (debug builds allow it):
# a folder adb creates under /sdcard/Android/data isn't readable by the app.
DEMO_FILE=/data/data/$PACKAGE/files/demo.json
adb shell run-as $PACKAGE mkdir -p files

adb shell cmd uimode night no >/dev/null

# A fixed, tidy status bar: 9:41, full signal and battery, no notifications.
adb shell settings put global sysui_demo_allowed 1
demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4
demo network -e mobile show -e datatype none -e level 4
demo notifications -e visible false

typeset -A LOCALES=(en en-GB ru ru-RU nb nb-NO)
for LANG_CODE in en ru nb; do
  mkdir -p "$OUT/$LANG_CODE"
  rm -f "$OUT/$LANG_CODE"/*.png(N)
  adb shell cmd locale set-app-locales $PACKAGE --locales ${LOCALES[$LANG_CODE]}
  python3 "$ROOT/scripts/demo_backup.py" "$LANG_CODE" "$TMPDIR/demo-$LANG_CODE.json"
  adb shell run-as $PACKAGE sh -c "'cat > files/demo.json'" < "$TMPDIR/demo-$LANG_CODE.json"
  # The restore runs once. It finishes after the first frame, so this launch may still show
  # onboarding; the captures below relaunch with onboarding done.
  adb shell am start -S -W -n $ACTIVITY --es demoBackup "$DEMO_FILE" >/dev/null
  sleep $((SETTLE * 2))
  # One launch with nothing to capture: the first after a restore can still be drawing.
  adb shell am start -S -W -n $ACTIVITY >/dev/null
  sleep $SETTLE
  N=1
  # demo-4 is the salmon, a few days from its date (see demo_backup.py).
  for TAB in home recipe shopping edit:demo-4 settings; do
    adb shell am start -S -W -n $ACTIVITY --es demoTab "$TAB" >/dev/null
    sleep $SETTLE
    # A slow emulator can raise "isn't responding" dialogs for other apps over ours.
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    demo clock -e hhmm 0941  # again: System UI drops demo mode if it restarts
    adb exec-out screencap -p > "$OUT/$LANG_CODE/$N-${TAB%%:*}.png"
    N=$((N + 1))
  done
  echo "$LANG_CODE: $(ls "$OUT/$LANG_CODE" | wc -l | tr -d ' ') screenshots"
done

adb shell cmd locale set-app-locales $PACKAGE --locales ""
adb shell run-as $PACKAGE rm -f files/demo.json
demo exit
echo "Saved to $OUT"
