#!/bin/sh
# Checks the iOS rating-prompt rule (the port of shared's ReviewPromptRule / ReviewPrompter):
#   iosApp/ReviewPromptCheck/check.sh
# Compiles iosApp/FoodTracker/Settings/ReviewPrompt.swift, which needs only Foundation, with
# main.swift outside the Xcode project and runs it. Needs only the Xcode toolchain; not run by CI,
# so run it after touching ReviewPrompt.swift.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
swiftc -swift-version 5 -o "$work/check" "$root/iosApp/FoodTracker/Settings/ReviewPrompt.swift" "$here/main.swift"
"$work/check"
