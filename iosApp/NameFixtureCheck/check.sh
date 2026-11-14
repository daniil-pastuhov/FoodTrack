#!/bin/sh
# Checks the iOS name cleaner against the fixture the server and Android are tested on:
#   iosApp/NameFixtureCheck/check.sh
# Copies the name-cleaning section of RecipeWire.swift (from `static let nameMaxLength` up to the
# `isoDay` doc comment) into a throwaway enum, compiles it with main.swift outside the Xcode
# project, and runs it over functions/test/fixtures/names.json. Needs only the Xcode toolchain.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
source="$root/iosApp/FoodTracker/Recipe/RecipeWire.swift"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
{
    echo "import Foundation"
    echo "enum RecipeWire {"
    awk '/^    static let nameMaxLength = 40$/ { on = 1 } /^    \/\/\/ `YYYY-MM-DD` in the phone/ { on = 0 } on' "$source"
    echo "}"
} > "$work/RecipeWireNames.swift"
if ! grep -q "static func cleanName" "$work/RecipeWireNames.swift"; then
    echo "cleanName not found in $source" >&2
    exit 2
fi
swiftc -O -o "$work/check" "$work/RecipeWireNames.swift" "$here/main.swift"
"$work/check" "$root/functions/test/fixtures/names.json"
