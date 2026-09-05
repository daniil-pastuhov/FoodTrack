# Task: Home-screen widgets (Android + iOS)

## Context

This is the last of a batch of 10 feature/improvement decisions from a project review
(commit `60e8956` on `main` already ships the other 9: removed the unused `ItemStatus`,
per-item `quantity` with a decrement action, GS1 parsing ported to iOS, a daily digest
notification, a sort picker, the flashlight toggle, `category` as a filter, and shopping-list
grouping/quantity). Read that commit if you want the full picture of what already changed —
this task only covers what's left.

**Decision from the user:** two widgets, on **both** Android and iOS:
1. An "expiring/expired items" widget — shows what's expired or about to expire.
2. A shopping list widget.

This is a different kind of work than the rest of that commit: it needs a brand-new Android
Glance widget module/package and, on iOS, an entirely new WidgetKit **extension target** in
the Xcode project. Both are riskier than the single-file edits the rest of the batch was, so
this was deliberately deferred to its own session.

## What already exists to build on

- **Android data layer**: `FoodTrackerDatabase` (Room, currently version 3) — `PantryDao` /
  `ShoppingListDao` in `androidApp/.../data/local/`. `PantryItem.quantity`, `.category`,
  `.expiryDate` and `ExpiryStatus`/`DateUtils.today()` (shared module) are what a widget would
  read to compute "expired" / "expiring soon" counts, matching `PantryStateHolder`'s own logic
  (`shared/.../viewmodel/PantryStateHolder.kt`) — don't reinvent that threshold logic, mirror it.
- **iOS data layer**: `NativePantryViewModel.sharedModelContainer` (a `nonisolated static let`)
  is the existing pattern for getting a `ModelContext` without a live view model instance —
  `IOSNotificationManager.deleteItemDirectly` (added in the last commit) already does exactly
  this from outside the main view hierarchy. A widget extension needs its own equivalent access
  to the same on-disk SwiftData store, which requires an **App Group** (see iOS section below).
- **Settings**: `AppSettings.expiringSoonDays` (shared model / `LocalModels.swift`) is the
  existing user-configurable threshold for "expiring soon" — widgets should respect it, not
  hardcode a window.
- **Shopping list model**: `ShoppingListEntry` now has `category` and `quantity` on both
  platforms — the shopping list widget should probably just show the flat list (name + qty),
  not necessarily re-implement the category grouping the in-app screen does.

## Android: Glance widget module

- Add `androidx.glance:glance-appwidget` (check current stable version) as a dependency of
  `androidApp` (or a new `:androidApp:widget` module if you want build-time isolation — not
  required, Glance widgets can live directly in `androidApp`).
- Two `GlanceAppWidget`/`AppWidgetReceiver` pairs (one per widget), each with:
  - `appwidget-provider` XML metadata (`res/xml/`) — min size, resize mode, update period
    (note: Glance widgets should rely on app-triggered updates, e.g. after a save/delete, rather
    than the coarse `android:updatePeriodMillis`, which has a 30-min floor).
  - A Glance `@Composable` layout reading from `PantryDao`/`ShoppingListDao` directly (Glance
    widgets run in the app's own process via `GlanceAppWidgetService`, so this can reuse the
    existing `RoomPantryRepository`/`RoomShoppingListRepository` through Koin, same as the rest
    of the app — no cross-process IPC needed on Android, unlike iOS).
- Trigger `GlanceAppWidgetManager(context).updateAll(...)` (or a targeted update) from
  `PantryStateHolder`'s save/delete/decrement paths, or simpler: from `RoomPantryRepository`'s
  Flow collectors, so the widget refreshes promptly instead of waiting for the OS's own cadence.
- Register both `AppWidgetReceiver` subclasses in `AndroidManifest.xml`.

## iOS: WidgetKit extension target

This is the fiddly part — a new Xcode target is not a single-file addition.

1. **New target**: add a `PBXNativeTarget` of product type
   `com.apple.product-type.app-extension` (Widget Extension template) to
   `iosApp/FoodTracker.xcodeproj/project.pbxproj` — new target, new product reference, its own
   `Info.plist` (`NSExtension` dict with `NSExtensionPointIdentifier =
   com.apple.widgetkit-extension`), its own `XCBuildConfiguration`s, and an
   "Embed App Extensions" build phase on the main `FoodTracker` target referencing it.
   Hand-editing this is much easier to get subtly wrong than the single-file `PBXFileReference`
   additions done in the last commit (e.g. adding `GS1BarcodeParser.swift`) — verify with a real
   `xcodebuild build` (see Verification below), not just SourceKit diagnostics, which were
   unreliable for cross-file resolution throughout the last session but did catch real errors
   when an actual build was run.
2. **App Group**: the widget extension runs in its own process/sandbox and cannot otherwise read
   the main app's SwiftData store. Add an App Group entitlement (e.g.
   `group.com.foodbest.ios`) to **both** the main app target and the new widget extension
   target (`.entitlements` files + `com.apple.security.application-groups` in each target's
   `XCBuildConfiguration`). **This may need a manual step in Xcode / the Apple Developer
   portal** (registering the App Group under the team, `DEVELOPMENT_TEAM = S8Q8KHX6G5` per the
   current project) that a text-only edit can't fully complete — flag this to the user rather
   than assuming it's done once the entitlements files exist.
3. Point `NativePantryViewModel.sharedModelContainer`'s `ModelConfiguration` at the App Group's
   shared container URL (`FileManager.default.containerURL(forSecurityApplicationGroupIdentifier:)`)
   instead of the default location, so both the app and the widget open the same store. This is
   a behavior change to an existing, working call site — read `NativePantryViewModel.swift`
   carefully before touching it, and check whether `IOSNotificationManager` (which also opens
   `ModelContext(NativePantryViewModel.sharedModelContainer)` directly) still behaves correctly.
4. Widget code: a `TimelineProvider` per widget (expiring/expired items, shopping list) computing
   entries from the shared store, plus SwiftUI views for the small/medium widget families.
5. Localize any new widget-facing strings the same way the rest of the app does — add entries
   to `L10n.swift` and all three `.strings`/`.stringsdict` files (en/nb/ru), not just English.

## Verification

- Android: `./gradlew :androidApp:assembleDebug` and `:androidApp:testDebugUnitTest`; then
  actually add both widgets to a home screen (emulator or device) and confirm they update after
  adding/removing a pantry item or shopping-list entry.
- iOS: `xcodebuild -project iosApp/FoodTracker.xcodeproj -scheme FoodTracker -destination
  'generic/platform=iOS Simulator' build` (this is what caught real errors last session — the
  IDE's inline Swift diagnostics were unreliable noise throughout that session and should not be
  trusted on their own). Then run in the simulator and add both widgets via the widget gallery.
- Gradle builds were blocked by the permission mode for part of the last session and then
  became available later in the same session with no explained change — if blocked again, ask
  the user to grant Bash permission rather than assuming the work can't be verified.
