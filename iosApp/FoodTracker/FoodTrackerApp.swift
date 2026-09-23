import SwiftUI

@main
struct FoodTrackerApp: App {
    // Exists only to catch home-screen quick actions, which SwiftUI's App has no hook for.
    @UIApplicationDelegateAdaptor(QuickActionDelegate.self) private var quickActionDelegate
    @ObservedObject private var settingsStore = SettingsStore.shared

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .tint(Color(red: 13/255, green: 148/255, blue: 136/255)) // Teal Brand color
                .preferredColorScheme(settingsStore.settings.theme.colorScheme)
                .onAppear {
                    IOSNotificationManager.shared.requestAuthorization()
                }
                .onOpenURL { url in
                    AppRouter.shared.handle(url)
                }
        }
    }
}
