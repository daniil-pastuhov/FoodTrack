#!/bin/sh
# Checks the iOS ports of the multi-choice recipe config (toggling, normalising, reading old
# stored configs), its legacy wire mapping, the exact past-date warnings, the shopping-list rows
# the phone-model language rule, menu sets (prices from costs.menuSets, what is offered and the
# days/courses request shape), the unread saved-recipe set, the Buy sheet's pack savings and the
# taste profile (the server's quiz definition decoded leniently; toggling, caps, conflicts, the
# answers filtered through a changed definition, new questions, summary, gate, request shape,
# acceptsTaste, never in a free route's request):
#   iosApp/RecipeConfigCheck/check.sh
# Compiles RecipeModels.swift, RecipeWire.swift and TasteProfile.swift (with a stub of the pantry item)
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
  "$root/iosApp/FoodTracker/Recipe/TasteProfile.swift" \
  "$here/main.swift"
"$work/check"
