# FoodTracker 🥫📅

A Kotlin Multiplatform (KMP) grocery and expiration date tracking app for **Android (Jetpack Compose)** and **iOS (Native SwiftUI)**.

## Features

- 📷 **Barcode Scanning**: Scan 1D and 2D barcodes (EAN-13, UPC, Code 128, DataBar, QR).
- 🏷️ **Automatic Product Lookup**: Queries the open [Open Food Facts API](https://world.openfoodfacts.org/) to fetch product names, brands, images, and categories.
- ⚡ **GS1 Barcode Expiration Extraction**: Decodes expiration dates directly from GS1 barcodes containing Application Identifiers `(17)` or `(15)`.
- 🤖 **On-Device Machine Learning Date OCR**:
  - Point your phone's camera at the printed expiration date stamp on any packaging (`EXP 24.11.2026`, `BEST BEFORE 15 NOV 2026`, `BB 05/12/26`).
  - Google ML Kit on Android and Apple Vision on iOS extract and parse the date in real time with 1-tap confirmation.
- 📸 **Product Photo Fallback**: Snap photos and manually name bakery, market produce, or custom items that lack barcodes.
- ⏱️ **Pantry Management Dashboard**:
  - Color-coded shelf-life urgency: **Expired** (Red), **Expiring Soon** (Amber, within 3 days), and **Fresh** (Green).
  - Filter by storage location (**Fridge**, **Pantry**, **Freezer**).
  - Search by name or brand.
  - Quick swipe actions to mark items as *Consumed* or *Delete*.

## Project Structure

- `shared/`: Kotlin Multiplatform shared domain, Open Food Facts Ktor client, GS1 parser, Date OCR parsing heuristics, the pantry state holder and repository interfaces. Only the Android app uses it.
- `androidApp/`: Native Android application built with Jetpack Compose (Material 3), CameraX, Google ML Kit and Room.
- `iosApp/`: Native iOS application built with SwiftUI, AVFoundation, Apple Vision and SwiftData. It is a separate implementation and does not link `shared/`, so a logic change on one platform has to be ported to the other by hand.

The pantry stays on the device: there is no backend, account or sync. The only network calls are barcode lookups on Open Food Facts and, in release builds, crash reports to Firebase Crashlytics.

## Building and Running

Requires JDK 17 for Android and Xcode for iOS. See [CONTRIBUTING.md](CONTRIBUTING.md) for the one-time setup (a debug keystore, and your own signing team for running on an iPhone).

### Run Unit Tests
```bash
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest
```

### Build Android App
```bash
./gradlew :androidApp:assembleDebug
```

### Build iOS App
```bash
xcodebuild -project iosApp/FoodTracker.xcodeproj -scheme FoodTracker -destination 'generic/platform=iOS Simulator' build
```

## Releasing

This section is for the maintainer: publishing needs the repository's store credentials.

Merging to `main` publishes nothing; CI only tests and builds it (a merged pull request other than a release one is not built again on `main`, as its pull request runs built it). A release goes out in two steps:

1. **Start it**: `scripts/release` (add `--dry-run` to try it without changing anything). It
   lists the commits on `origin/main` since the last `vX.Y.Z` tag and asks for a patch, minor or
   major bump. Then it has the Claude CLI write store release notes in every listing language
   (en-GB, ru-RU, no-NO) for you to accept, edit or regenerate. It creates
   `release/vX.Y.Z` with the version bump in `Version.xcconfig` and
   `release-notes/vX.Y.Z/<locale>.txt`, pushes it and opens a PR.
2. **Internal testing**: CI publishes every push to `release/v*` to the Google Play internal track
   and to the internal TestFlight testers, with those notes. Fixes for the release go on the
   branch; each push becomes a new internal build.
3. **Play closed testing and the App Store**: merging the PR promotes the newest internal Play
   build of that version to closed testing (`alpha`). It also puts the newest TestFlight build of
   the version on its App Store version, with the notes as "What's New", and submits it for App
   Review; it goes live as soon as Apple approves it (set the repository variable
   `APP_STORE_RELEASE_TYPE` to `MANUAL` to release it from App Store Connect yourself). Then it
   tags `vX.Y.Z` and creates a GitHub release.

**Version**: `Version.xcconfig` holds the one version of both apps. Gradle reads it for Android's
`versionName`, and it is the Xcode project's base configuration, so the app and the widget get it
as `MARKETING_VERSION`. Don't set the version anywhere else.

Needs `gh` (logged in) and `claude` on your PATH. Play rejects notes longer than 500 characters
per language, and the script checks that. App Review needs the version page in App Store Connect
complete (App Privacy, App Review contact); the job says so if Apple refuses the submission.

## Contributing

Issues and pull requests are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). To report a security problem, see [SECURITY.md](SECURITY.md).

## License

FoodTracker is licensed under the [Apache License 2.0](LICENSE).

The license covers the code. It does not cover the "BestBefore" name, the app icon or the store-listing artwork in `store-listing/`. Please use your own name and icon if you publish a fork to an app store.

The app bundles the Vollkorn typeface (SIL Open Font License 1.1) and Lucide icons (ISC License). Their licence texts ship in the app, in `androidApp/src/main/assets/licenses/`.

Product data and pictures come from [Open Food Facts](https://world.openfoodfacts.org/), under the Open Database License (database) and Creative Commons Attribution-ShareAlike (pictures).
