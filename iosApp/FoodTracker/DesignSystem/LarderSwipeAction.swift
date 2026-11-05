import SwiftUI

/// A native swipe action in Larder colours, for a `List` row. Nothing may depend on a gesture
/// alone, so each one needs a visible equivalent elsewhere, such as the detail sheet.
///
///     row.larderSwipeActions(
///         leading: [LarderSwipeAction("To list", icon: .shoppingBasket) { move(item) }],
///         trailing: [LarderSwipeAction("Delete", icon: .trash2, tint: .destructive) { delete(item) }]
///     )
struct LarderSwipeAction: Identifiable {
    enum Tint {
        case primary
        /// Green with a tick: ticking something off.
        case success
        /// Removes the row at once, with the system's delete animation.
        case destructive
    }

    let id = UUID()
    let title: String
    let icon: LarderIconName
    let tint: Tint
    let action: () -> Void

    init(_ title: String, icon: LarderIconName, tint: Tint = .primary, action: @escaping () -> Void) {
        self.title = title
        self.icon = icon
        self.tint = tint
        self.action = action
    }
}

private struct LarderSwipeActions: ViewModifier {
    let leading: [LarderSwipeAction]
    let trailing: [LarderSwipeAction]

    @Environment(\.colorScheme) private var colorScheme

    /// The system always draws swipe labels in white, and the dark theme's `primary` and `error`
    /// are pale, so in dark they use their container colours instead.
    private func color(for tint: LarderSwipeAction.Tint) -> Color {
        let isDark = colorScheme == .dark
        switch tint {
        case .primary, .success: return isDark ? LarderColor.primaryContainer : LarderColor.primary
        case .destructive: return isDark ? LarderColor.errorContainer : LarderColor.error
        }
    }

    private func button(_ action: LarderSwipeAction) -> some View {
        Button(role: action.tint == .destructive ? .destructive : nil, action: action.action) {
            Label(action.title, image: action.icon.assetName)
        }
        .tint(color(for: action.tint))
    }

    func body(content: Content) -> some View {
        content
            .swipeActions(edge: .leading) { ForEach(leading) { button($0) } }
            .swipeActions(edge: .trailing) { ForEach(trailing) { button($0) } }
    }
}

/// The same as `.larderSwipeActions`, under the name Android uses. The row must be in a `List`.
struct LarderSwipeRow<Content: View>: View {
    var leading: [LarderSwipeAction] = []
    var trailing: [LarderSwipeAction] = []
    @ViewBuilder var content: Content

    var body: some View {
        content.larderSwipeActions(leading: leading, trailing: trailing)
    }
}

extension View {
    /// Pass empty lists to switch a row's actions off, as Home does while selecting.
    func larderSwipeActions(
        leading: [LarderSwipeAction] = [],
        trailing: [LarderSwipeAction] = []
    ) -> some View {
        modifier(LarderSwipeActions(leading: leading, trailing: trailing))
    }
}
