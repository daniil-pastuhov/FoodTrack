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

## Before you open a pull request

```bash
./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest
xcodebuild -project iosApp/FoodTracker.xcodeproj -scheme FoodTracker \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

[CLAUDE.md](CLAUDE.md) describes the architecture in detail. The rules that matter most:

- **The iOS app does not use `shared/`.** If you change behaviour such as date parsing, GS1
  parsing, expiry thresholds or notifications, make the same change in the Swift code, or say in
  the pull request that it is Android-only.
- **Never lose user data.** The pantry is the user's only copy. Every Room schema change needs a
  real `Migration`, registered in `AppModule` and covered by `FoodTrackerMigrationTest`. Never use
  `fallbackToDestructiveMigration`. On iOS, a new stored property on a SwiftData `@Model` needs a
  default value.
- **No new network calls or tracking.** The app talks to Open Food Facts and, for crash reports,
  Firebase Crashlytics. Nothing else.
- **Strings come in three languages**: English, Norwegian Bokmål and Russian, on both platforms.
  If you can't translate a new string, add the English text in every language and mention it in
  the pull request.

## Releases

Only the maintainer cuts releases (`scripts/release`; see "Releasing" in README.md). Please don't
change `Version.xcconfig` or `release-notes/` in a pull request.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
