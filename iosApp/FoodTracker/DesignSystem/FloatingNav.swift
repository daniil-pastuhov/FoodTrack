import SwiftUI

/// The four main destinations. Scanning and adding an item aren't tabs: Scan is the `ScanFab`
/// above the pill, and adding by hand is reached from the scanner.
public enum RootTab: String, CaseIterable {
    case home, recipe, shopping, settings

    var title: String {
        switch self {
        case .home: return L10n.navHome
        case .recipe: return L10n.recipeTab
        case .shopping: return L10n.navShopping
        case .settings: return L10n.settingsTitle
        }
    }

    var icon: LarderIconName {
        switch self {
        case .home: return .house
        case .recipe: return .chefHat
        case .shopping: return .shoppingBasket
        case .settings: return .settings
        }
    }

    /// Scan is on Home only. On Recipe its place above the pill holds the tab's own primary action.
    var showsScan: Bool { self == .home }
}

/// The floating pill, on every device: sized to its tabs, so the page shows at its sides, with
/// content scrolling beneath it. There is no rail or sidebar anywhere, iPad included.
///
///     FloatingNav(selection: $tab, shoppingCount: viewModel.shoppingList.count)
struct FloatingNav: View {
    @Binding var selection: RootTab
    /// Products on the shopping list, as a badge on that tab. Hidden at zero.
    var shoppingCount: Int = 0

    var body: some View {
        HStack(spacing: 2) {
            ForEach(RootTab.allCases, id: \.self) { tab in
                FloatingNavItem(
                    tab: tab,
                    isSelected: selection == tab,
                    badge: tab == .shopping ? shoppingCount : 0
                ) {
                    selection = tab
                }
            }
        }
        .padding(.vertical, LarderSpace.s2)
        .padding(.horizontal, 6)
        .background(LarderColor.surface, in: RoundedRectangle(cornerRadius: LarderRadius.xl))
        .overlay {
            RoundedRectangle(cornerRadius: LarderRadius.xl)
                .strokeBorder(LarderColor.outlineVariant, lineWidth: 1)
        }
        .larderElevation(.level2)
        // The pill has to fit a phone's width. Past this size, a long press on a tab shows the
        // system's large content viewer instead.
        .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        .animation(.easeInOut(duration: 0.15), value: selection)
    }
}

private struct FloatingNavItem: View {
    let tab: RootTab
    let isSelected: Bool
    let badge: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                LarderIcon(tab.icon)
                    .overlay(alignment: .topTrailing) {
                        if badge > 0 {
                            LarderBadge(count: badge, isCompact: true)
                                .fixedSize()
                                .alignmentGuide(.top) { $0[.top] + 6 }
                                .alignmentGuide(.trailing) { $0[.trailing] - 10 }
                        }
                    }
                Text(tab.title)
                    .font(.system(size: LarderFont.caption.size, weight: isSelected ? .bold : .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .foregroundStyle(isSelected ? LarderColor.onPrimaryContainer : LarderColor.onSurfaceVariant)
            .padding(.horizontal, LarderSpace.s3)
            .padding(.vertical, LarderSpace.s2)
            .frame(minHeight: 56)
            .background(
                isSelected ? LarderColor.primaryContainer : Color.clear,
                in: RoundedRectangle(cornerRadius: LarderRadius.lg)
            )
            .contentShape(RoundedRectangle(cornerRadius: LarderRadius.lg))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(badge > 0 ? "\(tab.title), \(badge)" : tab.title)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityShowsLargeContentViewer {
            LarderIcon(tab.icon)
            Text(tab.title)
        }
    }
}

private struct NavPreview: View {
    @State private var tab = RootTab.home
    var count = 5

    var body: some View {
        ZStack(alignment: .bottom) {
            LarderColor.background.ignoresSafeArea()
            FloatingNav(selection: $tab, shoppingCount: count)
                .padding(.bottom, LarderSpace.s3)
        }
    }
}

#Preview("Pill") { NavPreview() }
#Preview("Pill, dark") { NavPreview().preferredColorScheme(.dark) }
#Preview("Pill, no badge") { NavPreview(count: 0) }
#Preview("Pill, 99+") { NavPreview(count: 120) }
#Preview("Pill, large text") { NavPreview().dynamicTypeSize(.accessibility3) }
