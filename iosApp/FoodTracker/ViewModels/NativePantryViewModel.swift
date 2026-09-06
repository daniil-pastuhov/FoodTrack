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

// Open Food Facts sometimes returns tag-style categories with a language prefix, e.g. "en:Milk".
func stripLanguagePrefix(_ raw: String) -> String {
    guard let colonIndex = raw.firstIndex(of: ":") else { return raw }
    let prefix = raw[raw.startIndex..<colonIndex]
    guard (1...3).contains(prefix.count), prefix.allSatisfy({ $0.isLetter }) else { return raw }
    return raw[raw.index(after: colonIndex)...].trimmingCharacters(in: .whitespaces)
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
    /// Rename/reorder/enable state for each raw category value seen on a product, managed from
    /// Settings (see `CategorySettingsView` / `CategoryPreferenceRecord`).
    @Published public var categoryPreferences: [CategoryPreference] = []

    private let modelContext: ModelContext

    /// Shared on-disk container for pantry data. `NativePantryViewModel()` (used at every
    /// call site) resolves to this by default; a test can pass an in-memory container instead.
    ///
    /// Stored in the App Group container (rather than SwiftData's default per-app location) so
    /// `FoodTrackerWidgetExtension` — a separate process/sandbox — can open the exact same
    /// store via this same property.
    public nonisolated static let sharedModelContainer: ModelContainer = {
        let schema = Schema([
            PantryItemRecord.self,
            ShoppingListEntryRecord.self,
            ProductNameRecord.self,
            LocalProductRecord.self,
            CategoryPreferenceRecord.self
        ])
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
        
        // Ensure "Other" (uncategorized) sentinel is registered so it's always in Settings
        registerSentinelCategoryIfNeeded()
        
        loadCategoryPreferences()
        loadItems()
        loadShoppingList()
        backfillKnownProductNamesFromInventoryIfNeeded()
        loadKnownProductNames()
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
    }

    private func registerSentinelCategoryIfNeeded() {
        let descriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.originName == UNCATEGORIZED_ORIGIN })
        if (try? modelContext.fetch(descriptor).first) == nil {
            modelContext.insert(CategoryPreferenceRecord(from: CategoryPreference(
                originName: UNCATEGORIZED_ORIGIN,
                displayName: L10n.shoppingListUncategorized,
                sortOrder: .greatestFiniteMagnitude,
                enabled: true
            )))
            try? modelContext.save()
        }
    }

    private var expiringSoonDays: Int { SettingsStore.shared.settings.expiringSoonDays }

    private func loadItems() {
        let descriptor = FetchDescriptor<PantryItemRecord>(sortBy: [SortDescriptor(\.expiryDate)])
        items = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.asPantryItem }
        registerCategories(items.compactMap { $0.category })
    }

    private func loadShoppingList() {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(
            sortBy: [SortDescriptor(\.sortOrder), SortDescriptor(\.addedDate, order: .reverse)]
        )
        shoppingList = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.asEntry }
        registerCategories(shoppingList.compactMap { $0.category })
    }

    private func loadCategoryPreferences() {
        let descriptor = FetchDescriptor<CategoryPreferenceRecord>(sortBy: [SortDescriptor(\.sortOrder)])
        categoryPreferences = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.asPreference }
    }

    /// Auto-registers any category in `origins` not already known, so it shows up in Settings
    /// (see `CategorySettingsView`) the first time a product carrying it is scanned or added,
    /// without requiring the user to configure anything up front. Origins already known (per the
    /// latest `categoryPreferences`) are skipped without a DB write.
    private func registerCategories(_ origins: [String]) {
        let known = Set(categoryPreferences.map { $0.originName })
        let newOrigins = Array(Set(origins)).filter { !known.contains($0) }
        guard !newOrigins.isEmpty else { return }

        var nextSortOrder = maxCategorySortOrder() + 1
        for origin in newOrigins {
            modelContext.insert(CategoryPreferenceRecord(from: CategoryPreference(originName: origin, displayName: origin, sortOrder: nextSortOrder)))
            nextSortOrder += 1
        }
        try? modelContext.save()
        loadCategoryPreferences()
    }

    private func maxCategorySortOrder() -> Double {
        var descriptor = FetchDescriptor<CategoryPreferenceRecord>(sortBy: [SortDescriptor(\.sortOrder, order: .reverse)])
        descriptor.fetchLimit = 1
        return (try? modelContext.fetch(descriptor).first)?.sortOrder ?? -1
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

    /// Upserts a barcode's product details into the local cache (see `LocalProductRecord`) so a
    /// later scan of the same barcode prefetches it here instead of hitting the network again —
    /// this is what makes a once-manually-entered "custom" product recognized on a later scan.
    private func saveLocalProduct(barcode: String, name: String, brand: String?, imageUrl: String?, category: String?) {
        let trimmed = barcode.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == trimmed })
        if let existing = try? modelContext.fetch(descriptor).first {
            existing.name = name
            existing.brand = brand
            existing.imageUrl = imageUrl
            existing.category = category
        } else {
            modelContext.insert(LocalProductRecord(barcode: trimmed, name: name, brand: brand, imageUrl: imageUrl, category: category))
        }
        try? modelContext.save()
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
            
            // Category filter. Filter by the resolved display name, so that multiple origins
            // renamed to the same name (or disabled categories folding into "Other") are
            // treated as a single selectable group.
            let itemDisplay = resolveCategoryDisplayName(item.category, categoryPreferences)
            let matchesCategory = selectedCategory == nil || itemDisplay == selectedCategory

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

    /// Distinct, enabled categories present on current items, derived rather than a fixed
    /// settings list (unlike storage locations) but filtered/ordered per the user's category
    /// preferences (see `CategorySettingsView`) rather than shown alphabetically.
    public var availableCategories: [String] {
        let displays = items.map { resolveCategoryDisplayName($0.category, categoryPreferences) }
            .compactMap { $0 }
        
        return Array(Set(displays))
            .sorted { lhs, rhs in
                let lhsOrder = categoryPreferences
                    .filter { $0.displayName == lhs && $0.enabled }
                    .map { $0.sortOrder }
                    .min() ?? .greatestFiniteMagnitude
                let rhsOrder = categoryPreferences
                    .filter { $0.displayName == rhs && $0.enabled }
                    .map { $0.sortOrder }
                    .min() ?? .greatestFiniteMagnitude
                
                if lhsOrder != rhsOrder { return lhsOrder < rhsOrder }
                return lhs < rhs
            }
    }

    /// The label to show for a raw category value `origin` (e.g. a filter chip), resolved
    /// through the user's category preferences.
    public func categoryLabel(for origin: String) -> String {
        resolveCategoryDisplayName(origin, categoryPreferences) ?? origin
    }

    // Ties need an explicit secondary key so the sort doesn't silently fall back to whatever
    // order `items` happened to arrive in (see the same fix in PantryStateHolder.kt).
    private func sortComparator(for option: SortOption) -> (PantryItem, PantryItem) -> Bool {
        switch option {
        case .expiryDate:
            return { lhs, rhs in
                if lhs.expiryDate != rhs.expiryDate { return lhs.expiryDate < rhs.expiryDate }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
        case .name:
            return { lhs, rhs in
                let cmp = lhs.name.localizedCaseInsensitiveCompare(rhs.name)
                if cmp != .orderedSame { return cmp == .orderedAscending }
                return lhs.addedDate > rhs.addedDate
            }
        case .dateAdded:
            return { lhs, rhs in
                if lhs.addedDate != rhs.addedDate { return lhs.addedDate > rhs.addedDate }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
        case .storageLocation:
            return { lhs, rhs in
                let cmp = lhs.storageLocation.localizedCaseInsensitiveCompare(rhs.storageLocation)
                if cmp != .orderedSame { return cmp == .orderedAscending }
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
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

        // A locally-cached product (whether it was previously found via the API or entered by
        // hand for a barcode OFF didn't know) wins over the network, so a "custom" product from
        // a past scan is recognized here instead of showing not-found again.
        let cacheDescriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == barcode })
        if let cached = try? modelContext.fetch(cacheDescriptor).first {
            scannedProduct = ProductInfo(
                barcode: cached.barcode,
                name: cached.name,
                brand: cached.brand,
                imageUrl: cached.imageUrl,
                category: cached.category,
                quantity: nil
            )
            productNotFoundBarcode = nil
            isLookingUpBarcode = false
            return
        }

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
                    let rawCategory = (product["categories"] as? String)?.components(separatedBy: ",").first?.trimmingCharacters(in: .whitespaces)
                    let category = rawCategory.map(stripLanguagePrefix)
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
        if let barcode = item.barcode {
            saveLocalProduct(barcode: barcode, name: item.name, brand: item.brand, imageUrl: item.imageUrl, category: item.category)
        }

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

    /// A plain delete: removes the item outright and never touches the shopping list, even for
    /// an essential item — only `decrementQuantity` and `moveToShoppingList` do that.
    /// `onItemRemoved` receives the deleted item's last known state and whether the removal
    /// added a shopping-list entry for it (always false here), so callers can mirror side
    /// effects onto it (e.g. cancelling its scheduled notification) and offer an undo that
    /// knows whether to reverse a shopping-list add (see `restoreItem`).
    public func deleteItem(_ id: String, onItemRemoved: ((PantryItem, Bool) -> Void)? = nil) {
        removeItemRecord(id) { removed in onItemRemoved?(removed, false) }
    }

    /// Decrements one unit of an item; once the last unit is used it is removed the same way
    /// `deleteItem` removes a whole item, except an essential item is also re-added to the
    /// shopping list here (a plain `deleteItem` never does that).
    /// `onItemRemoved` fires only when that full removal happens, receiving the item's state
    /// just before removal (quantity 1) and whether it was re-added to the shopping list, so
    /// callers can offer an undo that reverses both (see `restoreItem`).
    public func decrementQuantity(_ id: String, onItemRemoved: ((PantryItem, Bool) -> Void)? = nil) {
        guard let item = items.first(where: { $0.id == id }) else { return }
        if item.quantity <= 1 {
            removeItemRecord(id) { removed in
                if removed.essential {
                    self.addShoppingListEntryIfAbsent(name: removed.name, barcode: removed.barcode, category: removed.category)
                }
                onItemRemoved?(removed, removed.essential)
            }
        } else {
            var updated = item
            updated.quantity -= 1
            saveItem(updated)
        }
    }

    /// Explicit "move to shopping list" action (the Home screen's leading swipe action): removes
    /// the item from the pantry and adds it to the shopping list unconditionally, regardless of
    /// its `essential` flag — unlike `decrementQuantity`, this is a deliberate user choice rather
    /// than an automatic essential-item safeguard. `onItemRemoved` fires with `true` for the
    /// shopping-list flag so an undo (see `restoreItem`) reverses both the pantry removal and
    /// the shopping-list add.
    public func moveToShoppingList(_ id: String, onItemRemoved: ((PantryItem, Bool) -> Void)? = nil) {
        removeItemRecord(id) { removed in
            self.addShoppingListEntryIfAbsent(name: removed.name, barcode: removed.barcode, category: removed.category)
            onItemRemoved?(removed, true)
        }
    }

    /// Shared removal core behind `deleteItem`, `decrementQuantity`'s last-unit case, and
    /// `moveToShoppingList`: deletes the SwiftData record, cancels the item's notification, and
    /// reloads widgets. Never touches the shopping list itself — callers decide that.
    private func removeItemRecord(_ id: String, onItemRemoved: ((PantryItem) -> Void)? = nil) {
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
        }
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Reverses a `deleteItem`/`decrementQuantity`/`moveToShoppingList` removal by putting `item`
    /// straight back (its id is preserved, so this is an upsert onto the same row rather than a
    /// new item). Pass `removedFromShoppingList: true` whenever the removal being reversed added
    /// a shopping-list entry for it (per the `Bool` each of those methods hands its
    /// `onItemRemoved` callback), so that entry is removed again; a plain `deleteItem` never
    /// added one, so leave it false there.
    public func restoreItem(_ item: PantryItem, removedFromShoppingList: Bool = false) {
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == item.id })
        if let existingRecord = try? modelContext.fetch(descriptor).first {
            existingRecord.update(from: item)
        } else {
            modelContext.insert(PantryItemRecord(from: item))
        }
        try? modelContext.save()
        loadItems()

        if removedFromShoppingList {
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

    /// Renames the category identified by `originName` (its raw, scanned value - see
    /// `CategoryPreference.originName`) to `newDisplayName`. Applies retroactively everywhere
    /// that category is shown, without touching the stored `category` field on any product,
    /// since display always resolves through `CategoryPreference` (see
    /// `resolveCategoryDisplayName`).
    public func renameCategory(_ originName: String, newDisplayName: String) {
        let trimmed = newDisplayName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        
        let descriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.originName == originName })
        guard let current = try? modelContext.fetch(descriptor).first else { return }
        
        // If we're renaming to an existing display name, sync the enabled state and sort order
        // to effectively "merge" them in the UI.
        let existingDescriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.displayName == trimmed })
        let existing = try? modelContext.fetch(existingDescriptor).first
        
        current.displayName = trimmed
        if let existing {
            current.enabled = existing.enabled
            current.sortOrder = existing.sortOrder
        }
        
        try? modelContext.save()
        loadCategoryPreferences()
    }

    /// Enables or disables the category identified by `originName`. A disabled category is
    /// hidden everywhere (filter chips, item badges, shopping list group headers) - products
    /// that have it simply show as uncategorized, per `resolveCategoryDisplayName`.
    public func setCategoryEnabled(_ originName: String, enabled: Bool) {
        let descriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.originName == originName })
        guard let target = try? modelContext.fetch(descriptor).first else { return }
        
        // Sync all origins sharing this display name so they enable/disable as a unit
        let displayName = target.displayName
        let allDescriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.displayName == displayName })
        if let all = try? modelContext.fetch(allDescriptor) {
            for record in all {
                record.enabled = enabled
            }
        }
        
        try? modelContext.save()
        loadCategoryPreferences()
    }

    /// Bulk on/off switch for every category at once, so the user isn't forced to flip each
    /// `setCategoryEnabled` toggle one by one.
    public func setAllCategoriesEnabled(_ enabled: Bool) {
        let descriptor = FetchDescriptor<CategoryPreferenceRecord>()
        guard let all = try? modelContext.fetch(descriptor) else { return }
        for record in all { record.enabled = enabled }
        try? modelContext.save()
        loadCategoryPreferences()
    }

    /// Called after a drag-and-drop reorder of categories in Settings completes, mirroring
    /// `moveShoppingListEntry`'s fractional sort-order scheme. `beforeOrigin`/`afterOrigin` are
    /// the origin names now immediately above/below `movedOrigin` (nil at a list boundary).
    public func moveCategoryPreference(_ movedOrigin: String, beforeOrigin: String?, afterOrigin: String?) {
        let descriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.originName == movedOrigin })
        guard let moved = try? modelContext.fetch(descriptor).first else { return }

        let beforeOrder = beforeOrigin.flatMap { origin -> Double? in
            let d = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.originName == origin })
            return (try? modelContext.fetch(d).first)?.sortOrder
        }
        let afterOrder = afterOrigin.flatMap { origin -> Double? in
            let d = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.originName == origin })
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

        // Sync all origins sharing this display name so they reorder as a unit
        let displayName = moved.displayName
        let allDescriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.displayName == displayName })
        if let all = try? modelContext.fetch(allDescriptor) {
            for record in all {
                record.sortOrder = newSortOrder
            }
        }
        
        try? modelContext.save()
        loadCategoryPreferences()
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
