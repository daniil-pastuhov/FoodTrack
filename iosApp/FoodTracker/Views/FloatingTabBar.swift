import SwiftUI

/// The four primary destinations reachable from the floating bottom pill nav bar. Scanning and
/// adding a new item are deliberately not tabs here — they're the two floating round buttons
/// (`ScanAndAddButtons`) that float above the pill instead, so they stay reachable from every tab.
enum RootTab: CaseIterable {
    case home, calendar, shoppingList, settings

    var title: String {
        switch self {
        case .home: return L10n.navHome
        case .calendar: return L10n.calendarButton
        case .shoppingList: return L10n.shoppingListTitle
        case .settings: return L10n.settingsTitle
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house.fill"
        case .calendar: return "calendar"
        case .shoppingList: return "cart.fill"
        case .settings: return "gearshape.fill"
        }
    }
}

/// Floating, fully-rounded pill nav bar, sized to its content rather than the full screen
/// width, so the background shows through the margins around it (see its usage in `RootTabView`).
struct FloatingTabBar: View {
    @Binding var selectedTab: RootTab
    /// Count of distinct products on the shopping list, shown as a badge on that tab. Each
    /// `ShoppingListEntry` already represents one product (it carries its own `quantity`
    /// field), so this is simply `shoppingList.count`.
    var shoppingListCount: Int = 0

    var body: some View {
        HStack(spacing: 2) {
            ForEach(RootTab.allCases, id: \.self) { tab in
                Button {
                    selectedTab = tab
                } label: {
                    VStack(spacing: 3) {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: tab.systemImage)
                                .font(.system(size: 20))
                            if tab == .shoppingList && shoppingListCount > 0 {
                                TabBadge(count: shoppingListCount)
                                    .offset(x: 12, y: -6)
                            }
                        }
                        Text(tab.title)
                            .font(.system(size: 11, weight: selectedTab == tab ? .bold : .medium))
                    }
                    .foregroundColor(selectedTab == tab ? .accentColor : .secondary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 20)
                            .fill(selectedTab == tab ? Color.accentColor.opacity(0.12) : Color.clear)
                    )
                }
                .buttonStyle(.plain)
                .accessibilityLabel(
                    tab == .shoppingList && shoppingListCount > 0
                        ? "\(tab.title), \(shoppingListCount)"
                        : tab.title
                )
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 8)
        .background(
            Capsule()
                .fill(.regularMaterial)
                .shadow(color: .black.opacity(0.15), radius: 8, y: 3)
        )
        .animation(.easeInOut(duration: 0.15), value: selectedTab)
    }
}

/// Small numbered pill anchored to the top-trailing corner of a tab icon.
private struct TabBadge: View {
    let count: Int

    var body: some View {
        Text(count > 99 ? "99+" : "\(count)")
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(.white)
            .padding(.horizontal, 4)
            .frame(minWidth: 15, minHeight: 15)
            .background(Capsule().fill(Color.accentColor))
    }
}

/// The two floating round buttons that hover above `FloatingTabBar`, reachable from every tab:
/// the primary "scan a barcode" button and the secondary "add an item" button. Scan is the
/// more important, more frequently used action, so it gets the larger primary button closest
/// to the tab bar (easiest thumb reach); Add is the secondary button above it.
struct ScanAndAddButtons: View {
    let onScan: () -> Void
    let onAdd: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Button(action: onAdd) {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.primary)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(.regularMaterial))
                    .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
            }
            .accessibilityLabel(L10n.addItemTitle)

            Button(action: onScan) {
                Image(systemName: "barcode.viewfinder")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 56, height: 56)
                    .background(Circle().fill(Color.accentColor))
                    .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
            }
            .accessibilityLabel(L10n.scanButton)
        }
    }
}
