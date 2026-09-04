import Foundation

@MainActor
public class SettingsStore: ObservableObject {
    public static let shared = SettingsStore()

    @Published public var settings: AppSettings

    private static let storageKey = "com.foodtracker.app_settings"

    private init() {
        settings = Self.load()
    }

    public func save(_ newSettings: AppSettings) {
        settings = newSettings
        if let data = try? JSONEncoder().encode(newSettings) {
            UserDefaults.standard.set(data, forKey: Self.storageKey)
        }
    }

    private static func load() -> AppSettings {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) else {
            return AppSettings()
        }
        return decoded
    }
}
