import AppIntents
import Foundation
import SwiftData
import WidgetKit

/// Pantry and shopping-list mutations applied straight to SwiftData, without going through
/// `NativePantryViewModel`.
///
/// Two callers need exactly this, and neither can rely on a view model existing:
///  - notification actions, which can fire right after a cold launch, before any view (and so
///    any view model) has been created — see `IOSNotificationManager`;
///  - the widgets' `AppIntent` buttons, which run in the *widget extension's* process, where
///    `NativePantryViewModel` is never instantiated at all.
///
/// Compiled into both targets so those two paths share one implementation rather than each
/// keeping its own copy to drift out of step with the app's own rules. Every operation here
/// mirrors the matching `NativePantryViewModel` method; when one changes, so must the other.
@MainActor
enum PantryDirectActions {

    /// Consumes one unit, mirroring `NativePantryViewModel.decrementQuantity`: an item with more
    /// than one left stays in the pantry with one fewer, and only the last one being used removes
    /// it (re-stocking an essential, which `deleteItem` handles). Deleting outright would
    /// silently lose the other units.
    static func useOne(_ itemId: String) {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == itemId })
        guard let record = try? context.fetch(descriptor).first else { return }
        guard record.quantity > 1 else {
            deleteItem(itemId)
            return
        }
        record.quantity -= 1
        try? context.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Deletes a pantry item, mirroring `NativePantryViewModel.deleteItem`'s essential-item
    /// shopping-list re-add.
    static func deleteItem(_ itemId: String) {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == itemId })
        guard let record = try? context.fetch(descriptor).first else { return }
        defer { WidgetCenter.shared.reloadAllTimelines() }

        let wasEssential = record.essential
        let name = record.name
        let barcode = record.barcode
        let category = record.category

        context.delete(record)
        try? context.save()
        IOSNotificationManager.shared.cancelNotification(for: itemId)

        guard wasEssential else { return }

        let existing: ShoppingListEntryRecord?
        if let barcode {
            let shoppingDescriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.barcode == barcode })
            existing = try? context.fetch(shoppingDescriptor).first
        } else {
            let normalizedName = name.trimmingCharacters(in: .whitespaces).lowercased()
            let shoppingDescriptor = FetchDescriptor<ShoppingListEntryRecord>()
            existing = ((try? context.fetch(shoppingDescriptor)) ?? []).first {
                $0.productName.trimmingCharacters(in: .whitespaces).lowercased() == normalizedName
            }
        }
        guard existing == nil else { return }

        context.insert(ShoppingListEntryRecord(from: ShoppingListEntry(productName: name, barcode: barcode, category: category)))
        try? context.save()
    }

    /// Pushes the expiry out, for the "Still good" notification action. Counted from the item's
    /// *current* expiry rather than from today, so tapping it on something that expired three
    /// days ago doesn't quietly hand it a fresh window. Reschedules from the new date: the
    /// notification has already fired, so without that the item would never warn again.
    static func extendExpiry(_ itemId: String, byDays days: Int) {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == itemId })
        guard let record = try? context.fetch(descriptor).first,
              let extended = Calendar.current.date(byAdding: .day, value: days, to: record.expiryDate) else {
            return
        }
        record.expiryDate = extended
        try? context.save()
        IOSNotificationManager.shared.scheduleNotification(for: record.asPantryItem)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Removes a shopping list entry, exactly as ticking it off in the app does. There is no
    /// undo from a widget — nowhere to put one — so this is the plain delete and never touches
    /// the pantry.
    static func deleteShoppingListEntry(_ entryId: String) {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == entryId })
        guard let record = try? context.fetch(descriptor).first else { return }
        context.delete(record)
        try? context.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Folds or unfolds a shopping-list section from its widget header. The same stored flag
    /// the app's header toggles, so the two always show the same sections open.
    static func setShoppingSectionCollapsed(_ sectionId: String, _ collapsed: Bool) {
        let context = ModelContext(NativePantryViewModel.sharedModelContainer)
        let descriptor = FetchDescriptor<ShoppingSectionRecord>(predicate: #Predicate { $0.id == sectionId })
        if let record = try? context.fetch(descriptor).first {
            record.collapsed = collapsed
        } else if sectionId == ShoppingSection.defaultId {
            var section = ShoppingSection.defaultSection
            section.collapsed = collapsed
            context.insert(ShoppingSectionRecord(from: section))
        } else {
            return
        }
        try? context.save()
        WidgetCenter.shared.reloadAllTimelines()
    }
}

/// "Used one" on an expiry widget row.
struct ConsumeItemIntent: AppIntent {
    static var title: LocalizedStringResource = "Used one"
    /// The button acts in place on the home screen; opening the app would defeat the point of
    /// putting it on the widget.
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Item ID")
    var itemId: String

    init() {}

    init(itemId: String) {
        self.itemId = itemId
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        PantryDirectActions.useOne(itemId)
        return .result()
    }
}

/// "Bought" on a shopping-list widget row.
struct TickOffShoppingEntryIntent: AppIntent {
    static var title: LocalizedStringResource = "Bought"
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Entry ID")
    var entryId: String

    init() {}

    init(entryId: String) {
        self.entryId = entryId
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        PantryDirectActions.deleteShoppingListEntry(entryId)
        return .result()
    }
}

/// A section header on the shopping-list widget: folds or unfolds that section.
struct ToggleShoppingSectionIntent: AppIntent {
    static var title: LocalizedStringResource = "Toggle section"
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Section ID")
    var sectionId: String

    @Parameter(title: "Collapse")
    var collapse: Bool

    init() {}

    init(sectionId: String, collapse: Bool) {
        self.sectionId = sectionId
        self.collapse = collapse
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        PantryDirectActions.setShoppingSectionCollapsed(sectionId, collapse)
        return .result()
    }
}
