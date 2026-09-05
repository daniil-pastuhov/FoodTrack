import Foundation
import SwiftData
import SwiftUI

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

    private let modelContext: ModelContext

    /// Shared on-disk container for pantry data. `NativePantryViewModel()` (used at every
    /// call site) resolves to this by default; a test can pass an in-memory container instead.
    public nonisolated static let sharedModelContainer: ModelContainer = {
        do {
            return try ModelContainer(for: PantryItemRecord.self, ShoppingListEntryRecord.self)
        } catch {
            fatalError("Failed to create SwiftData ModelContainer for PantryItemRecord: \(error)")
        }
    }()

    public init(modelContainer: ModelContainer = NativePantryViewModel.sharedModelContainer) {
        modelContext = ModelContext(modelContainer)
        loadItems()
        loadShoppingList()
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
    }

    private var expiringSoonDays: Int { SettingsStore.shared.settings.expiringSoonDays }

    private func loadItems() {
        let descriptor = FetchDescriptor<PantryItemRecord>(sortBy: [SortDescriptor(\.expiryDate)])
        items = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.asPantryItem }
    }

    private func loadShoppingList() {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(sortBy: [SortDescriptor(\.addedDate, order: .reverse)])
        shoppingList = ((try? modelContext.fetch(descriptor)) ?? []).map { $0.asEntry }
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

        if isNewItem, let barcode = item.barcode {
            removeShoppingListEntry(matchingBarcode: barcode)
        }

        IOSNotificationManager.shared.scheduleNotification(for: item)
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)
        clearScanState()
    }

    public func deleteItem(_ id: String) {
        let deletedItem = items.first(where: { $0.id == id })

        let descriptor = FetchDescriptor<PantryItemRecord>(predicate: #Predicate { $0.id == id })
        if let existing = try? modelContext.fetch(descriptor).first {
            modelContext.delete(existing)
            try? modelContext.save()
        }
        items.removeAll { $0.id == id }
        IOSNotificationManager.shared.cancelNotification(for: id)
        IOSNotificationManager.shared.scheduleDigestNotification(items: items)

        if let deletedItem, deletedItem.essential {
            addShoppingListEntryIfAbsent(name: deletedItem.name, barcode: deletedItem.barcode, category: deletedItem.category)
        }
    }

    /// Decrements one unit of an item; once the last unit is used it is removed the same way
    /// `deleteItem` removes a whole item (essential items get re-added to the shopping list).
    public func decrementQuantity(_ id: String) {
        guard let item = items.first(where: { $0.id == id }) else { return }
        if item.quantity <= 1 {
            deleteItem(id)
        } else {
            var updated = item
            updated.quantity -= 1
            saveItem(updated)
        }
    }

    public func addManualShoppingListEntry(_ productName: String) {
        let trimmed = productName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        addShoppingListEntryIfAbsent(name: trimmed, barcode: nil, category: nil)
    }

    public func deleteShoppingListEntry(_ id: String) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
        if let existing = try? modelContext.fetch(descriptor).first {
            modelContext.delete(existing)
            try? modelContext.save()
        }
        loadShoppingList()
    }

    public func incrementShoppingListQuantity(_ id: String) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
        guard let existing = try? modelContext.fetch(descriptor).first else { return }
        var updated = existing.asEntry
        updated.quantity += 1
        existing.update(from: updated)
        try? modelContext.save()
        loadShoppingList()
    }

    public func decrementShoppingListQuantity(_ id: String) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.id == id })
        guard let existing = try? modelContext.fetch(descriptor).first, existing.quantity > 1 else { return }
        var updated = existing.asEntry
        updated.quantity -= 1
        existing.update(from: updated)
        try? modelContext.save()
        loadShoppingList()
    }

    private func removeShoppingListEntry(matchingBarcode barcode: String) {
        let descriptor = FetchDescriptor<ShoppingListEntryRecord>(predicate: #Predicate { $0.barcode == barcode })
        if let existing = try? modelContext.fetch(descriptor).first {
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

        modelContext.insert(ShoppingListEntryRecord(from: ShoppingListEntry(productName: name, barcode: barcode, category: category)))
        try? modelContext.save()
        loadShoppingList()
    }

    public func clearScanState() {
        scannedProduct = nil
        productNotFoundBarcode = nil
        detectedDate = nil
        isLookingUpBarcode = false
    }

    public func clearAllData() {
        try? modelContext.delete(model: PantryItemRecord.self)
        try? modelContext.save()
        items = []
        IOSNotificationManager.shared.cancelAll()
    }

    /// Builds a device-language prompt asking an AI assistant (ChatGPT/Gemini/Claude, via the
    /// share sheet) for recipe ideas using the given items, ordered soonest-to-expire first.
    public static func recipePrompt(for items: [PantryItem]) -> String {
        let lines = items
            .sorted { $0.daysRemaining < $1.daysRemaining }
            .map { "- " + L10n.recipePromptItem(name: $0.name, expiry: $0.daysRemainingFormatted) }
            .joined(separator: "\n")
        return L10n.recipePromptIntro + "\n\n" + lines
    }
}
