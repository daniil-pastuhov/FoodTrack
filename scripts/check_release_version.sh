#!/bin/bash
# Checks that a release branch is consistent before CI publishes it, and exports RELEASE_VERSION
# and RELEASE_PLATFORM for later steps. Used by .github/workflows/ci.yml with the version from the
# branch name, and by each job that publishes one platform with that platform too:
#
#   scripts/check_release_version.sh 1.3.0           # consistent? prints the platform(s)
#   scripts/check_release_version.sh 1.3.0 android   # ... and does the release ship Android?
#
# Version.xcconfig (the one version of both the Android and the iOS app) and the release notes
# have to match the branch, since a hand edit can leave one behind. A release of one platform only
# has release-notes/vX.Y.Z/platform, written by scripts/release --platform, holding "android" or
# "ios"; without it the release is for both (every release before platform-only ones). Anything
# else in it fails, so a typo can't publish the wrong platform.
set -euo pipefail

VERSION=${1:?usage: $0 X.Y.Z [android|ios]}
WANTED=${2:-}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
# As scripts/release writes them.
LOCALES=(en-GB ru-RU no-NO es-ES es-419 de-DE fr-FR fr-CA sv-SE da-DK)
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
PLATFORM=both
platform_file="$ROOT/release-notes/v$VERSION/platform"
if [[ -e $platform_file ]]; then
  PLATFORM=$(<"$platform_file")
  [[ $PLATFORM == android || $PLATFORM == ios ]] ||
    fail "release-notes/v$VERSION/platform must be 'android' or 'ios', not '$PLATFORM'"
fi
case $WANTED in
  "") ;;
  android|ios)
    [[ $PLATFORM == both || $PLATFORM == "$WANTED" ]] ||
      fail "Release $VERSION is for $PLATFORM only, not $WANTED" ;;
  *) fail "'$WANTED' is not android or ios" ;;
esac
echo "Release $VERSION ($PLATFORM) is consistent"
if [[ -n ${GITHUB_ENV:-} ]]; then
  echo "RELEASE_VERSION=$VERSION" >> "$GITHUB_ENV"
  echo "RELEASE_PLATFORM=$PLATFORM" >> "$GITHUB_ENV"
fi
[[ -n ${GITHUB_OUTPUT:-} ]] && echo "platform=$PLATFORM" >> "$GITHUB_OUTPUT"
exit 0
