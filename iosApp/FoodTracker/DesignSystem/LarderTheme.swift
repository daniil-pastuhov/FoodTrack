import SwiftUI
import UIKit

/// App-wide appearance that SwiftUI's `.tint` doesn't reach. Called once, before the first view.
enum LarderTheme {
    @MainActor
    static func applyGlobalAppearance() {
        let primary = uiColor("primary")

        let bar = UINavigationBarAppearance()
        bar.configureWithOpaqueBackground()
        bar.backgroundColor = uiColor("background")
        bar.shadowColor = .clear
        let text = uiColor("on-background")
        bar.titleTextAttributes = [.foregroundColor: text, .font: LarderFont.title.uiFont]
        bar.largeTitleTextAttributes = [.foregroundColor: text, .font: LarderFont.display.uiFont]
        UINavigationBar.appearance().standardAppearance = bar
        UINavigationBar.appearance().compactAppearance = bar
        // Transparent at the top of a screen: an opaque background here hides large titles.
        let edgeBar = UINavigationBarAppearance()
        edgeBar.configureWithTransparentBackground()
        edgeBar.titleTextAttributes = bar.titleTextAttributes
        edgeBar.largeTitleTextAttributes = bar.largeTitleTextAttributes
        UINavigationBar.appearance().scrollEdgeAppearance = edgeBar
        UINavigationBar.appearance().tintColor = primary

        UISwitch.appearance().onTintColor = primary
        UIDatePicker.appearance().tintColor = primary
        UISegmentedControl.appearance().selectedSegmentTintColor = uiColor("primary-container")
        UISegmentedControl.appearance().setTitleTextAttributes(
            [.foregroundColor: uiColor("on-primary-container")], for: .selected
        )
        // Alerts take their button colour from the window, not from SwiftUI's tint.
        UIView.appearance(whenContainedInInstancesOf: [UIAlertController.self]).tintColor = primary
    }

    /// Colour sets carry both themes, so these stay dynamic.
    private static func uiColor(_ role: String) -> UIColor {
        UIColor(named: "Larder/\(role)") ?? .label
    }
}

extension View {
    /// For old screens that are still a `List` or `Form`: drops the system's grey backdrop for
    /// the Larder page colour. Rows take theirs from `larderListRow()`.
    func larderListBackground() -> some View {
        scrollContentBackground(.hidden)
            .background(LarderColor.background)
    }

    /// Row colour for a `Section` or row of a grouped `List` or `Form`.
    func larderListRow() -> some View {
        listRowBackground(LarderColor.surface)
    }
}
