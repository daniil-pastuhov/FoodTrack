import Foundation

/// Shared between the main app target and the `FoodTrackerWidgetExtension` target so both
/// can agree on the same App Group container — for the SwiftData store
/// (`NativePantryViewModel.sharedModelContainer`) and for settings (`SettingsStore`). The
/// widget extension runs in its own process/sandbox and cannot otherwise read either.
///
/// Registering `group.com.foodbest.ios` under the `S8Q8KHX6G5` team (in Xcode's Signing &
/// Capabilities and/or the Apple Developer portal) is a manual step this file can't perform —
/// see WIDGETS_TASK.md.
enum AppGroup {
    static let identifier = "group.com.foodbest.ios"
}
