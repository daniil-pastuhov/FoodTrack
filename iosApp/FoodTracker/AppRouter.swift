import Foundation

/// Routes a widget tap to a specific in-app screen. `FoodTrackerApp`'s `.onOpenURL` sets the
/// flag for the `foodtracker://shopping-list` URL the Shopping List widget's `.widgetURL(...)`
/// opens; `PantryDashboardView` observes it and presents the Shopping List sheet, then resets
/// it. Main-app-only — the widget extension only needs to *emit* the URL, not this router.
@MainActor
public final class AppRouter: ObservableObject {
    public static let shared = AppRouter()

    @Published public var openShoppingList: Bool = false

    private init() {}

    public func handle(_ url: URL) {
        if url.host == "shopping-list" {
            openShoppingList = true
        }
    }
}
