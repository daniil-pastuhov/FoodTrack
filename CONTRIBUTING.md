# Contributing to FoodTracker

Thanks for helping out. Bug reports, fixes and translations are all welcome. For a larger feature,
please open an issue first so we can agree on the approach before you put time into it.

## Setup

**Android** (JDK 17):

1. Create the debug keystore. The debug build signs with the key alias `debug`, and the Android
   Gradle Plugin only generates a keystore for its own default alias. You only need to do this once:

   ```bash
   mkdir -p ~/.android
   keytool -genkeypair -keystore ~/.android/debug.keystore -storepass android -keypass android \
     -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```

   If you already have a `~/.android/debug.keystore` with a different alias, you can add the
   `debug` alias to it with the same command, or back that file up first.
2. `./gradlew :androidApp:installDebug`

**iOS** (Xcode): the simulator build needs no setup. To run on a physical device, choose your own
team under Signing & Capabilities for both the `FoodTracker` and `FoodTrackerWidget` targets, and
change the bundle IDs and the App Group (`group.com.foodbest.ios`) to ones your team can register.
Please leave those changes out of your pull request.

**Crash reports**: debug builds never send crash reports. Release builds send them to the
maintainer's Firebase project. If you publish a fork, replace `androidApp/google-services.json` and
`iosApp/FoodTracker/GoogleService-Info.plist` with configs from your own Firebase project.

**Recipes from packs** (the paid route) don't work against the real backend in your builds. Debug
builds on both platforms show it, with the App Check debug provider, but the server refuses calls
without an App Check debug token registered in the maintainer's console, and on iOS it always
rejects purchases from Xcode's `Recipes.storekit` test products. A release build shows it only
when installed from Google Play (Android) or on a device with App Attest (iOS). The free routes, the
shared prompt and the phone's own model, work everywhere. To work on the paid route, run the backend locally with
fake stores and a fake model (`functions/README.md`, "Running the functions for the apps") and
point a debug build at it: `./gradlew :androidApp:installDebug -PrecipeEmulatorHost=10.0.2.2` on
the Android emulator (a fake store whose purchases the emulator grants), or the launch argument
`-recipeEmulatorHost 127.0.0.1` on the iOS simulator (the `Recipes.storekit` products, which the
emulator accepts as sandbox purchases). Neither needs an App Check token.

**Backend** (`functions/`, Node.js 24, and Java 21+ for the Firestore emulator):
`cd functions && npm ci && npm run lint && npm test`. The tests run against the emulators under
the `demo-foodtracker` project id and never reach a real Firebase project.

## Before you open a pull request

```bash
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest
xcodebuild -project iosApp/FoodTracker.xcodeproj -scheme FoodTracker \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
(cd functions && npm ci && npm run lint && npm test)   # if you changed functions/
```

[CLAUDE.md](CLAUDE.md) describes the architecture in detail. The rules that matter most:

- **The iOS app does not use `shared/`.** If you change behaviour such as date parsing, GS1
  parsing, expiry thresholds or notifications, make the same change in the Swift code, or say in
  the pull request that it is Android-only.
- **Never lose user data.** The pantry is the user's only copy. Every Room schema change needs a
  real `Migration`, registered in `AppModule` and covered by `FoodTrackerMigrationTest`. Never use
  `fallbackToDestructiveMigration`. On iOS, a new stored property on a SwiftData `@Model` needs a
  default value.
- **No new network calls or tracking.** The app talks to Open Food Facts, Firebase Crashlytics
  for crash reports, the app stores for purchases and, only once someone starts a purchase, the
  recipe backend (Firebase Auth, App Check and the Cloud Functions in `functions/`). Nothing
  else, and nothing at all from the free routes. Pantry data leaves the phone only as the items
  someone ticks for a recipe from a pack.
- **Purchase and buyer records are never deleted.** A `purchases/` document in Firestore is the
  only thing that stops one store purchase being redeemed twice; account deletion detaches it from
  the user and keeps it. A `buyers/` document is the record of refunds after use that turns buying
  off. A change to the wire format goes into `functions/src/contract.ts`, `functions/CONTRACT.md`
  and both clients together.
- **Name rules live in three places.** The server's `functions/src/core/inputSafety.ts`, the
  shared `RecipeWireName.kt` and iOS `RecipeWire.swift` must accept and clean pantry and allergen
  names the same way (CONTRACT.md, "Name rules"); change and test all three.
- **Costs come from the server.** What a recipe or a menu takes is in every response with a
  balance; never hard-code it in an app.
- **Strings come in three languages**: English, Norwegian Bokmål and Russian, on both platforms.
  If you can't translate a new string, add the English text in every language and mention it in
  the pull request.

## Releases

Only the maintainer cuts releases (`scripts/release`; see "Releasing" in README.md). Please don't
change `Version.xcconfig` or `release-notes/` in a pull request.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
