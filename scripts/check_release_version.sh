#!/bin/bash
# Checks that a release branch is consistent before CI publishes it, and exports RELEASE_VERSION
# for later steps. Used by .github/workflows/ci.yml with the version from the branch name:
#
#   scripts/check_release_version.sh 1.3.0
#
# The Android versionName, every iOS MARKETING_VERSION and the release notes all have to match,
# since scripts/release writes them together and a hand edit can leave one behind.
set -euo pipefail

VERSION=${1:?usage: $0 X.Y.Z}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LOCALES=(en-GB ru-RU no-NO)
fail() { echo "::error::$1"; exit 1; }

[[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$VERSION' is not a X.Y.Z version"
grep -q "versionName = \"$VERSION\"" "$ROOT/androidApp/build.gradle.kts" ||
  fail "androidApp/build.gradle.kts versionName is not $VERSION"
if grep "MARKETING_VERSION = " "$ROOT/iosApp/FoodTracker.xcodeproj/project.pbxproj" | grep -vq "= $VERSION;"; then
  fail "not every MARKETING_VERSION in project.pbxproj is $VERSION"
fi
for locale in "${LOCALES[@]}"; do
  notes="$ROOT/release-notes/v$VERSION/$locale.txt"
  [[ -s $notes ]] || fail "missing release notes: release-notes/v$VERSION/$locale.txt"
done
echo "Release $VERSION is consistent"
[[ -n ${GITHUB_ENV:-} ]] && echo "RELEASE_VERSION=$VERSION" >> "$GITHUB_ENV"
exit 0
