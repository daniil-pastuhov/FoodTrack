#!/bin/sh
# Checks the iOS ports of the multi-choice recipe config (toggling, normalising, reading old
# stored configs), its legacy wire mapping, the exact past-date warnings, the shopping-list rows
# the phone-model language rule, menu sets (prices from costs.menuSets, what is offered and the
# days/courses request shape), the unread saved-recipe set, Saved recipes' multi-select, the Buy sheet's pack savings and the
# taste profile (the server's quiz definition decoded leniently; toggling, caps, conflicts, the
# answers filtered through a changed definition, new questions, summary, gate, request shape,
# acceptsTaste, never in a free route's request), free recipes for signing in (what is shown where,
# the offer's age and refresh, the claim's outcomes, the menu line) and staples (presets, order,
# keys, out, adding, what each route gets, the wire names and caps, acceptsStaples, stapleIndex,
# the shopping list's exclusion, the backup field, onboarding's Staples step), recipe pictures (the
# Pictures row's levels, what is sent with a short pack, the picture prices in costs, the request's
# image, the flag, the reply's pictures read leniently, the lines) and "Report this recipe" (the
# reasons, the note's cut, when the action shows, what is remembered and sent), a recipe's meal
# (read from a reply, the phone's label checked against the config) and Saved recipes' tabs and
# order (`SavedList`):
#   iosApp/RecipeConfigCheck/check.sh
# Compiles RecipeModels.swift, RecipeWire.swift, TasteProfile.swift, WelcomeRecipes.swift and Staple.swift (with a stub of the pantry item)
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
  "$root/iosApp/FoodTracker/Recipe/WelcomeRecipes.swift" \
  "$root/iosApp/FoodTracker/Models/Staple.swift" \
  "$root/iosApp/FoodTracker/Views/OnboardingFlow.swift" \
  "$here/main.swift"
"$work/check"
