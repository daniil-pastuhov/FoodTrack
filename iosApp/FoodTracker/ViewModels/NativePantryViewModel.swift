import Foundation
import SwiftData
import SwiftUI
import UIKit
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
    /// Expiry date to pre-fill on the item this scan produces, and where it came from - a date
    /// read off the packaging, the shelf life this barcode was given last time, or its
    /// category's configured default. See `ShelfLife.suggest`.
    @Published public var suggestedExpiry: ShelfLifeSuggestion? = nil
    /// Names suggested for a photographed, barcode-less product by the on-device image
    /// classifier (see `FoodLabels`). Empty unless a photo was just captured.
    @Published public var photoLabelSuggestions: [FoodLabelSuggestion] = []
    /// True while that classification is running, so the form can say so rather than showing
    /// nothing and then popping chips in.
    @Published public var isLabelingPhoto: Bool = false
    /// Products scanned in batch mode and waiting to be reviewed and committed together.
    @Published public var batchQueue: [BatchScanEntry] = []
    /// Set when a scanned barcode is already in the pantry, so the UI can offer to add a unit
    /// to that item rather than create a second row for the same product.
    @Published public var duplicateOf: PantryItem?
    /// Allergens the scanned product lists that the user asked to be warned about. Empty unless
    /// both sides have something to match.
    @Published public var allergenWarnings: [String] = []
    @Published public var shoppingList: [ShoppingListEntry] = []
    /// The user's shopping-list sections in display order, the default one first (see
    /// `ShoppingSection`).
    @Published public var shoppingSections: [ShoppingSection] = [.defaultSection]
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
    private var dayChangeObserver: NSObjectProtocol?

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
            CategoryPreferenceRecord.self,
            ShoppingSectionRecord.self
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
        migrateShoppingCategoriesToSectionsIfNeeded()
        loadShoppingSections()
        loadShoppingList()
        backfillKnownProductNamesFromInventoryIfNeeded()
        backfillShelfLivesFromInventoryIfNeeded()
        loadKnownProductNames()
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
        observeDayChanges()
    }

    /// `PantryItem.daysRemaining` and every count/filter built on it read the clock when they're
    /// evaluated, but SwiftUI only re-evaluates them when something published changes — so an
    /// app left open across midnight kept showing yesterday's countdowns. iOS posts
    /// `significantTimeChangeNotification` at midnight, on clock and time-zone changes, and on
    /// becoming active if one of those happened while suspended; a redraw is all that's needed.
    private func observeDayChanges() {
        dayChangeObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.significantTimeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.objectWillChange.send()
            }
        }
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

    private func loadShoppingSections() {
        let records = (try? modelContext.fetch(FetchDescriptor<ShoppingSectionRecord>())) ?? []
        shoppingSections = ShoppingSections.ordered(records.map { $0.asSection })
    }

    /// Re-reads the shopping list and its sections from the store, for when the widget (a
    /// separate process) may have changed them - ticked an entry off, or folded a section.
    public func reloadShoppingList() {
        loadShoppingSections()
        loadShoppingList()
    }

    /// The sectioned rows the shopping list (and its share text) shows.
    public var shoppingRows: [ShoppingRow] {
        ShoppingSections.rows(entries: shoppingList, sections: shoppingSections)
    }

    /// Replaces the shopping list's old category grouping with sections, once - the counterpart
    /// of Android's `MIGRATION_12_13`, with the same rules so both platforms upgrade the same
    /// way. Every category that currently groups shopping entries becomes a section of the name
    /// the user sees, in the same order, and its entries (and their product names, so a re-add
    /// lands there too) move into it. A disabled category, or none, was the "Other" bucket,
    /// which is the default section now; a category with no preference shows - and becomes a
    /// section - under its raw value.
    private func migrateShoppingCategoriesToSectionsIfNeeded() {
        let flagKey = "shoppingSectionsMigrated"
        guard !UserDefaults.standard.bool(forKey: flagKey) else { return }

        let entries = (try? modelContext.fetch(FetchDescriptor<ShoppingListEntryRecord>())) ?? []
        let preferences = Dictionary(
            categoryPreferences.map { ($0.originName, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        var sectionNameByEntry: [String: String] = [:]
        var orderByName: [String: Double] = [:]
        for entry in entries {
            guard let category = entry.category, !category.isEmpty else { continue }
            let name: String
            let order: Double
            if let preference = preferences[category] {
                guard preference.enabled else { continue }
                name = preference.displayName
                order = preference.sortOrder
            } else {
                name = category
                order = .greatestFiniteMagnitude
            }
            guard !name.trimmingCharacters(in: .whitespaces).isEmpty else { continue }
            sectionNameByEntry[entry.id] = name
            orderByName[name] = min(orderByName[name] ?? .greatestFiniteMagnitude, order)
        }

        let names = orderByName.keys.sorted {
            let (lhs, rhs) = (orderByName[$0]!, orderByName[$1]!)
            return lhs != rhs ? lhs < rhs : $0 < $1
        }
        var idByName: [String: String] = [:]
        for (index, name) in names.enumerated() {
            let id = "migrated_" + name.utf8.map { String(format: "%02X", $0) }.joined()
            idByName[name] = id
            modelContext.insert(ShoppingSectionRecord(from: ShoppingSection(id: id, name: name, sortOrder: Double(index))))
        }
        let products = (try? modelContext.fetch(FetchDescriptor<ProductNameRecord>())) ?? []
        let productByName = Dictionary(products.map { ($0.normalizedName, $0) }, uniquingKeysWith: { first, _ in first })
        for entry in entries {
            guard let name = sectionNameByEntry[entry.id], let id = idByName[name] else { continue }
            entry.sectionId = id
            let normalized = entry.productName.trimmingCharacters(in: .whitespaces).lowercased()
            productByName[normalized]?.shoppingSectionId = id
        }
        try? modelContext.save()
        UserDefaults.standard.set(true, forKey: flagKey)
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

    /// Highest sort order in use, **excluding** the `UNCATEGORIZED_ORIGIN` sentinel. That one is
    /// parked at `.greatestFiniteMagnitude` so the "Other" bucket sorts last, and counting it
    /// here would make the next order `greatestFiniteMagnitude + 1` — which in Double arithmetic
    /// is `greatestFiniteMagnitude` again, so every newly registered category would land on that
    /// same order and the list would have no order left to drag rows around in.
    private func maxCategorySortOrder() -> Double {
        var descriptor = FetchDescriptor<CategoryPreferenceRecord>(
            predicate: #Predicate { $0.originName != UNCATEGORIZED_ORIGIN },
            sortBy: [SortDescriptor(\.sortOrder, order: .reverse)]
        )
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
    private func saveLocalProduct(
        barcode: String,
        name: String,
        brand: String?,
        imageUrl: String?,
        category: String?,
        nutrition: ProductNutrition = ProductNutrition()
    ) {
        let trimmed = barcode.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == trimmed })
        if let existing = try? modelContext.fetch(descriptor).first {
            // Deliberately leaves shelfLifeDays and storageLocation alone: both are learned from
            // what the user chose, not carried on the Open Food Facts response this re-caches
            // from, so overwriting them here would forget the suggestion on every re-scan.
            existing.name = name
            existing.brand = brand
            existing.imageUrl = imageUrl
            existing.category = category
            // A manually-entered product carries no nutrition, and neither does a lookup OFF
            // has nothing for. Keep whatever a previous successful lookup cached rather than
            // blanking it - losing a cached allergen list is the one outcome worth avoiding.
            if !nutrition.isEmpty { existing.apply(nutrition) }
        } else {
            modelContext.insert(
                LocalProductRecord(
                    barcode: trimmed,
                    name: name,
                    brand: brand,
                    imageUrl: imageUrl,
                    category: category,
                    nutrition: nutrition
                )
            )
        }
        try? modelContext.save()
    }

    /// Resolves the expiry date to pre-fill for a scanned product: the date read off the
    /// packaging if there was one, else the shelf life this exact barcode was last given, else
    /// the default configured for its category.
    ///
    /// The category is resolved through `resolveCategoryOrigin` first, because a product's raw
    /// category may have been renamed and the default hangs off the origin. Mirrors
    /// `PantryStateHolder.suggestExpiry` on Android.
    private func resolveSuggestedExpiry(barcode: String?, category: String?) -> ShelfLifeSuggestion {
        var rememberedDays: Int? = nil
        if detectedDate == nil, let barcode {
            let trimmed = barcode.trimmingCharacters(in: .whitespaces)
            let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == trimmed })
            rememberedDays = (try? modelContext.fetch(descriptor).first)?.shelfLifeDays
        }
        // Match the product's raw category against a preference by origin first, then by
        // display name - a category the user renamed keeps its original origin on the product,
        // while a freehand-typed one may only match what it was renamed *to*. Android does the
        // same via `resolveCategoryOrigin`.
        let trimmedCategory = category?.trimmingCharacters(in: .whitespaces)
        let origin = (trimmedCategory?.isEmpty ?? true) ? UNCATEGORIZED_ORIGIN : trimmedCategory!
        let preference = categoryPreferences.first { $0.originName == origin }
            ?? categoryPreferences.first { $0.displayName.caseInsensitiveCompare(origin) == .orderedSame }
        let categoryDefaultDays = preference?.defaultShelfLifeDays
        return ShelfLife.suggest(
            detectedDate: detectedDate,
            rememberedDays: rememberedDays,
            categoryDefaultDays: categoryDefaultDays
        )
    }

    /// Learns how long `item`'s product keeps, so a future scan of the same barcode pre-fills
    /// its expiry date (see `resolveSuggestedExpiry`). Only new items teach the cache: editing
    /// an old item's date says nothing about how long a *fresh* one lasts, which is the same
    /// rule `rememberStorageLocation` follows. An implausible span is skipped rather than
    /// stored - see `ShelfLife.learnableShelfLife`.
    ///
    /// Must run after `saveLocalProduct`, which is what guarantees the row exists: this
    /// deliberately only updates an already-cached barcode, so it can't leave behind a
    /// half-populated, nameless product.
    private func rememberShelfLife(barcode: String, item: PantryItem) {
        guard let days = ShelfLife.learnableShelfLife(addedDate: item.addedDate, expiryDate: item.expiryDate) else { return }
        let trimmed = barcode.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == trimmed })
        guard let existing = try? modelContext.fetch(descriptor).first else { return }
        existing.shelfLifeDays = days
        try? modelContext.save()
    }

    /// Android's counterpart is a one-time Room migration (MIGRATION_8_9); SwiftData has no
    /// declarative migration mechanism here, so this UserDefaults flag is the equivalent
    /// "runs exactly once" guard - the same pattern
    /// `backfillKnownProductNamesFromInventoryIfNeeded` uses.
    ///
    /// Seeds each cached product's shelf life from the most recently added pantry item carrying
    /// that barcode, so an existing user gets suggestions for products they already own
    /// immediately rather than only after adding each one again. Spans
    /// `ShelfLife.learnableShelfLife` would reject are skipped, leaving the value nil rather
    /// than seeding something the app would refuse to learn itself.
    private func backfillShelfLivesFromInventoryIfNeeded() {
        let flagKey = "didBackfillShelfLivesFromInventory_v1"
        guard !UserDefaults.standard.bool(forKey: flagKey) else { return }

        var newestByBarcode: [String: PantryItem] = [:]
        for item in items {
            guard let barcode = item.barcode?.trimmingCharacters(in: .whitespaces), !barcode.isEmpty else { continue }
            if let existing = newestByBarcode[barcode], existing.addedDate >= item.addedDate { continue }
            newestByBarcode[barcode] = item
        }

        for (barcode, item) in newestByBarcode {
            guard let days = ShelfLife.learnableShelfLife(addedDate: item.addedDate, expiryDate: item.expiryDate) else { continue }
            let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == barcode })
            guard let record = try? modelContext.fetch(descriptor).first, record.shelfLifeDays == nil else { continue }
            record.shelfLifeDays = days
        }
        try? modelContext.save()
        UserDefaults.standard.set(true, forKey: flagKey)
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

    /// The shopping-list section `name` was last moved to, or nil if it never left the default.
    private func sectionId(forKnownProduct name: String) -> String? {
        let normalized = name.trimmingCharacters(in: .whitespaces).lowercased()
        let descriptor = FetchDescriptor<ProductNameRecord>(predicate: #Predicate { $0.normalizedName == normalized })
        return (try? modelContext.fetch(descriptor).first)?.shoppingSectionId
    }

    private func recordSectionId(name: String, sectionId: String?) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let normalized = trimmed.lowercased()
        let descriptor = FetchDescriptor<ProductNameRecord>(predicate: #Predicate { $0.normalizedName == normalized })
        if let existing = try? modelContext.fetch(descriptor).first {
            existing.shoppingSectionId = sectionId
        } else {
            let record = ProductNameRecord(normalizedName: normalized, displayName: trimmed, lastUsedAt: Date())
            record.shoppingSectionId = sectionId
            modelContext.insert(record)
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
        L10n.categoryName(resolveCategoryDisplayName(origin, categoryPreferences) ?? origin)
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

    /// Publishes the result of classifying a captured product photo.
    ///
    /// The classification itself is done by the view (see `ScannerSheetView`), not here: this
    /// type is compiled into the widget extension as well, and the widget has no business
    /// linking Vision. Android splits it the same way - `ScannerScreen` calls the labeler and
    /// hands the filtered suggestions to `onPhotoLabelsDetected`.
    public func onPhotoLabelsDetected(_ suggestions: [FoodLabelSuggestion]) {
        photoLabelSuggestions = suggestions
        isLabelingPhoto = false
    }

    public func onPhotoLabelingStarted() {
        photoLabelSuggestions = []
        isLabelingPhoto = true
    }

    public func clearPhotoLabels() {
        photoLabelSuggestions = []
        isLabelingPhoto = false
    }

    // MARK: - Batch scan

    /// Queues `barcode` for the batch-add review list.
    ///
    /// Scanning a barcode already in the queue bumps its quantity instead of adding a second
    /// row — that is what scanning four identical yoghurts in a row means, and it also absorbs
    /// the camera re-reading a product that is simply still in frame.
    ///
    /// The row is published immediately, before the product lookup finishes, so the queue
    /// visibly responds to each scan rather than lagging behind the network.
    public func enqueueBatchScan(_ barcode: String) {
        if let index = batchQueue.firstIndex(where: { $0.barcode == barcode }) {
            batchQueue[index].quantity += 1
            return
        }

        let gs1Date = GS1BarcodeParser.extractExpiryDate(from: barcode)
        let settings = SettingsStore.shared.settings
        batchQueue.append(
            BatchScanEntry(
                // The barcode doubles as the row id for scanned rows, so re-scanning the same
                // product keeps finding the same row. Receipt rows get a generated id instead.
                id: barcode,
                barcode: barcode,
                name: barcode,
                expiryDate: gs1Date ?? Date(),
                storageLocation: settings.storageLocationForNewItem,
                expirySource: gs1Date != nil ? .detected : .fallback,
                isLookingUp: true
            )
        )

        Task {
            // Split rather than `cached ?? await ...`: Swift won't allow an await to the right
            // of a non-assignment operator.
            var product = cachedProduct(for: barcode)
            if product == nil {
                product = await fetchProductFromApi(barcode)
            }
            let suggestion = ShelfLife.suggest(
                detectedDate: gs1Date,
                rememberedDays: gs1Date == nil ? shelfLifeDays(for: barcode) : nil,
                categoryDefaultDays: categoryDefaultShelfLife(for: product?.category)
            )
            // Re-find rather than closing over the index: the user may have edited or removed
            // this row while the lookup was in flight.
            guard let index = batchQueue.firstIndex(where: { $0.id == barcode && $0.isLookingUp }) else { return }
            batchQueue[index].name = product?.name ?? batchQueue[index].name
            batchQueue[index].brand = product?.brand
            batchQueue[index].imageUrl = product?.imageUrl
            batchQueue[index].category = product?.category
            batchQueue[index].expiryDate = suggestion.date
            batchQueue[index].expirySource = suggestion.source
            // Only replace the placeholder's location if the user hasn't already picked
            // another one for this row.
            if let known = knownStorageLocation(for: barcode),
               batchQueue[index].storageLocation == settings.storageLocationForNewItem {
                batchQueue[index].storageLocation = known
            }
            batchQueue[index].isLookingUp = false
        }
    }

    public func removeBatchEntry(_ id: String) {
        batchQueue.removeAll { $0.id == id }
    }

    public func clearBatchQueue() {
        batchQueue = []
    }

    /// Saves everything in the queue and empties it, returning how many items were added.
    ///
    /// Each row goes through `saveItem`, so a batch add still learns shelf lives, caches
    /// products and clears matching shopping list entries exactly as adding one by one would —
    /// none of that is re-implemented here.
    @discardableResult
    public func commitBatchQueue() -> Int {
        let queue = batchQueue
        guard !queue.isEmpty else { return 0 }
        for entry in queue {
            saveItem(
                PantryItem(
                    name: entry.name,
                    brand: entry.brand,
                    barcode: entry.barcode,
                    imageUrl: entry.imageUrl,
                    expiryDate: entry.expiryDate,
                    addedDate: Date(),
                    storageLocation: entry.storageLocation,
                    category: entry.category,
                    quantity: entry.quantity
                )
            )
        }
        clearBatchQueue()
        return queue.count
    }

    // MARK: - Duplicate detection

    /// Records whether `barcode` is already in the pantry, so the UI can offer adding a unit to
    /// that item instead of creating a second row for the same product. The soonest-expiring
    /// match wins, so the offer lands on the one to use up next.
    ///
    /// Only the single-item scan flow asks: batch mode collapses repeats into one row's
    /// quantity by itself, and asking per scan would be exactly the interruption batch mode
    /// exists to avoid.
    public func checkForDuplicate(_ barcode: String) {
        duplicateOf = items
            .filter { $0.barcode == barcode }
            .min { $0.expiryDate < $1.expiryDate }
    }

    public func dismissDuplicate() {
        duplicateOf = nil
    }

    /// Adds one unit to an existing item - the "+1 to the one you already have" answer to a
    /// duplicate scan.
    @discardableResult
    public func incrementQuantity(_ id: String) -> PantryItem? {
        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == id })
        guard let record = try? modelContext.fetch(descriptor).first else { return nil }
        record.quantity += 1
        try? modelContext.save()
        loadItems()
        dismissDuplicate()
        WidgetCenter.shared.reloadAllTimelines()
        return record.asPantryItem
    }

    /// How close a receipt line's text must be to a known product before its details are
    /// adopted. Higher than the shopping list's suggestion threshold, which feeds a dialog the
    /// user answers; this one pre-fills a row silently. Mirrors `shared`'s
    /// `RECEIPT_MATCH_THRESHOLD`.
    private static let receiptMatchThreshold = 0.6

    /// Turns the OCR'd text of a receipt into batch-queue rows, returning how many were added.
    ///
    /// Everything here is a *suggestion*: the rows land in the same queue barcode scanning
    /// uses, where the user reviews, edits and commits them. Receipt text is heuristic in a way
    /// a barcode is not (see `ReceiptParser`), so nothing is saved without that pass.
    ///
    /// A parsed name is matched against the products this device already knows — the
    /// autosuggest names and the local product cache — with the same scorer the shopping list
    /// matches with. A confident hit adopts that product's proper name, category and learned
    /// shelf life, so a receipt's `MLK SEMI 2PT` becomes the milk the user has added before,
    /// with its usual expiry already filled in. A miss keeps the receipt's own text.
    ///
    /// Rows are appended rather than merged: two lines both reading `APPLES` may well be two
    /// separate purchases, and unlike a re-scanned barcode there's no identity to prove
    /// otherwise.
    ///
    /// Mirrors `PantryStateHolder.enqueueReceiptLines`.
    @discardableResult
    public func enqueueReceiptLines(_ ocrLines: [String]) -> Int {
        let parsed = ReceiptParser.parseLines(ocrLines)
        guard !parsed.isEmpty else { return 0 }

        let settings = SettingsStore.shared.settings
        let cached = allCachedProducts()
        let rows = parsed.map { line -> BatchScanEntry in
            let match = matchReceiptLine(line, cached: cached)
            let suggestion = ShelfLife.suggest(
                detectedDate: nil,
                rememberedDays: match?.barcode.flatMap { shelfLifeDays(for: $0) },
                categoryDefaultDays: categoryDefaultShelfLife(for: match?.category)
            )
            return BatchScanEntry(
                id: "receipt_\(UUID().uuidString)",
                barcode: match?.barcode,
                name: match?.name ?? line.name,
                brand: match?.brand,
                imageUrl: match?.imageUrl,
                category: match?.category,
                expiryDate: suggestion.date,
                quantity: line.quantity,
                storageLocation: match?.barcode.flatMap { knownStorageLocation(for: $0) }
                    ?? settings.storageLocationForNewItem,
                expirySource: suggestion.source
            )
        }
        batchQueue.append(contentsOf: rows)
        return rows.count
    }

    /// What a receipt line was resolved to, when it was resolved to anything.
    ///
    /// Not `ProductInfo`: that requires a barcode, and a line matched only against a remembered
    /// name has none. Mirrors `shared`'s `ReceiptMatch`.
    private struct ReceiptMatch {
        let name: String
        var barcode: String?
        var brand: String?
        var imageUrl: String?
        var category: String?
    }

    /// The cached product a receipt line most likely refers to, or nil when nothing is close
    /// enough. Falls back to the autosuggest names: those carry no barcode or category, but
    /// adopting a name the user has typed before still beats keeping a till's abbreviation.
    private func matchReceiptLine(_ line: ReceiptLine, cached: [ReceiptMatch]) -> ReceiptMatch? {
        let best = cached
            .map { ($0, NameSimilarity.score(line.name, $0.name)) }
            .filter { $0.1 >= Self.receiptMatchThreshold }
            .max { $0.1 < $1.1 }?
            .0
        if let best { return best }

        guard let name = NameSimilarity.bestMatch(
            line.name,
            candidates: knownProductNames,
            threshold: Self.receiptMatchThreshold
        ) else {
            return nil
        }
        return ReceiptMatch(name: name)
    }

    private func allCachedProducts() -> [ReceiptMatch] {
        let descriptor = FetchDescriptor<LocalProductRecord>()
        return ((try? modelContext.fetch(descriptor)) ?? []).map {
            ReceiptMatch(
                name: $0.name,
                barcode: $0.barcode,
                brand: $0.brand,
                imageUrl: $0.imageUrl,
                category: $0.category
            )
        }
    }

    private func cachedProduct(for barcode: String) -> ProductInfo? {
        let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == barcode })
        guard let cached = try? modelContext.fetch(descriptor).first else { return nil }
        return ProductInfo(
            barcode: cached.barcode,
            name: cached.name,
            brand: cached.brand,
            imageUrl: cached.imageUrl,
            category: cached.category,
            quantity: nil,
            nutrition: cached.nutrition
        )
    }

    private func shelfLifeDays(for barcode: String) -> Int? {
        let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == barcode })
        return (try? modelContext.fetch(descriptor).first)?.shelfLifeDays
    }

    private func categoryDefaultShelfLife(for category: String?) -> Int? {
        let trimmed = category?.trimmingCharacters(in: .whitespaces)
        let origin = (trimmed?.isEmpty ?? true) ? UNCATEGORIZED_ORIGIN : trimmed!
        let preference = categoryPreferences.first { $0.originName == origin }
            ?? categoryPreferences.first { $0.displayName.caseInsensitiveCompare(origin) == .orderedSame }
        return preference?.defaultShelfLifeDays
    }

    /// The Open Food Facts half of `lookupBarcode`, factored out so the batch queue can reuse it
    /// without also driving the single-scan `scannedProduct` state.
    private func fetchProductFromApi(_ barcode: String) async -> ProductInfo? {
        guard let url = URL(string: "https://world.openfoodfacts.org/api/v2/product/\(barcode).json") else {
            return nil
        }
        var request = URLRequest(url: url)
        request.setValue("FoodTracker-iOS/1.0", forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let status = json["status"] as? Int, status == 1,
              let product = json["product"] as? [String: Any] else {
            return nil
        }
        let rawCategory = (product["categories"] as? String)?
            .components(separatedBy: ",").first?
            .trimmingCharacters(in: .whitespaces)
        return ProductInfo(
            barcode: barcode,
            name: product["product_name"] as? String ?? "Product \(barcode)",
            brand: product["brands"] as? String,
            imageUrl: product["image_front_url"] as? String,
            category: rawCategory.map(stripLanguagePrefix),
            quantity: product["quantity"] as? String,
            nutrition: ProductNutrition.from(offProduct: product)
        )
    }

    /// True while a single-item scan is on screen - its lookup still running, or its product
    /// (or not-found notice) waiting on the user. The scanner ignores new barcodes until
    /// `clearScanResults` lets it go ("Scan Again", or a mode switch).
    public var isHoldingBarcodeResult: Bool {
        isLookingUpBarcode || scannedProduct != nil || productNotFoundBarcode != nil
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
                quantity: nil,
                nutrition: cached.nutrition
            )
            productNotFoundBarcode = nil
            allergenWarnings = cached.nutrition.matchedAllergens(SettingsStore.shared.settings.allergens)
            suggestedExpiry = resolveSuggestedExpiry(barcode: barcode, category: cached.category)
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

                    let nutrition = ProductNutrition.from(offProduct: product)
                    self.scannedProduct = ProductInfo(
                        barcode: barcode,
                        name: name,
                        brand: brand,
                        imageUrl: image,
                        category: category,
                        quantity: quantity,
                        nutrition: nutrition
                    )
                    self.productNotFoundBarcode = nil
                    self.allergenWarnings = nutrition.matchedAllergens(SettingsStore.shared.settings.allergens)
                    self.suggestedExpiry = self.resolveSuggestedExpiry(barcode: barcode, category: category)
                } else {
                    self.scannedProduct = nil
                    self.productNotFoundBarcode = barcode
                    // Unknown product, but the barcode may still have been added by hand before.
                    self.suggestedExpiry = self.resolveSuggestedExpiry(barcode: barcode, category: nil)
                }
            } catch {
                self.scannedProduct = nil
                self.productNotFoundBarcode = barcode
                self.suggestedExpiry = self.resolveSuggestedExpiry(barcode: barcode, category: nil)
            }
            self.isLookingUpBarcode = false
        }
    }

    /// The storage location to pre-select for a new item with `barcode`: where that product was
    /// put the last time it was added, or else the general rule in
    /// `AppSettings.storageLocationForNewItem` (last-used or default location) for a product
    /// the app has never seen stored. Mirrors `PantryStateHolder.storageLocationForNewItem`.
    public func storageLocationForNewItem(barcode: String?) -> String {
        let settings = SettingsStore.shared.settings
        if let trimmed = barcode?.trimmingCharacters(in: .whitespaces), !trimmed.isEmpty,
           let known = knownStorageLocation(for: trimmed) {
            return known
        }
        return settings.storageLocationForNewItem
    }

    /// Where `barcode`'s product was last put away, if that is still one of the configured
    /// locations. Learned on save (see `rememberProductStorageLocation`); for a product saved
    /// before that was tracked, the newest pantry item carrying the barcode stands in.
    private func knownStorageLocation(for barcode: String) -> String? {
        let valid = SettingsStore.shared.settings.storageLocations
        let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == barcode })
        if let remembered = (try? modelContext.fetch(descriptor).first)?.storageLocation,
           valid.contains(remembered) {
            return remembered
        }
        return items
            .filter { $0.barcode == barcode && valid.contains($0.storageLocation) }
            .max { $0.addedDate < $1.addedDate }?
            .storageLocation
    }

    /// Records where `barcode`'s product was just put away, so a re-scan pre-selects it. Only
    /// new items count, like `rememberShelfLife`: an opened jar moved to the fridge says nothing
    /// about where the next one goes. Must run after `saveLocalProduct`, for the same reason.
    private func rememberProductStorageLocation(barcode: String, location: String) {
        let trimmed = barcode.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let descriptor = FetchDescriptor<LocalProductRecord>(predicate: #Predicate { $0.barcode == trimmed })
        guard let existing = try? modelContext.fetch(descriptor).first else { return }
        existing.storageLocation = location
        try? modelContext.save()
    }

    /// Records `location` as the one to pre-select for the next new item (see
    /// `AppSettings.storageLocationForNewItem`). Only new items count: editing an old item's
    /// location says nothing about where the next scan is going. Skipped while the feature is
    /// off so the stored value doesn't silently change underneath a user who disabled it.
    private func rememberStorageLocation(_ location: String) {
        let store = SettingsStore.shared
        var settings = store.settings
        guard settings.rememberLastStorageLocation, settings.lastUsedStorageLocation != location else { return }
        settings.lastUsedStorageLocation = location
        store.save(settings)
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
            saveLocalProduct(
                barcode: barcode,
                name: item.name,
                brand: item.brand,
                imageUrl: item.imageUrl,
                category: item.category,
                // Carried from the scan that produced this item, when there was one.
                nutrition: scannedProduct?.barcode == barcode ? scannedProduct?.nutrition ?? ProductNutrition() : ProductNutrition()
            )
            if isNewItem {
                rememberShelfLife(barcode: barcode, item: item)
                rememberProductStorageLocation(barcode: barcode, location: item.storageLocation)
            }
        }

        if isNewItem {
            rememberStorageLocation(item.storageLocation)
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

    /// Adds every entry in `raw` (a QR payload or pasted text - see `ShoppingListShare.parse`)
    /// that isn't on the list already, and returns how many were added and how many were
    /// understood.
    ///
    /// Merging rather than replacing: an import is someone else's copy of the list, not
    /// necessarily a newer one, so silently dropping what's already here would lose whatever the
    /// receiving user added since. Deduping goes through the same
    /// `addShoppingListEntryIfAbsent` every other path uses, so an imported name that is already
    /// present - however it got there - is skipped rather than duplicated.
    ///
    /// Each imported name is also recorded for the autosuggest, so a list received from someone
    /// else teaches this device the same names typing them by hand would.
    @discardableResult
    public func importShoppingList(_ raw: String) -> (added: Int, found: Int) {
        let parsed = ShoppingListShare.parse(raw)
        guard !parsed.isEmpty else { return (0, 0) }

        var added = 0
        for entry in parsed {
            let before = shoppingList.count
            addShoppingListEntryIfAbsent(
                name: entry.productName,
                barcode: nil,
                category: entry.category,
                sectionId: entry.section.map { sectionIdForImportedName($0) }
            )
            guard shoppingList.count > before else { continue }
            added += 1
            recordProductName(entry.productName)
            // Quantity is applied after the fact: addShoppingListEntryIfAbsent is the shared
            // dedupe path and always creates a single unit, and teaching it about quantities
            // would change behaviour for every other caller.
            if entry.quantity > 1,
               let created = shoppingList.first(where: {
                   $0.productName.caseInsensitiveCompare(entry.productName) == .orderedSame
               }) {
                let createdId = created.id
                let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == createdId })
                if let record = try? modelContext.fetch(descriptor).first {
                    record.quantity = entry.quantity
                }
            }
        }
        if added > 0 {
            try? modelContext.save()
            loadShoppingList()
            WidgetCenter.shared.reloadAllTimelines()
        }
        return (added, parsed.count)
    }

    /// The local section called `name` (case-insensitively), created at the end if there is
    /// none - section ids are per-device, so an imported list is filed by section name.
    private func sectionIdForImportedName(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        if let match = shoppingSections.first(where: {
            !$0.isDefault && $0.name.trimmingCharacters(in: .whitespaces).caseInsensitiveCompare(trimmed) == .orderedSame
        }) {
            return match.id
        }
        let section = ShoppingSection(id: Self.newShoppingSectionId(), name: trimmed, sortOrder: nextShoppingSectionSortOrder())
        modelContext.insert(ShoppingSectionRecord(from: section))
        try? modelContext.save()
        loadShoppingSections()
        return section.id
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

    private func addShoppingListEntryIfAbsent(name: String, barcode: String?, category: String?, sectionId: String? = nil) {
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
        // Same for its section: back to wherever the user last moved it, as long as that section
        // still exists. An explicit `sectionId` (an import) wins.
        let existingSections = Set(shoppingSections.map(\.id))
        let resolvedSection = (sectionId ?? self.sectionId(forKnownProduct: name))
            .flatMap { $0 != ShoppingSection.defaultId && existingSections.contains($0) ? $0 : nil }

        modelContext.insert(ShoppingListEntryRecord(from: ShoppingListEntry(
            productName: name,
            barcode: barcode,
            category: category,
            sortOrder: resolvedSortOrder,
            sectionId: resolvedSection
        )))
        try? modelContext.save()
        loadShoppingList()
    }

    private func maxShoppingListSortOrder() -> Double {
        var descriptor = FetchDescriptor<ShoppingListEntryRecord>(sortBy: [SortDescriptor(\.sortOrder, order: .reverse)])
        descriptor.fetchLimit = 1
        return (try? modelContext.fetch(descriptor).first)?.sortOrder ?? 0
    }

    /// Called after a drag-and-drop completes. `sectionId` is the section the entry was dropped
    /// into (its own one for a plain reorder), and `beforeId`/`afterId` are the entries now
    /// immediately above/below it within that section (nil at a section boundary).
    ///
    /// Computes a fractional sort order between the neighbours and persists it, with the
    /// section, on the entry and on its product name, so both are remembered the next time the
    /// product re-enters the list (see `addShoppingListEntryIfAbsent`).
    public func moveShoppingListEntry(_ movedId: String, toSection sectionId: String, beforeId: String?, afterId: String?) {
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
        let newSectionId: String? = sectionId == ShoppingSection.defaultId ? nil : sectionId

        moved.sortOrder = newSortOrder
        moved.sectionId = newSectionId
        try? modelContext.save()
        recordSortOrder(name: moved.productName, sortOrder: newSortOrder)
        recordSectionId(name: moved.productName, sectionId: newSectionId)
        loadShoppingList()
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Moves `entryId` to the end of `sectionId` - the non-drag "Move to section" action.
    public func moveShoppingListEntry(_ entryId: String, toSection sectionId: String) {
        let ids = Set(shoppingSections.map(\.id))
        let last = shoppingList.last {
            $0.id != entryId && ShoppingSections.resolve($0.sectionId, in: ids) == sectionId
        }
        moveShoppingListEntry(entryId, toSection: sectionId, beforeId: last?.id, afterId: nil)
    }

    /// Adds a section called `name` at the end. Blank names are ignored.
    public func createShoppingSection(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        modelContext.insert(ShoppingSectionRecord(from: ShoppingSection(
            id: Self.newShoppingSectionId(), name: trimmed, sortOrder: nextShoppingSectionSortOrder()
        )))
        saveShoppingSections()
    }

    public func renameShoppingSection(_ id: String, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, id != ShoppingSection.defaultId, let record = shoppingSectionRecord(id) else { return }
        record.name = trimmed
        saveShoppingSections()
    }

    /// Deletes the section; its entries move to the default section. The default section itself
    /// can't be deleted.
    public func deleteShoppingSection(_ id: String) {
        guard id != ShoppingSection.defaultId, let record = shoppingSectionRecord(id) else { return }
        let entries = (try? modelContext.fetch(FetchDescriptor<ShoppingListEntryRecord>(
            predicate: #Predicate { $0.sectionId == id }
        ))) ?? []
        for entry in entries { entry.sectionId = nil }
        modelContext.delete(record)
        saveShoppingSections()
        loadShoppingList()
    }

    public func setShoppingSectionCollapsed(_ id: String, _ collapsed: Bool) {
        if let record = shoppingSectionRecord(id) {
            record.collapsed = collapsed
        } else if id == ShoppingSection.defaultId {
            var section = ShoppingSection.defaultSection
            section.collapsed = collapsed
            modelContext.insert(ShoppingSectionRecord(from: section))
        } else {
            return
        }
        saveShoppingSections()
    }

    /// Applies a new order of the user sections, given as their ids top to bottom. The default
    /// section is pinned first and ignored if present.
    public func reorderShoppingSections(_ orderedIds: [String]) {
        for (index, id) in orderedIds.filter({ $0 != ShoppingSection.defaultId }).enumerated() {
            shoppingSectionRecord(id)?.sortOrder = Double(index)
        }
        saveShoppingSections()
    }

    private func shoppingSectionRecord(_ id: String) -> ShoppingSectionRecord? {
        let descriptor = FetchDescriptor<ShoppingSectionRecord>(predicate: #Predicate { $0.id == id })
        return try? modelContext.fetch(descriptor).first
    }

    private func saveShoppingSections() {
        try? modelContext.save()
        loadShoppingSections()
        WidgetCenter.shared.reloadAllTimelines()
    }

    private func nextShoppingSectionSortOrder() -> Double {
        let orders = shoppingSections.filter { !$0.isDefault }.map(\.sortOrder)
        return max(orders.max() ?? -1, -1) + 1
    }

    private static func newShoppingSectionId() -> String {
        "section_\(UUID().uuidString)"
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

    /// Sets the shelf life, in days, pre-filled on a new item in this category when its barcode
    /// has no remembered shelf life of its own. Nil clears the default. Like
    /// `setCategoryEnabled`, every origin sharing this category's display name is updated as a
    /// unit, since merged categories are a single row to the user.
    public func setCategoryDefaultShelfLife(_ originName: String, days: Int?) {
        let descriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.originName == originName })
        guard let target = try? modelContext.fetch(descriptor).first else { return }

        let sanitized = days.map { min(max($0, 0), ShelfLife.maxLearnableDays) }
        let displayName = target.displayName
        let allDescriptor = FetchDescriptor<CategoryPreferenceRecord>(predicate: #Predicate { $0.displayName == displayName })
        if let all = try? modelContext.fetch(allDescriptor) {
            for record in all {
                record.defaultShelfLifeDays = sanitized
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

    /// Called after a drag-and-drop reorder of categories in Settings completes.
    /// `orderedOrigins` is the origin name of one row per visible category (see
    /// `CategorySettingsView.groupedPreferences`), in the order the user now sees them; the whole
    /// list is renumbered to match.
    ///
    /// Deliberately *not* `moveShoppingListEntry`'s scheme of giving the moved row a fractional
    /// order between its two neighbours. The "Other" bucket is registered at
    /// `.greatestFiniteMagnitude` so it always sorts last (see `registerSentinelCategoryIfNeeded`),
    /// and `greatestFiniteMagnitude + 1` is still `greatestFiniteMagnitude` — so dropping a row
    /// below "Other" persisted the sort order it already had. Nothing changed on disk, and the row
    /// snapped back to its old place the next time the list was read. Renumbering has no such
    /// degenerate case, and it also repairs any ties already stored.
    public func reorderCategoryPreferences(_ orderedOrigins: [String]) {
        guard let all = try? modelContext.fetch(FetchDescriptor<CategoryPreferenceRecord>()) else { return }

        // Rows are grouped by display name, so every origin merged onto one row moves as a unit.
        var orderByDisplayName: [String: Double] = [:]
        for (index, origin) in orderedOrigins.enumerated() {
            guard let displayName = all.first(where: { $0.originName == origin })?.displayName else { continue }
            orderByDisplayName[displayName] = Double(index)
        }

        var changed = false
        for record in all {
            guard let newSortOrder = orderByDisplayName[record.displayName],
                  record.sortOrder != newSortOrder else { continue }
            record.sortOrder = newSortOrder
            changed = true
        }
        guard changed else { return }

        try? modelContext.save()
        loadCategoryPreferences()
    }

    /// Drops the result of the last single-item scan, without touching the two things that
    /// outlive one scan: the batch queue, and the shopping-list entry this scan is filling in.
    ///
    /// This is what switching scanner mode calls. A product card, an OCR'd date or a photo
    /// belongs to the mode that produced it, and leaving it published while the camera is doing
    /// something else is how a stale result ends up on screen under a mode that cannot have
    /// produced it. `clearScanState` is the stronger version, for closing the scanner entirely.
    public func clearScanResults() {
        scannedProduct = nil
        productNotFoundBarcode = nil
        detectedDate = nil
        suggestedExpiry = nil
        photoLabelSuggestions = []
        isLabelingPhoto = false
        duplicateOf = nil
        allergenWarnings = []
        isLookingUpBarcode = false
    }

    /// Drops the last scan's result *and* the shopping-list entry it was filling in, for when
    /// that scan is finished with - saving the item it produced, or closing the scanner.
    ///
    /// Deliberately not the batch queue: `saveItem` calls this, and `commitBatchQueue` saves
    /// each row through `saveItem`, so emptying the queue here would have it clear itself out
    /// from under its own loop. The queue is discarded by `clearBatchQueue`, which the sheet
    /// calls when the user confirms closing with a queue uncommitted.
    public func clearScanState() {
        clearScanResults()
        scanningShoppingListEntryId = nil
    }

    /// Builds a full backup of everything stored locally.
    ///
    /// Reads the store directly rather than the published arrays: those hold only what the
    /// screens need (no product cache, no known-names list), and a backup that quietly omitted
    /// them would restore a pantry that had forgotten every shelf life and autosuggest name the
    /// user had built up.
    public func exportBackup() -> BackupDocument {
        let products = ((try? modelContext.fetch(FetchDescriptor<LocalProductRecord>())) ?? []).map {
            CachedProduct(
                barcode: $0.barcode,
                name: $0.name,
                brand: $0.brand,
                imageUrl: $0.imageUrl,
                category: $0.category,
                shelfLifeDays: $0.shelfLifeDays,
                storageLocation: $0.storageLocation,
                nutrition: $0.nutrition
            )
        }
        let names = ((try? modelContext.fetch(FetchDescriptor<ProductNameRecord>())) ?? []).map { $0.displayName }
        return BackupDocument(
            exportedAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1000),
            items: items,
            shoppingList: shoppingList,
            shoppingSections: shoppingSections.filter { !$0.isDefault },
            categoryPreferences: categoryPreferences,
            products: products,
            knownProductNames: names,
            settings: SettingsStore.shared.settings
        )
    }

    /// Restores `raw` according to `mode`.
    ///
    /// `.merge` adds only what is missing, matching pantry items and shopping entries by id so
    /// re-importing the same file twice is a no-op rather than a way to double a pantry.
    /// `.replace` clears local data first and also restores the backup's settings; merge
    /// deliberately leaves settings alone, since those are the settings of the device the user is
    /// holding rather than of the file they are importing.
    public func restoreBackup(_ raw: String, mode: RestoreMode) -> (BackupReadResult, RestoreSummary) {
        let result = BackupSerializer.decode(raw)
        guard case .success(let document) = result else {
            return (result, RestoreSummary())
        }

        if mode == .replace {
            try? modelContext.delete(model: PantryItemRecord.self)
            try? modelContext.delete(model: ShoppingListEntryRecord.self)
            try? modelContext.delete(model: ShoppingSectionRecord.self)
            try? modelContext.delete(model: LocalProductRecord.self)
            try? modelContext.delete(model: CategoryPreferenceRecord.self)
            try? modelContext.delete(model: ProductNameRecord.self)
            try? modelContext.save()
        }

        // Category preferences go back first: items and shopping entries reference categories by
        // origin, and restoring those first would auto-register each origin with a default
        // display name that the backup's own (possibly renamed) version would then overwrite.
        let existingPreferences = (try? modelContext.fetch(FetchDescriptor<CategoryPreferenceRecord>())) ?? []
        var preferenceByOrigin = Dictionary(
            existingPreferences.map { ($0.originName, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        for preference in document.categoryPreferences {
            if let existing = preferenceByOrigin[preference.originName] {
                existing.update(from: preference)
            } else {
                let record = CategoryPreferenceRecord(from: preference)
                modelContext.insert(record)
                preferenceByOrigin[preference.originName] = record
            }
        }

        let existingItemIds = Set(((try? modelContext.fetch(FetchDescriptor<PantryItemRecord>())) ?? []).map { $0.id })
        var itemsRestored = 0
        for item in document.items {
            if mode == .merge && existingItemIds.contains(item.id) { continue }
            modelContext.insert(PantryItemRecord(from: item))
            itemsRestored += 1
        }

        // Sections before entries, so no restored entry points at a missing section. On a merge
        // a section that already exists keeps this device's name, order and collapse state.
        let existingSectionIds = Set(((try? modelContext.fetch(FetchDescriptor<ShoppingSectionRecord>())) ?? []).map { $0.id })
        for section in document.shoppingSections where !section.isDefault && !existingSectionIds.contains(section.id) {
            modelContext.insert(ShoppingSectionRecord(from: section))
        }

        let existingEntryIds = Set(((try? modelContext.fetch(FetchDescriptor<ShoppingListEntryRecord>())) ?? []).map { $0.id })
        var entriesRestored = 0
        for entry in document.shoppingList {
            if mode == .merge && existingEntryIds.contains(entry.id) { continue }
            modelContext.insert(ShoppingListEntryRecord(from: entry))
            entriesRestored += 1
        }

        let existingProducts = (try? modelContext.fetch(FetchDescriptor<LocalProductRecord>())) ?? []
        var productByBarcode = Dictionary(
            existingProducts.map { ($0.barcode, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        for product in document.products {
            let barcode = product.barcode.trimmingCharacters(in: .whitespaces)
            guard !barcode.isEmpty else { continue }
            if let existing = productByBarcode[barcode] {
                existing.name = product.name
                existing.brand = product.brand
                existing.imageUrl = product.imageUrl
                existing.category = product.category
                existing.shelfLifeDays = product.shelfLifeDays
                existing.storageLocation = product.storageLocation
                existing.apply(product.nutrition)
            } else {
                let record = LocalProductRecord(
                    barcode: barcode,
                    name: product.name,
                    brand: product.brand,
                    imageUrl: product.imageUrl,
                    category: product.category,
                    shelfLifeDays: product.shelfLifeDays,
                    storageLocation: product.storageLocation,
                    nutrition: product.nutrition
                )
                modelContext.insert(record)
                productByBarcode[barcode] = record
            }
        }

        try? modelContext.save()

        for name in document.knownProductNames {
            recordProductName(name)
        }

        if mode == .replace {
            SettingsStore.shared.save(document.settings)
        }

        loadCategoryPreferences()
        loadItems()
        loadShoppingSections()
        loadShoppingList()
        loadKnownProductNames()
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
        WidgetCenter.shared.reloadAllTimelines()

        return (result, RestoreSummary(
            itemsRestored: itemsRestored,
            shoppingEntriesRestored: entriesRestored,
            productsRestored: document.products.count,
            categoriesRestored: document.categoryPreferences.count,
            settingsRestored: mode == .replace
        ))
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
