# Implementing Larder: the shared contract

Spec: `docs/design/design.html` (built from `src/pages/`), `tokens.json`, and the decisions in `brief.md`. Where the code and the design differ, the design wins unless `brief.md` says otherwise. The approved defaults are at the end of this file.

## Branches
Integration branch: `feat/larder-design`. Each agent works in its own worktree on `larder/<name>`, branched from it, and commits there. The coordinator merges into the integration branch. Agents never push, open PRs, or merge into it themselves. Never commit `docs/` changes or `scripts/__pycache__/`.

## Ownership
Coordinator only, after wave 0: `ui/theme/`, `ui/design/`, `iosApp/FoodTracker/DesignSystem/`, `FoodTrackerRoot.kt`, `MainActivity.kt`, `RootTabView.swift`, `AppRouter.swift`, `PantryStateHolder.kt`, `NativePantryViewModel.swift`, the data layer, `tokens.json`, `scripts/gen_design_tokens.py`, `project.pbxproj` merges.
If you need a change there, send the coordinator a message saying exactly what and why, and carry on with other work. Don't work around it locally. Generated files (`LarderTokens.kt`, `larder_colors.xml`, `Assets.xcassets/Larder/`, `LarderTokens.swift`) are never edited by hand.

## Names, the same on both platforms
Components: `LarderButton` (primary, secondary, outline, text, danger, dangerText), `LarderTextField`, `LarderChip`, `LarderTick`, `LarderSwitch`, `LarderStepper`, `LarderSegmented`, `StatusChip`, `PantryRow`, `LarderGroup`, `SectionHeader`, `LarderCard`, `LarderCallout` (info, warning, error, success), `LarderSnackbar`, `LarderDialog`, `LarderSheet`, `EmptyState`, `ScreenError`, `LedeHeader`, `ScanFab`, `FloatingNav`, `LarderIcon`.
Android lives in `androidApp/.../ui/design/`, iOS in `iosApp/FoodTracker/DesignSystem/`. Tokens: `MaterialTheme` plus `LarderTheme.colors` on Android, `LarderColor` on iOS.
Widths: `isExpanded` is true at 700dp/pt and up, read from window width. Two-pane layouts use it. There is no navigation rail or sidebar on any device.

## Routes
Tabs: home, calendar, shopping, settings. Pushed or modal: productDetail(itemId) (sheet on phone, right pane on expanded Home), addItem(new | prefilled | edit), scanner(mode), categories, onboarding.

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
`found` hides "In your pantry" at zero. Quantity limit 99 on both platforms. iOS Add item stays a modal with Cancel. Add item title is "Add item". Per-item notification channel gets a new id at default importance. Widget tick stays a ring with a check; in-app tick keeps its empty-ring off state. Blank Save and Import in Shopping stay disabled. A duplicate shopping name shows a T3 message. Scan is on Home and Calendar only. Later, not now: fixing Check date from the queue, validating restore files on pick, the T4 after declining location, shop-arrival actions.
