#!/bin/sh
# Checks Batch mode's one pass (a barcode scan, then the printed date read onto that scan's row):
#   iosApp/BatchDateReadCheck/check.sh
# Compiles iosApp/FoodTracker/Models/BatchScanEntry.swift and ShelfLife.swift (with a stub of the
# date plausibility window) outside the Xcode project and runs main.swift. Needs only the Xcode
# toolchain; not run by CI, so run it after touching BatchDateRead.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
swiftc -swift-version 5 -o "$work/check" \
  "$root/iosApp/FoodTracker/Models/BatchScanEntry.swift" \
  "$root/iosApp/FoodTracker/Models/ShelfLife.swift" \
  "$here/main.swift"
"$work/check"
