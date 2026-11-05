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

/// Which rows a widget that can't scroll has room for.
enum ShoppingWidgetLayout {
    static let headerHeight: CGFloat = 20

    /// The leading rows that fit in `height`, and how many entries that leaves out, those in
    /// folded sections included. An open header whose rows didn't make it goes too: it would look
    /// like an empty section.
    static func fit(_ rows: [ShoppingRow], height: CGFloat, itemHeight: CGFloat) -> (visible: [ShoppingRow], hidden: Int) {
        var visible: [ShoppingRow] = []
        var used: CGFloat = 0
        for row in rows {
            let rowHeight: CGFloat
            if case .header = row { rowHeight = headerHeight } else { rowHeight = itemHeight }
            guard used + rowHeight <= height else { break }
            used += rowHeight
            visible.append(row)
        }
        if case .header(let section, _) = visible.last, !section.collapsed {
            visible.removeLast()
        }
        let hidden = rows.dropFirst(visible.count).reduce(0) { total, row in
            switch row {
            case .item: return total + 1
            case .header(let section, let count): return total + (section.collapsed ? count : 0)
            }
        }
        return (visible, hidden)
    }
}

struct ShoppingListWidgetView: View {
    var entry: ShoppingListWidgetProvider.Entry
    @Environment(\.widgetFamily) private var family

    private typealias Tick = WidgetTick<TickOffShoppingEntryIntent>

    /// Room for rows under the title: a header and two ticks, or as many as a large widget takes.
    private var listHeight: CGFloat {
        family == .systemLarge ? 290 : ShoppingWidgetLayout.headerHeight + 2 * Tick.size
    }

    var body: some View {
        let list = ShoppingWidgetLayout.fit(entry.rows, height: listHeight, itemHeight: Tick.size)
        VStack(alignment: .leading, spacing: 0) {
            WidgetTitle(L10n.widgetShoppingListTitle)
            if entry.isEmpty {
                WidgetEmptyLine(L10n.widgetShoppingListEmpty)
            } else {
                ForEach(list.visible) { row in
                    switch row {
                    case .header(let section, let count):
                        // Folds or unfolds the section for the app too.
                        Button(intent: ToggleShoppingSectionIntent(sectionId: section.id, collapse: !section.collapsed)) {
                            HStack(spacing: LarderSpace.s1) {
                                LarderIcon(section.collapsed ? .chevronRight : .chevronDown, size: .small)
                                Text(L10n.shoppingSectionName(section))
                                    .larderFont(.caption)
                                    .fontWeight(.bold)
                                    .lineLimit(1)
                                Spacer(minLength: LarderSpace.s1)
                                Text("\(count)")
                                    .larderFont(.caption)
                                    .fontWeight(.bold)
                            }
                            .foregroundStyle(LarderColor.onSurfaceVariant)
                            .frame(height: ShoppingWidgetLayout.headerHeight)
                        }
                        .buttonStyle(.plain)
                    case .item(let item, _):
                        HStack(spacing: LarderSpace.s2) {
                            WidgetItemName(item.productName)
                            Spacer(minLength: 0)
                            if item.quantity > 1 {
                                WidgetQuantity(quantity: item.quantity)
                            }
                            // Ticks the entry off in the aisle, which is exactly where the widget
                            // gets looked at and where opening the app is most annoying.
                            WidgetTick(
                                intent: TickOffShoppingEntryIntent(entryId: item.id),
                                label: L10n.widgetActionBought(item.productName)
                            )
                        }
                    }
                }
                if list.hidden > 0 {
                    WidgetMoreLine(hidden: list.hidden)
                }
            }
            Spacer(minLength: 0)
        }
        .widgetContent()
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
                .containerBackground(LarderColor.surfaceContainerHigh, for: .widget)
        }
        .configurationDisplayName(L10n.widgetShoppingListTitle)
        .description(L10n.widgetShoppingListDescription)
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
