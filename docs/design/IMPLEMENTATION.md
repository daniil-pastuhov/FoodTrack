# Implementing Larder: the shared contract

Spec: `docs/design/design.html` (built from `src/pages/`), `tokens.json`, and the decisions in `brief.md`. Where the code and the design differ, the design wins unless `brief.md` says otherwise. The approved defaults are at the end of this file.

## Branches
Integration branch: `feat/larder-design`. Each agent works in its own worktree on `larder/<name>`, branched from it, and commits there. The coordinator merges into the integration branch. Agents never push, open PRs, or merge into it themselves. Never commit `docs/` changes or `scripts/__pycache__/`.

## Ownership
Coordinator only, after wave 0: `ui/theme/`, `ui/design/`, `iosApp/FoodTracker/DesignSystem/`, `FoodTrackerRoot.kt`, `MainActivity.kt`, `RootTabView.swift`, `AppRouter.swift`, `PantryStateHolder.kt`, `NativePantryViewModel.swift`, the data layer, `tokens.json`, `scripts/gen_design_tokens.py`, `project.pbxproj` merges.
If you need a change there, send the coordinator a message saying exactly what and why, and carry on with other work. Don't work around it locally. Generated files (`LarderTokens.kt`, `larder_colors.xml`, `Assets.xcassets/Larder/`, `LarderTokens.swift`) are never edited by hand.

## Names, the same on both platforms
Components: `LarderButton` (primary, secondary, outline, text, danger, dangerText), `LarderTextField`, `LarderChip` (single choice, or multi-choice with a check when on), `LarderRangeSlider` (two handles; Material 3 RangeSlider on Android, drawn by hand on iOS), `LarderTick`, `LarderSwitch`, `LarderStepper`, `LarderSegmented`, `StatusChip`, `PantryRow`, `LarderGroup`, `SectionHeader`, `LarderCard`, `LarderCallout` (info, warning, error, success), `LarderSnackbar`, `LarderDialog`, `LarderSheet`, `EmptyState`, `ScreenError`, `LedeHeader`, `ScanFab`, `FloatingNav`, `LarderIcon`.
Android lives in `androidApp/.../ui/design/`, iOS in `iosApp/FoodTracker/DesignSystem/`. Tokens: `MaterialTheme` plus `LarderTheme.colors` on Android, `LarderColor` on iOS.
Widths: `isExpanded` is true at 700dp/pt and up, read from window width. Two-pane layouts use it. There is no navigation rail or sidebar on any device.

## Routes
Tabs: home, recipe, shopping, settings. Pushed or modal: recipeResult(id), savedRecipes, recipeSettings, buyRecipes (sheet), productDetail(itemId) (sheet on phone, right pane on expanded Home), addItem(new | prefilled | edit), scanner(mode), categories, onboarding.

## State additions (Wave 0)
- Lookup result: Found(product), NotFound, Offline.
- `onboardingCompleted` in settings. Treated as done for people upgrading with items in the pantry.
- `deleteItems(ids)` returns what's needed for one Undo, `restoreItems(...)` undoes it.
- Home confirmation events: Saved(name, location), BatchAdded(count), OneMoreAdded(name, quantity).
`shared/` is Android only, so every logic change is ported to `NativePantryViewModel.swift` by hand.

## Rules
- Tokens and components only: no literal colours, sizes or fonts in screens.
- Lucide icons through `LarderIcon`. Vollkorn for display, headline and title styles; system fonts elsewhere; both scale with system text size.
- 48dp and 44pt minimum targets. Every status has an icon and words.
- Reversible actions are immediate with Undo. Dialogs only for irreversible actions or real choices.
- New strings go in en, ru and nb, in your feature's commented block, and use the design's ru copy where it exists. Your report lists every new ru and nb string.
- `@Composable` previews per component state.
- No Room or SwiftData schema change is expected. If you think one is needed, ask the coordinator first.
- Never uninstall apps or reset data on existing simulators or emulators; use a new instance. Check the screen before tapping, and stay inside BestBefore.

## Verify before reporting
`./gradlew :shared:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug` (JDK 17) and the `xcodebuild` simulator build in CLAUDE.md, as far as your platform is affected. In a fresh Gradle home set `GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches`. Report real results and anything not verified.

## Approved defaults
`found` hides "In your pantry" at zero. Quantity limit 99 on both platforms. iOS Add item stays a modal with Cancel. Add item title is "Add item". Per-item notification channel gets a new id at default importance. Widget tick stays a ring with a check; in-app tick keeps its empty-ring off state. Blank Save and Import in Shopping stay disabled. A duplicate shopping name shows a T3 message. Scan is on Home only; on Recipe the primary action (`RecipeUiState.actions`) takes its place above the pill: full width when it has a cost, a content-width pill at the trailing edge when Share prompt is pinned alone (level with the pill on wide windows where it fits). Later, not now: fixing Check date from the queue, validating restore files on pick, the T4 after declining location, shop-arrival actions.

## Wave 1 and 2 notes
The foundation is merged. Read the source in `androidApp/.../ui/design/`, `ui/navigation/`, `iosApp/FoodTracker/DesignSystem/` and `AppRouter.swift` for component APIs before writing screens. Don't rebuild a component that exists.

Ownership, relaxed: you may ADD new methods and state to `PantryStateHolder.kt`, `NativePantryViewModel.swift` and the data layer, and add new files to `project.pbxproj`, without asking. Keep additions in one block. Changing or removing anything existing there, or anything in `ui/theme/`, `ui/design/`, `DesignSystem/`, routes, or the Root files, goes through the coordinator (message `main`), who applies it on `feat/larder-design` and tells everyone. The only exception is listed in your own brief. If a component needs a small new option, ask rather than forking it.

Useful:
- Android: `LocalLarderNavigator.current.navigate(route)`, `ShellChromeEffect(hideNav, hideScan)` to hide the pill or Scan, `LarderSwipeRow`, `shellClearance(...)`. Debug gallery: `adb shell am start -n com.foodtracker.android/.ui.design.DesignGalleryActivity`.
- iOS: `AppRouter.shared.navigate(to:)`, `.shellChromeEffect(hideNav:hideScan:)`, `@Environment(\.showSnackbar)`, `@Environment(\.isExpanded)`, `.larderSheet`, `.larderDialog`, `.larderDatePicker`, `.larderList()` with `.larderGroupRow`. Screens reserve no bottom padding; `RootTabView` applies it. Demo data and routes: `-demoBackup`, `-demoTab`, `-demoRoute`.
- After booting a new simulator, the app can show a blank window for about 2 minutes. Relaunch until it draws before trusting a screenshot.
- Use a new emulator or simulator for your own runs and delete it when done. Don't touch emulator-5554 or existing simulators.
- Scratch files go in your own uniquely named folder, because the scratchpad is shared.
- The design's mock-ups are the look; a platform's native sheet, alert and picker behaviour stays native (see the table on the App shell page).

## Devices (from wave 1)
This Mac can't carry many booted devices: a blank iPad and failed runs came from load averages over 800. Keep at most one simulator and one emulator running, shut them down when idle, and delete them before you report. Check the AVD or device name before every `adb` or `simctl` use: agents share this machine. Maestro is installed (`maestro test flow.yaml`, set `JAVA_HOME` to JDK 17 if needed), so iOS flows can be tapped: use it for your feature's main interactions on a simulator. Say what you couldn't exercise.
