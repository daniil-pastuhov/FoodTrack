import Foundation
import SwiftData
import SwiftUI
import WidgetKit

public enum NativeStatusFilter: String, CaseIterable, Identifiable {
    case all = "All"
    case expiringSoon = "Expiring Soon"
    case expired = "Expired"
    case fresh = "Fresh"

    public var id: String { rawValue }

    /// The filter cases shown as tabs in the filter sheet. `fresh` is intentionally excluded —
    /// it's only reachable by tapping the Fresh metric card, mirroring the Android dashboard.
    public static var tabCases: [NativeStatusFilter] { [.all, .expiringSoon, .expired] }

    public var localizedTitle: String {
        switch self {
        case .all: return L10n.statusFilterAll
        case .expiringSoon: return L10n.statusFilterExpiringSoon
        case .expired: return L10n.statusFilterExpired
        case .fresh: return L10n.statusFilterFresh
        }
    }
}

@MainActor
public class NativePantryViewModel: ObservableObject {
    @Published public var items: [PantryItem] = []
    @Published public var searchQuery: String = ""
    @Published public var selectedLocation: String? = nil
    @Published public var selectedCategory: String? = nil
    @Published public var selectedFilter: NativeStatusFilter = .all
    @Published public var isLookingUpBarcode: Bool = false
    @Published public var scannedProduct: ProductInfo? = nil
    @Published public var productNotFoundBarcode: String? = nil
    @Published public var detectedDate: Date? = nil
    @Published public var shoppingList: [ShoppingListEntry] = []
    /// Set while the scan flow was launched from a specific shopping list row (rather than
    /// the dashboard's general scan button), so `saveItem` knows to remove that exact entry
    /// even if it has no barcode of its own to match against (manually-added entries).
    @Published public var scanningShoppingListEntryId: String? = nil
    /// Backs the shopping list's autosuggest — see `ProductNameRecord`.
    @Published public var knownProductNames: [String] = []

    private let modelContext: ModelContext

    /// Shared on-disk container for pantry data. `NativePantryViewModel()` (used at every
    /// call site) resolves to this by default; a test can pass an in-memory container instead.
    ///
    /// Stored in the App Group container (rather than SwiftData's default per-app location) so
    /// `FoodTrackerWidgetExtension` — a separate process/sandbox — can open the exact same
    /// store via this same property.
    public nonisolated static let sharedModelContainer: ModelContainer = {
        let schema = Schema([PantryItemRecord.self, ShoppingListEntryRecord.self, ProductNameRecord.self])
        guard let appGroupURL = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: AppGroup.identifier) else {
            fatalError("App Group '\(AppGroup.identifier)' is not configured for this target — see WIDGETS_TASK.md")
        }
        let storeURL = appGroupURL.appendingPathComponent("FoodTracker.store")
        migrateLegacyStoreIfNeeded(to: storeURL)

        let configuration = ModelConfiguration(schema: schema, url: storeURL)
        do {
            return try ModelContainer(for: schema, configurations: [configuration])
        } catch {
            fatalError("Failed to create SwiftData ModelContainer for PantryItemRecord: \(error)")
        }
    }()

    /// One-time migration for installs that predate the widget's App Group requirement:
    /// SwiftData's default (no explicit `url:`) container lives at
    /// `Application Support/default.store` (+ `-wal`/`-shm` sidecar files). Copies those into
    /// the App Group container the first time this runs, so existing pantry data survives the
    /// move instead of silently resetting to empty.
    private nonisolated static func migrateLegacyStoreIfNeeded(to newStoreURL: URL) {
        let fileManager = FileManager.default
        guard !fileManager.fileExists(atPath: newStoreURL.path) else { return }

        guard let appSupportURL = try? fileManager.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: false
        ) else { return }

        let legacyBase = appSupportURL.appendingPathComponent("default.store").path
        guard fileManager.fileExists(atPath: legacyBase) else { return }

        let newBase = newStoreURL.path
        for suffix in ["", "-wal", "-shm"] {
            let source = legacyBase + suffix
            guard fileManager.fileExists(atPath: source) else { continue }
            try? fileManager.copyItem(atPath: source, toPath: newBase + suffix)
        }
    }

    public init(modelContainer: ModelContainer = NativePantryViewModel.sharedModelContainer) {
        modelContext = ModelContext(modelContainer)
        loadItems()
        loadShoppingList()
        backfillKnownProductNamesFromInventoryIfNeeded()
        loadKnownProductNames()
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
    }

    private var expiringSoonDays: Int { SettingsStore.shared.settings.expiringSoonDays }

    private func loadItems() {
        let descriptor = FetchDescriptor<PantryItemRecord>(sortBy: [SortDescriptor(\.expiryDate)])
        items = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.asPantryItem }
    }

    private func loadShoppingList() {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(
            sortBy: [SortDescriptor(\.sortOrder), SortDescriptor(\.addedDate, order: .reverse)]
        )
        shoppingList = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.asEntry }
    }

    private func loadKnownProductNames() {
        var descriptor = FetchDescriptor<ProductNameRecord>(sortBy: [SortDescriptor(\.lastUsedAt, order: .reverse)])
        descriptor.fetchLimit = 200
        knownProductNames = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.displayName }
    }

    /// Android's counterpart is a one-time Room migration (MIGRATION_5_6); SwiftData has no
    /// declarative migration mechanism here, so this UserDefaults flag is the equivalent
    /// "runs exactly once" guard. Backfills the shopping list's autosuggest with every product
    /// name already sitting in the pantry, so existing inventory suggests itself immediately
    /// instead of only after being re-scanned/re-saved (the only other path that records a
    /// name). Existing known names (and their sortOrder) are left untouched, and lastUsedAt
    /// uses each item's addedDate rather than "now" so backfilled names don't jump to the top
    /// of the recency-ordered suggestion list.
    private func backfillKnownProductNamesFromInventoryIfNeeded() {
        let flagKey = "didBackfillKnownProductNamesFromInventory_v1"
        guard !UserDefaults.standard.bool(forKey: flagKey) else { return }

        for item in items {
            let trimmed = item.name.trimmingCharacters(in: .whitespaces)
            guard !trimmed.isEmpty else { continue }
            let normalized = trimmed.lowercased()
            let descriptor = FetchDescriptor<ProductNameRecord>(predicate: #Predicate { $0.normalizedName == normalized })
            guard (try? modelContext.fetch(descriptor).first) == nil else { continue }
            modelContext.insert(ProductNameRecord(normalizedName: normalized, displayName: trimmed, lastUsedAt: item.addedDate))
        }
        try? modelContext.save()
        UserDefaults.standard.set(true, forKey: flagKey)
    }

    /// Records `name` as a known product name for the shopping list's autosuggest.
    /// Persisted separately from shopping list entries themselves (see `ProductNameRecord`)
    /// so the suggestion survives an entry being checked off or deleted.
    private func recordProductName(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let normalized = trimmed.lowercased()
        let descriptor = FetchDescriptor<ProductNameRecord>(predicate: #Predicate { $0.normalizedName == normalized })
        if let existing = try? modelContext.fetch(descriptor).first {
            existing.displayName = trimmed
            existing.lastUsedAt = Date()
        } else {
            modelContext.insert(ProductNameRecord(normalizedName: normalized, displayName: trimmed, lastUsedAt: Date()))
        }
        try? modelContext.save()
        loadKnownProductNames()
    }

    /// The manual shopping-list position last recorded for `name` via `recordSortOrder`, or nil
    /// if it has never been manually reordered. Lets a shopping list entry re-enter the list at
    /// the position the user last dragged it to, rather than always appending.
    private func sortOrder(forKnownProduct name: String) -> Double? {
        let normalized = name.trimmingCharacters(in: .whitespaces).lowercased()
        let descriptor = FetchDescriptor<ProductNameRecord>(predicate: #Predicate { $0.normalizedName == normalized })
        return (try? modelContext.fetch(descriptor).first)?.sortOrder
    }

    /// Persists `sortOrder` as `name`'s remembered manual position, creating the known-product
    /// record if this name has never been recorded before (e.g. an essential pantry item that
    /// was never manually typed/scanned into the shopping list).
    private func recordSortOrder(name: String, sortOrder: Double) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let normalized = trimmed.lowercased()
        let descriptor = FetchDescriptor<ProductNameRecord>(predicate: #Predicate { $0.normalizedName == normalized })
        if let existing = try? modelContext.fetch(descriptor).first {
            existing.sortOrder = sortOrder
        } else {
            modelContext.insert(ProductNameRecord(normalizedName: normalized, displayName: trimmed, lastUsedAt: Date(), sortOrder: sortOrder))
        }
        try? modelContext.save()
    }

    public var filteredItems: [PantryItem] {
        items.filter { item in
            let matchesLocation = selectedLocation == nil || item.storageLocation == selectedLocation
            let matchesCategory = selectedCategory == nil || item.category == selectedCategory

            let matchesStatus: Bool
            switch selectedFilter {
            case .all:
                matchesStatus = true
            case .expiringSoon:
                matchesStatus = item.daysRemaining >= 0 && item.daysRemaining <= expiringSoonDays
            case .expired:
                matchesStatus = item.daysRemaining < 0
            case .fresh:
                matchesStatus = item.daysRemaining > expiringSoonDays
            }

            let matchesQuery = searchQuery.trimmingCharacters(in: .whitespaces).isEmpty ||
                item.name.localizedCaseInsensitiveContains(searchQuery) ||
                (item.brand?.localizedCaseInsensitiveContains(searchQuery) == true)

            return matchesLocation && matchesCategory && matchesStatus && matchesQuery
        }.sorted(by: sortComparator(for: SettingsStore.shared.settings.sortOption))
    }

    /// Distinct categories present on current items, derived rather than a fixed settings
    /// list (unlike storage locations, there's no user-edited category list).
    public var availableCategories: [String] {
        Array(Set(items.compactMap { $0.category })).sorted()
    }

    private func sortComparator(for option: SortOption) -> (PantryItem, PantryItem) -> Bool {
        switch option {
        case .expiryDate:
            return { $0.expiryDate < $1.expiryDate }
        case .name:
            return { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        case .dateAdded:
            return { $0.addedDate > $1.addedDate }
        case .storageLocation:
            return { $0.storageLocation.localizedCaseInsensitiveCompare($1.storageLocation) == .orderedAscending }
        }
    }

    public var expiredCount: Int {
        items.filter { $0.daysRemaining < 0 }.count
    }

    public var expiringSoonCount: Int {
        items.filter { $0.daysRemaining >= 0 && $0.daysRemaining <= expiringSoonDays }.count
    }

    public var freshCount: Int {
        items.filter { $0.daysRemaining > expiringSoonDays }.count
    }

    public var hasActiveFilters: Bool {
        selectedLocation != nil ||
            selectedCategory != nil ||
            selectedFilter != .all ||
            !searchQuery.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Toggles a metric-card filter on/off: selecting an already-active filter clears it
    /// back to `.all` (show everything), otherwise switches to the tapped filter.
    public func toggleMetricFilter(_ filter: NativeStatusFilter) {
        selectedFilter = selectedFilter == filter ? .all : filter
    }

    public func lookupBarcode(_ barcode: String) {
        isLookingUpBarcode = true
        scannedProduct = nil

        Task {
            guard let url = URL(string: "https://world.openfoodfacts.org/api/v2/product/\(barcode).json") else {
                self.isLookingUpBarcode = false
                return
            }

            var request = URLRequest(url: url)
            request.setValue("FoodTracker-iOS/1.0", forHTTPHeaderField: "User-Agent")

            do {
                let (data, _) = try await URLSession.shared.data(for: request)
                if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let status = json["status"] as? Int, status == 1,
                   let product = json["product"] as? [String: Any] {

                    let name = product["product_name"] as? String ?? "Product \(barcode)"
                    let brand = product["brands"] as? String
                    let image = product["image_front_url"] as? String
                    let category = (product["categories"] as? String)?.components(separatedBy: ",").first?.trimmingCharacters(in: .whitespaces)
                    let quantity = product["quantity"] as? String

                    self.scannedProduct = ProductInfo(
                        barcode: barcode,
                        name: name,
                        brand: brand,
                        imageUrl: image,
                        category: category,
                        quantity: quantity
                    )
                    self.productNotFoundBarcode = nil
                } else {
                    self.scannedProduct = nil
                    self.productNotFoundBarcode = barcode
                }
            } catch {
                self.scannedProduct = nil
                self.productNotFoundBarcode = barcode
            }
            self.isLookingUpBarcode = false
        }
    }

    public func saveItem(_ item: PantryItem) {
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == item.id })
        let existingRecord = try? modelContext.fetch(descriptor).first
        let isNewItem = existingRecord == nil
        if let existingRecord {
            existingRecord.update(from: item)
        } else {
            modelContext.insert(PantryItemRecord(from: item))
        }
        try? modelContext.save()
        loadItems()
        recordProductName(item.name)

        if isNewItem {
            if let entryId = scanningShoppingListEntryId {
                deleteShoppingListEntry(entryId)
            } else if let barcode = item.barcode {
                removeShoppingListEntry(matchingBarcode: barcode)
            }
        }

        IOSNotificationManager.shared.scheduleNotification(for: item)
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
        clearScanState()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// `onItemRemoved` receives the deleted item's last known state, so callers can either
    /// mirror side effects onto it (e.g. cancelling its scheduled notification) or hold onto
    /// it to offer an undo (see `restoreItem`).
    public func deleteItem(_ id: String, onItemRemoved: ((PantryItem) -> Void)? = nil) {
        let deletedItem = items.first(where: { $0.id == id })

        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == id })
        if let existing = try? modelContext.fetch(descriptor).first {
            modelContext.delete(existing)
            try? modelContext.save()
        }
        items.removeAll { $0.id == id }
        IOSNotificationManager.shared.cancelNotification(for: id)
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)

        if let deletedItem {
            onItemRemoved?(deletedItem)
            if deletedItem.essential {
                addShoppingListEntryIfAbsent(name: deletedItem.name, barcode: deletedItem.barcode, category: deletedItem.category)
            }
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Decrements one unit of an item; once the last unit is used it is removed the same way
    /// `deleteItem` removes a whole item (essential items get re-added to the shopping list).
    /// `onItemRemoved` fires only when that full removal happens, receiving the item's state
    /// just before removal (quantity 1), so callers can offer an undo (see `restoreItem`).
    public func decrementQuantity(_ id: String, onItemRemoved: ((PantryItem) -> Void)? = nil) {
        guard let item = items.first(where: { $0.id == id }) else { return }
        if item.quantity <= 1 {
            deleteItem(id, onItemRemoved: onItemRemoved)
        } else {
            var updated = item
            updated.quantity -= 1
            saveItem(updated)
        }
    }

    /// Reverses a `deleteItem`/`decrementQuantity` removal by putting `item` straight back
    /// (its id is preserved, so this is an upsert onto the same row rather than a new item).
    /// If the removal had re-added it to the shopping list as an essential item, that entry
    /// is removed again so the item isn't listed as both in the pantry and needed.
    public func restoreItem(_ item: PantryItem) {
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == item.id })
        if let existingRecord = try? modelContext.fetch(descriptor).first {
            existingRecord.update(from: item)
        } else {
            modelContext.insert(PantryItemRecord(from: item))
        }
        try? modelContext.save()
        loadItems()

        if item.essential {
            if let barcode = item.barcode {
                removeShoppingListEntry(matchingBarcode: barcode)
            } else {
                removeShoppingListEntry(matchingName: item.name)
            }
        }

        IOSNotificationManager.shared.scheduleNotification(for: item)
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
        WidgetCenter.shared.reloadAllTimelines()
    }

    public func addManualShoppingListEntry(_ productName: String) {
        let trimmed = productName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        addShoppingListEntryIfAbsent(name: trimmed, barcode: nil, category: nil)
        recordProductName(trimmed)
    }

    public func beginScanForShoppingListEntry(_ id: String) {
        scanningShoppingListEntryId = id
    }

    /// `onItemRemoved` receives the deleted entry's last known state, so callers can hold onto
    /// it to offer an undo (see `restoreShoppingListEntry`).
    public func deleteShoppingListEntry(_ id: String, onItemRemoved: ((ShoppingListEntry) -> Void)? = nil) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
        let existing = try? modelContext.fetch(descriptor).first
        let deletedEntry = existing?.asEntry
        if let existing {
            modelContext.delete(existing)
            try? modelContext.save()
        }
        loadShoppingList()
        if let deletedEntry {
            onItemRemoved?(deletedEntry)
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    public func incrementShoppingListQuantity(_ id: String) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
        guard let existing = try? modelContext.fetch(descriptor).first else { return }
        var updated = existing.asEntry
        updated.quantity += 1
        existing.update(from: updated)
        try? modelContext.save()
        loadShoppingList()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Decrements one unit of a shopping list entry's needed quantity; once the last unit is
    /// decremented the whole entry is removed the same way `deleteShoppingListEntry` does.
    /// `onItemRemoved` fires only when that full removal happens, receiving the entry's state
    /// just before removal (quantity 1), so callers can offer an undo (see
    /// `restoreShoppingListEntry`).
    public func decrementShoppingListQuantity(_ id: String, onItemRemoved: ((ShoppingListEntry) -> Void)? = nil) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
        guard let existing = try? modelContext.fetch(descriptor).first else { return }
        if existing.quantity <= 1 {
            deleteShoppingListEntry(id, onItemRemoved: onItemRemoved)
        } else {
            var updated = existing.asEntry
            updated.quantity -= 1
            existing.update(from: updated)
            try? modelContext.save()
            loadShoppingList()
            WidgetCenter.shared.reloadAllTimelines()
        }
    }

    /// Reverses a `deleteShoppingListEntry`/`decrementShoppingListQuantity` removal by putting
    /// `entry` straight back onto the shopping list (its id is preserved, so this is an upsert
    /// onto the same row rather than a new entry).
    public func restoreShoppingListEntry(_ entry: ShoppingListEntry) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == entry.id })
        if let existingRecord = try? modelContext.fetch(descriptor).first {
            existingRecord.update(from: entry)
        } else {
            modelContext.insert(ShoppingListEntryRecord(from: entry))
        }
        try? modelContext.save()
        loadShoppingList()
        WidgetCenter.shared.reloadAllTimelines()
    }

    private func removeShoppingListEntry(matchingBarcode barcode: String) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.barcode == barcode })
        if let existing = try? modelContext.fetch(descriptor).first {
            modelContext.delete(existing)
            try? modelContext.save()
            loadShoppingList()
        }
    }

    private func removeShoppingListEntry(matchingName name: String) {
        let normalizedName = name.trimmingCharacters(in: .whitespaces).lowercased()
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>()
        if let existing = ((try? modelContext.fetch(descriptor)) ?? []).first(where: {
            $0.productName.trimmingCharacters(in: .whitespaces).lowercased() == normalizedName
        }) {
            modelContext.delete(existing)
            try? modelContext.save()
            loadShoppingList()
        }
    }

    private func addShoppingListEntryIfAbsent(name: String, barcode: String?, category: String?) {
        let existing: ShoppingListEntryRecord?
        if let barcode {
            let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.barcode == barcode })
            existing = try? modelContext.fetch(descriptor).first
        } else {
            let normalizedName = name.trimmingCharacters(in: .whitespaces).lowercased()
            let descriptor = FetchDescriptor<ShoppingListEntryRecord>()
            existing = ((try? modelContext.fetch(descriptor)) ?? []).first {
                $0.productName.trimmingCharacters(in: .whitespaces).lowercased() == normalizedName
            }
        }
        guard existing == nil else { return }

        // Restore this product's last manually-dragged position if it has one, so a re-added
        // item (e.g. an essential pantry item running out again) lands back where the user put
        // it rather than always appending to the end.
        let resolvedSortOrder = sortOrder(forKnownProduct: name) ?? (maxShoppingListSortOrder() + 1)

        modelContext.insert(ShoppingListEntryRecord(from: ShoppingListEntry(
            productName: name,
            barcode: barcode,
            category: category,
            sortOrder: resolvedSortOrder
        )))
        try? modelContext.save()
        loadShoppingList()
    }

    private func maxShoppingListSortOrder() -> Double {
        var descriptor = FetchDescriptor<ShoppingListEntryRecord>(sortBy: [SortDescriptor(\.sortOrder, order: .reverse)])
        descriptor.fetchLimit = 1
        return (try? modelContext.fetch(descriptor).first)?.sortOrder ?? 0
    }

    /// Called after a drag-and-drop reorder completes. `beforeId`/`afterId` are the ids of the
    /// entries now immediately above/below `movedId` within the same category group (nil at a
    /// group boundary). Computes a fractional sort order between them and persists it both on
    /// the moved entry and on its product name, so the position is remembered the next time this
    /// product re-enters the shopping list (see `addShoppingListEntryIfAbsent`).
    public func moveShoppingListEntry(_ movedId: String, beforeId: String?, afterId: String?) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == movedId })
        guard let moved = try? modelContext.fetch(descriptor).first else { return }

        let beforeOrder = beforeId.flatMap { id -> Double? in
            let d = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
            return (try? modelContext.fetch(d).first)?.sortOrder
        }
        let afterOrder = afterId.flatMap { id -> Double? in
            let d = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
            return (try? modelContext.fetch(d).first)?.sortOrder
        }

        let newSortOrder: Double
        if let beforeOrder, let afterOrder {
            newSortOrder = (beforeOrder + afterOrder) / 2
        } else if let beforeOrder {
            newSortOrder = beforeOrder + 1
        } else if let afterOrder {
            newSortOrder = afterOrder - 1
        } else {
            newSortOrder = moved.sortOrder
        }

        moved.sortOrder = newSortOrder
        try? modelContext.save()
        recordSortOrder(name: moved.productName, sortOrder: newSortOrder)
        loadShoppingList()
    }

    public func clearScanState() {
        scannedProduct = nil
        productNotFoundBarcode = nil
        detectedDate = nil
        isLookingUpBarcode = false
        scanningShoppingListEntryId = nil
    }

    public func clearAllData() {
        try? modelContext.delete(model: PantryItemRecord.self)
        try? modelContext.save()
        items = []
        IOSNotificationManager.shared.cancelAll()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// The share sheet payload rides in-process (no IPC size ceiling like Android's Binder
    /// transaction buffer), but a pantry-sized dump of "other items" is still capped so a huge
    /// pantry can't balloon the prompt without bound.
    private static let maxOtherItemsChars = 20_000

    /// Builds a device-language prompt asking an AI assistant (ChatGPT/Gemini/Claude, via the
    /// share sheet) for recipe ideas using the given items, ordered soonest-to-expire first.
    ///
    /// `otherItems` is an optional list of additional pantry items (e.g. everything not
    /// explicitly selected) offered to the AI as extra ingredients it could also use. Only names
    /// are listed, and the list is silently truncated at `maxOtherItemsChars`.
    public static func recipePrompt(for items: [PantryItem], otherItems: [PantryItem] = []) -> String {
        let lines = items
            .sorted { $0.daysRemaining < $1.daysRemaining }
            .map { "- " + L10n.recipePromptItem(name: $0.name, expiry: $0.daysRemainingFormatted) }
            .joined(separator: "\n")
        var prompt = L10n.recipePromptIntro + "\n\n" + lines

        if !otherItems.isEmpty {
            let names = otherItems.map { $0.name }.sorted()
            var otherNames = ""
            var includedCount = 0
            for name in names {
                let lengthIfAdded = otherNames.count + (otherNames.isEmpty ? 0 : 2) + name.count
                if lengthIfAdded > maxOtherItemsChars { break }
                if !otherNames.isEmpty { otherNames += ", " }
                otherNames += name
                includedCount += 1
            }
            if includedCount > 0 {
                prompt += "\n\n" + L10n.recipePromptOtherItemsIntro + " " + otherNames
                let omittedCount = otherItems.count - includedCount
                if omittedCount > 0 {
                    prompt += " " + L10n.recipePromptOtherItemsOmitted(omittedCount)
                }
            }
        }
        return prompt
    }
}
