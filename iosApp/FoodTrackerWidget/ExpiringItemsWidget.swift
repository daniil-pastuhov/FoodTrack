import WidgetKit
import SwiftUI
import SwiftData

struct ExpiringItemsEntry: TimelineEntry {
    let date: Date
    let items: [PantryItem]
    var expiringSoonDays = WidgetSettings.expiringSoonDays
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

    /// What fits under the title with a full-size tick on each row.
    private let rowLimit = 2

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            WidgetTitle(L10n.widgetExpiringTitle)
            if entry.items.isEmpty {
                WidgetEmptyLine(L10n.widgetExpiringEmpty)
            } else {
                ForEach(entry.items.prefix(rowLimit)) { item in
                    HStack(spacing: LarderSpace.s2) {
                        WidgetExpiringText(item: item, expiringSoonDays: entry.expiringSoonDays)
                        Spacer(minLength: 0)
                        // Consumes a unit in place, without opening the app — seeing that
                        // something expires today and then having to go find it in the app is
                        // most of the friction the widget exists to remove.
                        WidgetTick(
                            intent: ConsumeItemIntent(itemId: item.id),
                            label: L10n.widgetActionUsed(item.name)
                        )
                    }
                }
                if entry.items.count > rowLimit {
                    WidgetMoreLine(hidden: entry.items.count - rowLimit)
                }
            }
            Spacer(minLength: 0)
        }
        .widgetContent()
        // Needs the `home` route in `AppRoute(externalName:)`. Until it's there this opens the
        // app where it was, as it always has.
        .widgetURL(URL(string: "foodtracker://home"))
    }
}

struct ExpiringItemsWidget: Widget {
    let kind = "ExpiringItemsWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ExpiringItemsProvider()) { entry in
            ExpiringItemsWidgetView(entry: entry)
                .containerBackground(LarderColor.surfaceContainerHigh, for: .widget)
        }
        .configurationDisplayName(L10n.widgetExpiringTitle)
        .description(L10n.widgetExpiringDescription)
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
