import WidgetKit
import SwiftUI
import SwiftData

struct ExpiringItemsEntry: TimelineEntry {
    let date: Date
    let items: [PantryItem]
}

struct ExpiringItemsProvider: TimelineProvider {
    func placeholder(in context: Context) -> ExpiringItemsEntry {
        ExpiringItemsEntry(date: Date(), items: [])
    }

    func getSnapshot(in context: Context, completion: @escaping (ExpiringItemsEntry) -> Void) {
        completion(ExpiringItemsEntry(date: Date(), items: Self.fetchExpiringItems()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ExpiringItemsEntry>) -> Void) {
        let entry = ExpiringItemsEntry(date: Date(), items: Self.fetchExpiringItems())
        // The app calls `WidgetCenter.shared.reloadAllTimelines()` on every pantry change
        // (see `NativePantryViewModel`), so this policy is only a backstop for whatever "days
        // remaining" text would otherwise go stale overnight, or if a reload call is missed.
        let midnight = Calendar.current.nextDate(
            after: Date(), matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime
        ) ?? Date().addingTimeInterval(3600)
        completion(Timeline(entries: [entry], policy: .after(midnight)))
    }

    /// Mirrors `PantryStateHolder`/`NativePantryViewModel`'s expired/expiring-soon threshold
    /// (`daysRemaining <= expiringSoonDays`). The widget has no view model instance to read
    /// from, so it fetches straight from the shared SwiftData store, the same way
    /// `IOSNotificationManager.deleteItemDirectly` does from outside the view hierarchy.
    static func fetchExpiringItems() -> [PantryItem] {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<PantryItemRecord>(sortBy: [SortDescriptor(\.expiryDate)])
        let items = ((try? context.fetch(descriptor)) ?? []).map { $0.asPantryItem }
        let expiringSoonDays = WidgetSettings.expiringSoonDays
        return items
            .filter { $0.daysRemaining <= expiringSoonDays }
            .sorted { $0.daysRemaining < $1.daysRemaining }
    }
}

struct ExpiringItemsWidgetView: View {
    var entry: ExpiringItemsProvider.Entry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.widgetExpiringTitle)
                .font(.headline)
            if entry.items.isEmpty {
                Text(L10n.widgetExpiringEmpty)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(entry.items.prefix(4)) { item in
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
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

struct ExpiringItemsWidget: Widget {
    let kind = "ExpiringItemsWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ExpiringItemsProvider()) { entry in
            ExpiringItemsWidgetView(entry: entry)
                .containerBackground(.background, for: .widget)
        }
        .configurationDisplayName(L10n.widgetExpiringTitle)
        .description(L10n.widgetExpiringDescription)
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
