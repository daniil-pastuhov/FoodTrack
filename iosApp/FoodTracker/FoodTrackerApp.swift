import SwiftUI

@main
struct FoodTrackerApp: App {
    @ObservedObject private var settingsStore = SettingsStore.shared

    var body: some Scene {
        WindowGroup {
            PantryDashboardView()
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
