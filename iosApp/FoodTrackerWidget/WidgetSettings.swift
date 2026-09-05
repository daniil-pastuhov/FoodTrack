import Foundation

/// Reads just the `expiringSoonDays` threshold `SettingsStore` persists, without going through
/// `SettingsStore` itself — that class is `@MainActor`, and `TimelineProvider`'s methods are
/// not, so a widget extension can't call into it synchronously. Decodes the same App Group
/// UserDefaults key by hand instead.
enum WidgetSettings {
    static var expiringSoonDays: Int {
        guard let defaults = UserDefaults(suiteName: AppGroup.identifier),
              let data = defaults.data(forKey: SettingsStore.storageKey),
              let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) else {
            return AppSettings().expiringSoonDays
        }
        return decoded.expiringSoonDays
    }
}
