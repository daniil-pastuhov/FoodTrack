import FirebaseCore
import SwiftUI

@main
struct FoodTrackerApp: App {
    /// Set by scripts/ios_screenshots.sh through the debug-only `-demoBackup` launch argument
    /// (see RootTabView.applyScreenshotLaunchArguments).
    #if DEBUG
    static var isTakingScreenshots: Bool { UserDefaults.standard.string(forKey: "demoBackup") != nil }
    #else
    static let isTakingScreenshots = false
    #endif

    // Exists only to catch home-screen quick actions, which SwiftUI's App has no hook for.
    @UIApplicationDelegateAdaptor(QuickActionDelegate.self) private var quickActionDelegate
    @ObservedObject private var settingsStore = SettingsStore.shared

    init() {
        // Starts Crashlytics, configured by GoogleService-Info.plist. Left out of debug builds:
        // crashes hit while developing would only bury the ones real users run into.
        #if !DEBUG
        FirebaseApp.configure()
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .tint(Color(red: 13/255, green: 148/255, blue: 136/255)) // Teal Brand color
                .preferredColorScheme(settingsStore.settings.theme.colorScheme)
                .onAppear {
                    // The permission alert would cover the store screenshots.
                    if !Self.isTakingScreenshots {
                        IOSNotificationManager.shared.requestAuthorization()
                    }
                    // Re-asserted on every start rather than only when Settings changes: region
                    // monitoring is an OS-level registration that outlives the process, and
                    // permission can be revoked from Settings between runs.
                    StoreGeofenceManager.shared.sync(settings: settingsStore.settings)
                }
                .onOpenURL { url in
                    AppRouter.shared.handle(url)
                }
        }
    }
}
