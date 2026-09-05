import Foundation

@MainActor
public class SettingsStore: ObservableObject {
    public static let shared = SettingsStore()

    @Published public var settings: AppSettings

    /// Key shared with `FoodTrackerWidgetExtension`'s own (non-`@MainActor`) settings read —
    /// see that target's `WidgetSettings.expiringSoonDays`. `nonisolated` so that widget code
    /// can reference it from a synchronous, non-actor `TimelineProvider` method.
    public nonisolated static let storageKey = "com.foodtracker.app_settings"

    /// Stored in the App Group suite (rather than `.standard`) so the widget extension, which
    /// cannot use this `@MainActor` class directly from its `TimelineProvider`, can still read
    /// `expiringSoonDays` by decoding the same key itself.
    private static let defaults = UserDefaults(suiteName: AppGroup.identifier) ?? .standard

    private init() {
        settings = Self.load()
    }

    public func save(_ newSettings: AppSettings) {
        settings = newSettings
        if let data = try? JSONEncoder().encode(newSettings) {
            Self.defaults.set(data, forKey: Self.storageKey)
        }
    }

    private static func load() -> AppSettings {
        if let data = defaults.data(forKey: storageKey),
           let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
            return decoded
        }

        // One-time migration for installs that predate the widget's App Group requirement:
        // settings used to live in `.standard`. Carry them into the App Group suite so an
        // existing user's preferences (and the widget's `expiringSoonDays` read) survive.
        if let legacyData = UserDefaults.standard.data(forKey: storageKey),
           let legacyDecoded = try? JSONDecoder().decode(AppSettings.self, from: legacyData) {
            defaults.set(legacyData, forKey: storageKey)
            return legacyDecoded
        }

        return AppSettings()
    }
}
