import Foundation
import UIKit

/// Where an external entry point wants the app to land.
public enum AppDestination: String {
    case shoppingList = "shopping-list"
    case scanner = "scan"
    case addItem = "add-item"
}

/// Routes a widget tap or a home-screen quick action to a specific in-app screen.
///
/// Two sources feed it, both resolving to the same `AppDestination`:
///  - `foodtracker://<host>` URLs, which the widgets emit via `.widgetURL(...)` and
///    `FoodTrackerApp`'s `.onOpenURL` forwards here.
///  - The long-press quick actions declared as `UIApplicationShortcutItems` in Info.plist,
///    delivered through `QuickActionDelegate` (SwiftUI has no scene hook for them).
///
/// `RootTabView` observes `destination` and clears it once handled. Main-app-only — the widget
/// extension only needs to *emit* a URL, not resolve one.
@MainActor
public final class AppRouter: ObservableObject {
    public static let shared = AppRouter()

    /// The screen to show next, or nil when there is nothing pending. Consumers set it back to
    /// nil after acting, so the same tap can't be replayed on the next view update.
    @Published public var destination: AppDestination?

    /// Retained across the app's own launch: a quick action that starts the app cold is
    /// delivered before any SwiftUI view exists to observe `destination`, so it is stashed here
    /// and replayed once `RootTabView` appears.
    public var pendingLaunchDestination: AppDestination?

    private init() {}

    public func handle(_ url: URL) {
        guard let host = url.host, let destination = AppDestination(rawValue: host) else { return }
        self.destination = destination
    }

    public func handle(shortcutType: String) {
        guard let destination = AppDestination(rawValue: shortcutType) else { return }
        self.destination = destination
    }
}

/// Delivers home-screen quick actions into `AppRouter`.
///
/// SwiftUI's `App` has no hook for `UIApplicationShortcutItem`, so this minimal delegate exists
/// purely to catch them — both the warm path (`performActionFor`) and the cold-launch path,
/// where the action arrives in the connection options before any view is on screen.
public final class QuickActionDelegate: NSObject, UIApplicationDelegate {
    public func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        if let shortcut = options.shortcutItem,
           let destination = AppDestination(rawValue: shortcut.type) {
            Task { @MainActor in
                AppRouter.shared.pendingLaunchDestination = destination
            }
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = QuickActionSceneDelegate.self
        return configuration
    }
}

/// Catches quick actions taken while the app is already running.
public final class QuickActionSceneDelegate: NSObject, UIWindowSceneDelegate {
    public func windowScene(
        _ windowScene: UIWindowScene,
        performActionFor shortcutItem: UIApplicationShortcutItem,
        completionHandler: @escaping (Bool) -> Void
    ) {
        let type = shortcutItem.type
        Task { @MainActor in
            AppRouter.shared.handle(shortcutType: type)
            completionHandler(true)
        }
    }
}
