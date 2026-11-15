#!/bin/zsh
# Folds or unfolds a booted iPhone Duo simulator, for scripts/ios_screenshots.sh's two Duo sets:
#
#   scripts/duo_pose.sh Open [udid]      # unfolded: the inner display (SCREEN=3) is on
#   scripts/duo_pose.sh Closed [udid]    # folded: the outer display (SCREEN=1) is on
#
# simctl has no hinge control (`simctl io screenConfig power` only blanks a display and can leave
# SpringBoard stopped), so this clicks the pose buttons ("Closed", "Partially Open", "Open") that
# Xcode's Device Hub shows beside the device, through System Events. It needs Device Hub running
# with the simulator's window open: either its own window ("iPhone Duo – iOS 27.1", Device Hub
# with one window per device, tried first) or the main window, where it is selected in the
# sidebar by the name simctl gives it. It also needs Accessibility access for the app running this script (System Settings >
# Privacy & Security > Accessibility). It then waits until the chosen display draws something.
# A reboot (the screenshot script's region change; use KEEP_REGION=1) brings the device back
# folded.
set -euo pipefail

POSE=${1:?usage: duo_pose.sh Open|Closed|"Partially Open" [udid]}
UDID=${2:-$(xcrun simctl list devices booted -j | python3 -c '
import json, sys
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        if d["name"] == "iPhone Duo" and d["state"] == "Booted":
            print(d["udid"]); sys.exit()
sys.exit("no booted iPhone Duo")')}
NAME=$(xcrun simctl list devices -j | python3 -c '
import json, sys
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        if d["udid"] == sys.argv[1]:
            print(d["name"]); sys.exit()' "$UDID")
# "iOS 27.1", as Device Hub titles a device's window ("iPhone Duo – iOS 27.1").
RUNTIME=$(xcrun simctl list devices -j | python3 -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["udid"] == sys.argv[1]:
            v = runtime.rsplit(".", 1)[-1].split("-")
            print(v[0] + " " + ".".join(v[1:3])); sys.exit()' "$UDID")

open -a DeviceHub
osascript - "$NAME" "$POSE" "$RUNTIME" <<'EOF'
on run {deviceName, pose, runtimeName}
  tell application "System Events" to tell process "DeviceHub"
    set frontmost to true
    delay 1
    -- A Device Hub with one window per device (no sidebar): the window titled
    -- "<name> – <runtime>" carries the pose buttons in its first group. A shut-down device's
    -- window of the same title has none, so the first window that has the button is the one.
    repeat with w in windows
      try
        if name of w is (deviceName & " – " & runtimeName) then
          repeat with b in (buttons of group 1 of w)
            try
              if help of b is pose then
                click b
                return
              end if
            end try
          end repeat
        end if
      end try
    end repeat
    -- Otherwise the main window with its device sidebar.
    set o to outline 1 of scroll area 1 of group 1 of splitter group 1 of group 1 of splitter group 1 of group 1 of window 1
    -- Running devices are listed first, so the first row with the name is the booted one.
    repeat with r in rows of o
      try
        if value of static text 1 of UI element 1 of r is deviceName then
          select r
          exit repeat
        end if
      end try
    end repeat
    delay 2
    repeat with e in (entire contents of window 1)
      try
        if role of e is "AXButton" and help of e is pose then
          click e
          return
        end if
      end try
    end repeat
    error "Device Hub shows no " & pose & " button for " & deviceName
  end tell
end run
EOF

[[ $POSE == Closed ]] && SCREEN=1 || SCREEN=3
SHOT=$(mktemp -t duo-pose).png
for TRY in {1..30}; do
  sleep 2
  xcrun simctl io "$UDID" screenshot --display=$SCREEN "$SHOT" >/dev/null 2>&1 || continue
  python3 -c 'import sys; from PIL import Image
sys.exit(Image.open(sys.argv[1]).convert("L").getextrema()[1] == 0)' "$SHOT" && { rm -f "$SHOT"; echo "$POSE: display $SCREEN is on"; exit 0; }
done
rm -f "$SHOT"
echo "display $SCREEN stayed dark after choosing $POSE" >&2
exit 1
