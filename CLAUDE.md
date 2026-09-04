# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

FoodTracker — a Kotlin Multiplatform grocery/expiration-date tracker with a Jetpack Compose Android app and a native SwiftUI iOS app. There is no remote backend or login — pantry data lives only in memory on-device and does not persist across app restarts. Barcodes are scanned (CameraX + ML Kit on Android), looked up against the Open Food Facts API, and expiration dates are extracted either from GS1 Application Identifiers in the barcode itself or via on-device OCR of the printed date (ML Kit Text Recognition on Android, Apple Vision on iOS).

## Commands

Requires JDK 17. Modules: `:shared` (KMP), `:androidApp`.

```bash
./gradlew :shared:test                                    # shared-module unit tests (JVM/Android target)
./gradlew :shared:testDebugUnitTest --tests "*ClassName*"  # single test class
./gradlew :androidApp:assembleDebug                        # build Android debug APK
./gradlew :androidApp:installDebug                         # build + install on connected device/emulator
xcodebuild -project iosApp/FoodTracker.xcodeproj -scheme FoodTracker \
  -destination 'generic/platform=iOS Simulator' build      # build iOS app
```

No Firebase, no `google-services.json`/`GoogleService-Info.plist`, no external setup needed — both apps run standalone with an in-memory, non-persisted pantry store. See "Data layer" below.

## Architecture

**iOS does not consume the `shared` KMP module.** Despite the module existing and building an `iosArm64`/`iosSimulatorArm64`/`iosX64` framework (`SharedApp`), nothing under `iosApp/` links or imports it — there is no `SharedApp` reference anywhere in the Xcode project or Swift sources. iOS is a fully independent, parallel reimplementation:

- `iosApp/FoodTracker/Models/LocalModels.swift` — Swift structs mirroring `shared`'s domain models (`PantryItem`, `StorageLocation`, `ProductInfo`), not generated from or bound to them.
- `iosApp/FoodTracker/ViewModels/NativePantryViewModel.swift` — reimplements the filtering/counting logic from `PantryStateHolder.kt` by hand, calls the Open Food Facts REST API directly with `URLSession` (bypassing the shared Ktor client and its `OpenFoodFactsApi`), and is in-memory only — no GS1 barcode parsing, no shared `ExpiryNotificationCalculator` (it schedules local notifications itself via `IOSNotificationManager`).

**Practical implication:** a change to domain logic, parsing rules, or notification timing in `shared/` (GS1 parsing, date-OCR heuristics, expiry-status thresholds, notification scheduling) has no effect on iOS and must be ported to the corresponding Swift file by hand if iOS behavior should match. Don't assume "shared" code is shared with iOS — verify by grepping `iosApp/` before relying on it.

**Data layer** (`shared/.../repository/`): `PantryRepository` is the interface; `InMemoryPantryRepository` is its only implementation, wired by `FoodTrackerApp.kt`. It holds items in a `MutableStateFlow` with no persistence — data is lost on process death and there is no sync between devices or platforms. There used to be a Firestore-backed implementation and matching iOS Firebase integration; both were removed (no remote backend, no login) and should not be reintroduced without being asked.

**State**: on Android, `PantryStateHolder` (in `shared`, platform-agnostic) exposes a single `PantryUiState` via `StateFlow`, driven by collecting `PantryRepository.getItemsFlow()`. Filtering/search/location/status-filter state is applied client-side in `computeFiltered`, not pushed to the repository. Scan flow (barcode → GS1 date extraction → Open Food Facts lookup → optional OCR date override) is also modeled as fields on this same state object rather than a separate view model.

**Barcode/OCR pipeline** (Android): `androidApp/.../camera/BarcodeAnalyzer.kt` and `TextOcrAnalyzer.kt` are CameraX `ImageAnalysis.Analyzer` implementations feeding ML Kit. A scanned barcode first goes through `GS1BarcodeParser.extractExpiryDate` (shared module) to try to pull an expiry date straight out of AI(17)/AI(15) segments before falling back to product lookup + manual/OCR date entry. `DateOcrParser.parseBestDate` (shared module) scores multiple date-format regexes per OCR line, preferring lines near a keyword (EXP, BB, BEST BEFORE, etc.) and multilingual month names.

**Data layer conventions**: shared domain models live under `shared/.../model/`.
