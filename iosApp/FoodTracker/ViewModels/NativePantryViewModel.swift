import Foundation
import SwiftUI

public enum NativeStatusFilter: String, CaseIterable, Identifiable {
    case active = "Active"
    case expiringSoon = "Expiring Soon"
    case expired = "Expired"
    case fresh = "Fresh"

    public var id: String { rawValue }

    /// The filter cases shown as tabs in the filter sheet. `fresh` is intentionally excluded —
    /// it's only reachable by tapping the Fresh metric card, mirroring the Android dashboard.
    public static var tabCases: [NativeStatusFilter] { [.active, .expiringSoon, .expired] }

    public var localizedTitle: String {
        switch self {
        case .active: return L10n.statusFilterActive
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
    @Published public var selectedFilter: NativeStatusFilter = .active
    @Published public var isLookingUpBarcode: Bool = false
    @Published public var scannedProduct: ProductInfo? = nil
    @Published public var detectedDate: Date? = nil

    public init() {}

    private var expiringSoonDays: Int { SettingsStore.shared.settings.expiringSoonDays }

    public var filteredItems: [PantryItem] {
        items.filter { item in
            let matchesLocation = selectedLocation == nil || item.storageLocation == selectedLocation

            let matchesStatus: Bool
            switch selectedFilter {
            case .active:
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

            return matchesLocation && matchesStatus && matchesQuery
        }.sorted { $0.expiryDate < $1.expiryDate }
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
            selectedFilter != .active ||
            !searchQuery.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// Toggles a metric-card filter on/off: selecting an already-active filter clears it
    /// back to `.active` (show everything), otherwise switches to the tapped filter.
    public func toggleMetricFilter(_ filter: NativeStatusFilter) {
        selectedFilter = selectedFilter == filter ? .active : filter
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
                } else {
                    self.scannedProduct = ProductInfo(
                        barcode: barcode,
                        name: "Product \(barcode)",
                        brand: nil,
                        imageUrl: nil,
                        category: nil,
                        quantity: nil
                    )
                }
            } catch {
                self.scannedProduct = ProductInfo(
                    barcode: barcode,
                    name: "Product \(barcode)",
                    brand: nil,
                    imageUrl: nil,
                    category: nil,
                    quantity: nil
                )
            }
            self.isLookingUpBarcode = false
        }
    }

    public func saveItem(_ item: PantryItem) {
        if let idx = items.firstIndex(where: { $0.id == item.id }) {
            items[idx] = item
        } else {
            items.insert(item, at: 0)
        }
        IOSNotificationManager.shared.scheduleNotification(for: item)
        clearScanState()
    }

    public func deleteItem(_ id: String) {
        items.removeAll { $0.id == id }
        IOSNotificationManager.shared.cancelNotification(for: id)
    }

    public func clearScanState() {
        scannedProduct = nil
        detectedDate = nil
        isLookingUpBarcode = false
    }

    public func clearAllData() {
        items = []
        IOSNotificationManager.shared.cancelAll()
    }
}
