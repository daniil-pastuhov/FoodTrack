import WidgetKit
import SwiftUI
import SwiftData

struct ShoppingListWidgetEntry: TimelineEntry {
    let date: Date
    let entries: [ShoppingListEntry]
}

struct ShoppingListWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> ShoppingListWidgetEntry {
        ShoppingListWidgetEntry(date: Date(), entries: [])
    }

    func getSnapshot(in context: Context, completion: @escaping (ShoppingListWidgetEntry) -> Void) {
        completion(ShoppingListWidgetEntry(date: Date(), entries: Self.fetchEntries()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ShoppingListWidgetEntry>) -> Void) {
        let entry = ShoppingListWidgetEntry(date: Date(), entries: Self.fetchEntries())
        // Same app-triggered-reload model as `ExpiringItemsProvider` — this hourly policy is
        // only a backstop.
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(3600))))
    }

    /// Same flat list (name + quantity) `ShoppingListDao.getAllFlow()` returns on Android,
    /// without re-implementing the in-app screen's category grouping.
    static func fetchEntries() -> [ShoppingListEntry] {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(sortBy: [SortDescriptor(\.addedDate, order: .reverse)])
        return ((try? context.fetch(descriptor)) ?? []).map { $0.asEntry }
    }
}

struct ShoppingListWidgetView: View {
    var entry: ShoppingListWidgetProvider.Entry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.widgetShoppingListTitle)
                .font(.headline)
            if entry.entries.isEmpty {
                Text(L10n.widgetShoppingListEmpty)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(entry.entries.prefix(6)) { item in
                    HStack(spacing: 6) {
                        Text(item.productName)
                            .font(.subheadline)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        if item.quantity > 1 {
                            Text(L10n.quantityFormat(item.quantity))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        // Ticks the entry off in the aisle, which is exactly where the widget
                        // gets looked at and where opening the app is most annoying.
                        Button(intent: TickOffShoppingEntryIntent(entryId: item.id)) {
                            Image(systemName: "checkmark")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(L10n.widgetActionBought)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        // Routed by `AppRouter` (main app target only) to open the Shopping List sheet
        // directly, rather than just landing on the dashboard like a plain app-icon tap.
        .widgetURL(URL(string: "foodtracker://shopping-list"))
    }
}

struct ShoppingListWidget: Widget {
    let kind = "ShoppingListWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ShoppingListWidgetProvider()) { entry in
            ShoppingListWidgetView(entry: entry)
                .containerBackground(.background, for: .widget)
        }
        .configurationDisplayName(L10n.widgetShoppingListTitle)
        .description(L10n.widgetShoppingListDescription)
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
