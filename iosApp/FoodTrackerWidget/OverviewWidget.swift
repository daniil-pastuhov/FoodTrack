import WidgetKit
import SwiftUI
import SwiftData

struct OverviewEntry: TimelineEntry {
    let date: Date
    let items: [PantryItem]
    let entries: [ShoppingListEntry]
}

/// Combines `ExpiringItemsProvider` and `ShoppingListWidgetProvider`'s own queries into one
/// entry rather than re-deriving them, so the two sections stay in lockstep with the
/// single-purpose widgets if their fetch logic ever changes.
struct OverviewProvider: TimelineProvider {
    func placeholder(in context: Context) -> OverviewEntry {
        OverviewEntry(date: Date(), items: [], entries: [])
    }

    func getSnapshot(in context: Context, completion: @escaping (OverviewEntry) -> Void) {
        completion(Self.currentEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<OverviewEntry>) -> Void) {
        // Same app-triggered-reload model as the other two widgets — this policy is only a
        // backstop for whatever "days remaining" text would otherwise go stale overnight.
        let midnight = Calendar.current.nextDate(
            after: Date(), matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime
        ) ?? Date().addingTimeInterval(3600)
        completion(Timeline(entries: [Self.currentEntry()], policy: .after(midnight)))
    }

    private static func currentEntry() -> OverviewEntry {
        OverviewEntry(
            date: Date(),
            items: ExpiringItemsProvider.fetchExpiringItems(),
            entries: ShoppingListWidgetProvider.fetchEntries()
        )
    }
}

struct OverviewWidgetView: View {
    @Environment(\.widgetFamily) private var family
    var entry: OverviewProvider.Entry

    private var expiryLimit: Int { family == .systemLarge ? 5 : 2 }
    private var shoppingLimit: Int { family == .systemLarge ? 6 : 3 }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            OverviewSectionView(
                title: L10n.widgetExpiringTitle,
                isEmpty: entry.items.isEmpty,
                emptyText: L10n.widgetExpiringEmpty
            ) {
                ForEach(entry.items.prefix(expiryLimit)) { item in
                    VStack(alignment: .leading, spacing: 0) {
                        Text(item.name)
                            .font(.subheadline)
                            .lineLimit(1)
                        Text(item.daysRemainingFormatted)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Divider()
            OverviewSectionView(
                title: L10n.widgetShoppingListTitle,
                isEmpty: entry.entries.isEmpty,
                emptyText: L10n.widgetShoppingListEmpty
            ) {
                ForEach(entry.entries.prefix(shoppingLimit)) { item in
                    HStack {
                        Text(item.productName)
                            .font(.subheadline)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        if item.quantity > 1 {
                            Text(L10n.quantityFormat(item.quantity))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        // Routed by `AppRouter` (main app target only) to open the Shopping List sheet
        // directly, matching `ShoppingListWidget`'s own tap target.
        .widgetURL(URL(string: "foodtracker://shopping-list"))
    }
}

private struct OverviewSectionView<Rows: View>: View {
    let title: String
    let isEmpty: Bool
    let emptyText: String
    @ViewBuilder let rows: Rows

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .fontWeight(.bold)
                .foregroundStyle(.secondary)
            if isEmpty {
                Text(emptyText)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } else {
                rows
            }
        }
    }
}

struct OverviewWidget: Widget {
    let kind = "OverviewWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: OverviewProvider()) { entry in
            OverviewWidgetView(entry: entry)
                .containerBackground(.background, for: .widget)
        }
        .configurationDisplayName(L10n.widgetOverviewTitle)
        .description(L10n.widgetOverviewDescription)
        // Home Screen widgets can't scroll — unlike Android's Glance LazyColumn, WidgetKit
        // renders a static snapshot — so this only supports the two larger families, which
        // have enough room to show both sections without needing to scroll.
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}
