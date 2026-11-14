#!/bin/sh
# Checks the iOS ports of the multi-choice recipe config (toggling, normalising, reading old
# stored configs), its legacy wire mapping, the exact past-date warnings and the shopping-list rows:
#   iosApp/RecipeConfigCheck/check.sh
# Compiles RecipeModels.swift and RecipeWire.swift (with a stub of the pantry item)
# outside the Xcode project and runs main.swift, the same cases as Android's
# RecipeConfigChoicesTest. Needs only the Xcode toolchain; not run by CI, so run it after
# touching those files.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
swiftc -swift-version 5 -o "$work/check" \
  "$root/iosApp/FoodTracker/Models/RecipeModels.swift" \
  "$root/iosApp/FoodTracker/Recipe/RecipeWire.swift" \
  "$here/main.swift"
"$work/check"
