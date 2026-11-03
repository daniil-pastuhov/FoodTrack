import WidgetKit
import SwiftUI
import SwiftData

struct ShoppingListWidgetEntry: TimelineEntry {
    let date: Date
    let rows: [ShoppingRow]
    let isEmpty: Bool
}

struct ShoppingListWidgetProvider: TimelineProvider {
    func placeholder(in context: Context) -> ShoppingListWidgetEntry {
        ShoppingListWidgetEntry(date: Date(), rows: [], isEmpty: true)
    }

    func getSnapshot(in context: Context, completion: @escaping (ShoppingListWidgetEntry) -> Void) {
        completion(Self.fetchEntry())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ShoppingListWidgetEntry>) -> Void) {
        // Same app-triggered-reload model as `ExpiringItemsProvider` — this hourly policy is
        // only a backstop.
        completion(Timeline(entries: [Self.fetchEntry()], policy: .after(Date().addingTimeInterval(3600))))
    }

    /// The flat list, newest first - what `OverviewWidget`'s dense shopping preview shows.
    static func fetchEntries() -> [ShoppingListEntry] {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(sortBy: [SortDescriptor(\.addedDate, order: .reverse)])
        return ((try? context.fetch(descriptor)) ?? []).map { $0.asEntry }
    }

    /// The list as the app shows it: in the user's sections and their order, folded sections
    /// reduced to their header. Grouped by the same `ShoppingSections.rows` the app uses.
    static func fetchEntry() -> ShoppingListWidgetEntry {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let entries = ((try? context.fetch(FetchDescriptor<ShoppingListEntryRecord>(
            sortBy: [SortDescriptor(\.sortOrder), SortDescriptor(\.addedDate, order: .reverse)]
        ))) ?? []).map { $0.asEntry }
        let sections = ((try? context.fetch(FetchDescriptor<ShoppingSectionRecord>())) ?? []).map { $0.asSection }
        return ShoppingListWidgetEntry(
            date: Date(),
            rows: ShoppingSections.rows(entries: entries, sections: sections),
            isEmpty: entries.isEmpty
        )
    }
}

struct ShoppingListWidgetView: View {
    var entry: ShoppingListWidgetProvider.Entry
    @Environment(\.widgetFamily) private var family

    /// A widget can't scroll, so it shows as many rows as fit, headers included.
    private var rowBudget: Int { family == .systemLarge ? 13 : 5 }

    var body: some View {
        let visible = Array(entry.rows.prefix(rowBudget))
        let hiddenItems = entry.rows.dropFirst(rowBudget).filter {
            if case .item = $0 { return true } else { return false }
        }.count
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.widgetShoppingListTitle)
                .font(.headline)
            if entry.isEmpty {
                Text(L10n.widgetShoppingListEmpty)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(visible) { row in
                    switch row {
                    case .header(let section, let count):
                        // Folds or unfolds the section for the app too.
                        Button(intent: ToggleShoppingSectionIntent(sectionId: section.id, collapse: !section.collapsed)) {
                            HStack(spacing: 4) {
                                Image(systemName: section.collapsed ? "chevron.right" : "chevron.down")
                                    .font(.caption2.weight(.semibold))
                                Text(L10n.shoppingSectionName(section))
                                    .font(.caption.weight(.bold))
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                Text("\(count)")
                                    .font(.caption2)
                            }
                            .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    case .item(let item, _):
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
                if hiddenItems > 0 {
                    Text(L10n.widgetShoppingListMore(hiddenItems))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
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
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
