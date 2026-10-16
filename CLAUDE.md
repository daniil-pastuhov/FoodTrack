# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

FoodTracker — a Kotlin Multiplatform grocery/expiration-date tracker with a Jetpack Compose Android app and a native SwiftUI iOS app. There is no remote backend or login (Firebase Crashlytics, for crash reports, is the only Firebase product; see "Crash reporting" below). Pantry data persists on-device only, via a local database on each platform (Room on Android, SwiftData on iOS — see "Data layer" below); there is no sync between devices or platforms. Barcodes are scanned (CameraX + ML Kit on Android), looked up against the Open Food Facts API, and expiration dates are extracted either from GS1 Application Identifiers in the barcode itself or via on-device OCR of the printed date (ML Kit Text Recognition on Android, Apple Vision on iOS).

## Commands

Requires JDK 17. Modules: `:shared` (KMP), `:androidApp`.

```bash
./gradlew :shared:compileTestKotlinIosSimulatorArm64        # shared-module commonTest, compiled (no Android host-test target registered — `:shared:test` does not exist)
./gradlew :androidApp:testDebugUnitTest                      # androidApp unit tests (includes Room DAO tests, via Robolectric)
./gradlew :androidApp:testDebugUnitTest --tests "*ClassName*"  # single test class
./gradlew :androidApp:assembleDebug                        # build Android debug APK
./gradlew :androidApp:installDebug                         # build + install on connected device/emulator
xcodebuild -project iosApp/FoodTracker.xcodeproj -scheme FoodTracker \
  -destination 'generic/platform=iOS Simulator' build      # build iOS app
```

**Releases** are cut with `scripts/release`, not by pushing to main: it bumps the version in `Version.xcconfig` (the single version of both apps: `androidApp/build.gradle.kts` reads it for `versionName`, and it is the Xcode project's base configuration, so no target sets `MARKETING_VERSION` itself) on a `release/vX.Y.Z` branch and writes `release-notes/vX.Y.Z/<play-locale>.txt`. CI (`.github/workflows/ci.yml`) publishes pushes to `release/v*` to Play internal and TestFlight, and on the PR's merge promotes that build to Play closed testing and the external TestFlight group, and tags `vX.Y.Z`. Main publishes nothing. See "Releasing" in README.md.

The Firebase configs are committed (`androidApp/google-services.json`, `iosApp/FoodTracker/GoogleService-Info.plist`); no other external setup is needed. See "Data layer" below for what actually persists where.

## Architecture

**iOS does not consume the `shared` KMP module.** Despite the module existing and building an `iosArm64`/`iosSimulatorArm64`/`iosX64` framework (`SharedApp`), nothing under `iosApp/` links or imports it — there is no `SharedApp` reference anywhere in the Xcode project or Swift sources. iOS is a fully independent, parallel reimplementation:

- `iosApp/FoodTracker/Models/LocalModels.swift` — Swift structs mirroring `shared`'s domain models (`PantryItem`, `StorageLocation`, `ProductInfo`), not generated from or bound to them.
- `iosApp/FoodTracker/ViewModels/NativePantryViewModel.swift` — reimplements the filtering/counting logic from `PantryStateHolder.kt` by hand, calls the Open Food Facts REST API directly with `URLSession` (bypassing the shared Ktor client and its `OpenFoodFactsApi`) — no GS1 barcode parsing, no shared `ExpiryNotificationCalculator` (it schedules local notifications itself via `IOSNotificationManager`).

**Practical implication:** a change to domain logic, parsing rules, or notification timing in `shared/` (GS1 parsing, date-OCR heuristics, expiry-status thresholds, notification scheduling) has no effect on iOS and must be ported to the corresponding Swift file by hand if iOS behavior should match. Don't assume "shared" code is shared with iOS — verify by grepping `iosApp/` before relying on it.

**Data layer**: Android and iOS each persist locally through their own platform database — there is no shared schema or sync between them, consistent with iOS not consuming `shared` (above).

- Android: `PantryRepository` is a platform-agnostic interface in `shared/.../repository/`. Its only implementation is `RoomPantryRepository` (`androidApp/.../data/local/`), backed by a Room database (`FoodTrackerDatabase`, `exportSchema = false`). Room lives in `androidApp`, not `shared`, because `shared`'s Android target uses AGP 9's built-in-Kotlin compilation, which KSP (Room's annotation processor) only supports there via `com.google.devtools.ksp` 2.3.1+ using the plain `<kspVersion>` scheme (no `<kotlinVersion>-` prefix) — `androidApp` applies that plugin.
  - **Every schema change ships with a real `Migration`** (`MIGRATION_<old>_<new>` in `FoodTrackerDatabase`, registered in `AppModule`'s `addMigrations` and in every `FoodTrackerMigrationTest` builder, with a test that asserts the upgraded rows). Never bump `version` alone, and never use `fallbackToDestructiveMigration`. The pantry is the user's only copy of their data, so an upgrade must never wipe it. The only exception is when the user explicitly asks, in the current session, to skip the migration.
  - On iOS, the equivalent rule is: a new stored property on a SwiftData `@Model` (e.g. `PantryItemRecord`) needs a default value so the store migrates in place.
- iOS: `NativePantryViewModel` persists through SwiftData. `PantryItemRecord` (`iosApp/FoodTracker/Models/PantryItemRecord.swift`) is the `@Model` class Room's `PantryItemEntity` doesn't have a counterpart for elsewhere — the view model converts to/from the plain `PantryItem` struct on every read/write (`asPantryItem` / `init(from:)` / `update(from:)`) so the rest of `iosApp/` keeps working with `PantryItem` unchanged. The `ModelContainer` is a `nonisolated static let` (`NativePantryViewModel.sharedModelContainer`) so it can be used as a default-argument value from a `@MainActor` init without a Swift 6 isolation error.
- There used to be a Firestore-backed implementation and matching iOS Firebase integration; both were removed (no remote backend, no login) and should not be reintroduced without being asked — Room and SwiftData are both local-only and don't change that. Crashlytics (above) sends crash reports only, never pantry data.

**Crash reporting**: Firebase Crashlytics on both platforms, in the Firebase project `bestbefore-f9ee1`, and nothing else from Firebase (no Analytics, no Firestore).

- Android: the `com.google.gms.google-services` and `com.google.firebase.crashlytics` plugins in `androidApp`; the latter uploads the R8 mapping on release builds. Collection is off in debug builds through the `firebase_crashlytics_collection_enabled` manifest meta-data (`manifestPlaceholders["crashlyticsCollectionEnabled"]`). Don't call `setCrashlyticsCollectionEnabled` in code: its value persists on the device and would override the manifest.
- iOS: the `FirebaseCrashlytics` Swift package (pinned in `project.xcworkspace/xcshareddata/swiftpm/Package.resolved`, the one workspace file `.gitignore` lets through). `FoodTrackerApp.init` calls `FirebaseApp.configure()` only in non-DEBUG builds, and the "Upload Crashlytics dSYMs" run-script phase skips Debug to match. The widget extension doesn't link Firebase.

**DI**: Koin, wired in `androidApp/.../di/AppModule.kt` (`appModule`) and started in `FoodTrackerApp.onCreate`. `MainActivity` resolves `PantryStateHolder` via `by inject()`. The `shared` module itself has no Koin dependency — `PantryStateHolder`'s constructor just takes plain interface params, so it stays constructible on iOS (if iOS ever adopts `shared`) without pulling in an Android-only DI graph.

**State**: on Android, `PantryStateHolder` (in `shared`, platform-agnostic) exposes a single `PantryUiState` via `StateFlow`, driven by collecting `PantryRepository.getItemsFlow()`. Filtering/search/location/status-filter state is applied client-side in `computeFiltered`, not pushed to the repository. Scan flow (barcode → GS1 date extraction → Open Food Facts lookup → optional OCR date override) is also modeled as fields on this same state object rather than a separate view model.

**Barcode/OCR pipeline** (Android): `androidApp/.../camera/BarcodeAnalyzer.kt` and `TextOcrAnalyzer.kt` are CameraX `ImageAnalysis.Analyzer` implementations feeding ML Kit. A scanned barcode first goes through `GS1BarcodeParser.extractExpiryDate` (shared module) to try to pull an expiry date straight out of AI(17)/AI(15) segments before falling back to product lookup + manual/OCR date entry. `DateOcrParser.parseBestDate` (shared module) scores multiple date-format regexes per OCR line, preferring lines near a keyword (EXP, BB, BEST BEFORE, etc.) and multilingual month names.

**Data layer conventions**: shared domain models live under `shared/.../model/`.
