#!/bin/bash
# Checks that a release branch is consistent before CI publishes it, and exports RELEASE_VERSION
# for later steps. Used by .github/workflows/ci.yml with the version from the branch name:
#
#   scripts/check_release_version.sh 1.3.0
#
# Version.xcconfig (the one version of both the Android and the iOS app) and the release notes
# have to match the branch, since a hand edit can leave one behind.
set -euo pipefail

VERSION=${1:?usage: $0 X.Y.Z}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
LOCALES=(en-GB ru-RU no-NO)
fail() { echo "::error::$1"; exit 1; }

[[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$VERSION' is not a X.Y.Z version"
grep -qx "MARKETING_VERSION = $VERSION" "$ROOT/Version.xcconfig" ||
  fail "Version.xcconfig is not MARKETING_VERSION = $VERSION"
# A MARKETING_VERSION in the Xcode project would override Version.xcconfig for that target.
if grep -q "MARKETING_VERSION" "$ROOT/iosApp/FoodTracker.xcodeproj/project.pbxproj"; then
  fail "project.pbxproj sets MARKETING_VERSION; keep the version in Version.xcconfig only"
fi
for locale in "${LOCALES[@]}"; do
  notes="$ROOT/release-notes/v$VERSION/$locale.txt"
  [[ -s $notes ]] || fail "missing release notes: release-notes/v$VERSION/$locale.txt"
done
echo "Release $VERSION is consistent"
[[ -n ${GITHUB_ENV:-} ]] && echo "RELEASE_VERSION=$VERSION" >> "$GITHUB_ENV"
exit 0
