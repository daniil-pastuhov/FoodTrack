import Foundation
import UIKit

/// Everything the app can be sent to. The names match the Larder contract, so they are the same
/// on Android.
public enum AppRoute: Equatable {
    case tab(RootTab)
    /// A sheet on phones. On expanded Home it becomes the right pane.
    case productDetail(itemId: String)
    case addItem(AddItemRoute)
    case scanner(IOSCameraMode)
    case categories
    case onboarding
    /// The Recipe tab with these items ticked: Home's Get recipe ideas, and the summary
    /// notification's Recipe ideas action.
    case recipeIdeas(itemIds: [String])

    /// The name a widget URL or a home-screen quick action gives for its target.
    init?(externalName: String) {
        switch externalName {
        case "home": self = .tab(.home)
        case "shopping-list": self = .tab(.shopping)
        case "scan": self = .scanner(.barcode)
        case "add-item": self = .addItem(.new)
        default: return nil
        }
    }
}

public enum AddItemRoute: Equatable {
    case new
    case prefilled(AddItemPrefill)
    case edit(itemId: String)
}

/// What a scan knows about a product before the form opens.
public struct AddItemPrefill: Equatable {
    public var barcode: String? = nil
    public var name: String? = nil
    public var brand: String? = nil
    public var imageUrl: String? = nil
    /// Expiry printed on the packaging, when the scan read one.
    public var date: Date? = nil
    public var category: String? = nil
    /// Expiry to start a new item from when the scan produced no date of its own: the shelf life
    /// this barcode was last given, or its category's default.
    public var suggestion: ShelfLifeSuggestion? = nil

    public init(
        barcode: String? = nil,
        name: String? = nil,
        brand: String? = nil,
        imageUrl: String? = nil,
        date: Date? = nil,
        category: String? = nil,
        suggestion: ShelfLifeSuggestion? = nil
    ) {
        self.barcode = barcode
        self.name = name
        self.brand = brand
        self.imageUrl = imageUrl
        self.date = date
        self.category = category
        self.suggestion = suggestion
    }
}

/// Takes the app to an `AppRoute`, from anywhere.
///
/// Three sources feed it:
///  - `foodtracker://<host>` URLs, which the widgets emit via `.widgetURL(...)` and
///    `FoodTrackerApp`'s `.onOpenURL` forwards here.
///  - The long-press quick actions declared as `UIApplicationShortcutItems` in Info.plist,
///    delivered through `QuickActionDelegate` (SwiftUI has no scene hook for them).
///  - Code in the app, through `navigate(to:)`.
///
/// `RootTabView` observes `route` and clears it once handled. Main-app-only: the widget
/// extension only needs to *emit* a URL, not resolve one.
@MainActor
public final class AppRouter: ObservableObject {
    public static let shared = AppRouter()

    /// The screen to show next, or nil when there is nothing pending. Consumers set it back to
    /// nil after acting, so the same tap can't be replayed on the next view update.
    @Published public var route: AppRoute?

    /// Retained across the app's own launch: a quick action that starts the app cold is
    /// delivered before any SwiftUI view exists to observe `route`, so it is stashed here and
    /// replayed once `RootTabView` appears.
    public var pendingLaunchRoute: AppRoute?

    private init() {}

    public func navigate(to route: AppRoute) {
        self.route = route
    }

    public func handle(_ url: URL) {
        guard let host = url.host, let route = AppRoute(externalName: host) else { return }
        self.route = route
    }

    public func handle(shortcutType: String) {
        guard let route = AppRoute(externalName: shortcutType) else { return }
        self.route = route
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
           let route = AppRoute(externalName: shortcut.type) {
            Task { @MainActor in
                AppRouter.shared.pendingLaunchRoute = route
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
