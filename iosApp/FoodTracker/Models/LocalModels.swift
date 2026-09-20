import Foundation
import SwiftUI

public enum ExpiryStatus: String, Codable {
    case expired = "Expired"
    case expiringToday = "Expires Today"
    case expiringSoon = "Expiring Soon"
    case fresh = "Fresh"
}

public struct PantryItem: Identifiable, Codable, Equatable {
    public let id: String
    public var name: String
    public var brand: String?
    public var barcode: String?
    public var imageUrl: String?
    public var expiryDate: Date
    public var addedDate: Date
    public var storageLocation: String
    public var category: String?
    public var quantity: Int
    public var notes: String?
    public var essential: Bool

    public init(
        id: String = UUID().uuidString,
        name: String,
        brand: String? = nil,
        barcode: String? = nil,
        imageUrl: String? = nil,
        expiryDate: Date,
        addedDate: Date = Date(),
        storageLocation: String = "Pantry",
        category: String? = nil,
        quantity: Int = 1,
        notes: String? = nil,
        essential: Bool = true
    ) {
        self.id = id
        self.name = name
        self.brand = brand
        self.barcode = barcode
        self.imageUrl = imageUrl
        self.expiryDate = expiryDate
        self.addedDate = addedDate
        self.storageLocation = storageLocation
        self.category = category
        self.quantity = quantity
        self.notes = notes
        self.essential = essential
    }

    /// Key used to match this item's product against a shopping list entry: barcode when
    /// known, otherwise the trimmed/lowercased name (mirrors Android's `PantryItem.shoppingMatchKey()`).
    public func shoppingMatchKey() -> String {
        barcode ?? name.trimmingCharacters(in: .whitespaces).lowercased()
    }

    public var daysRemaining: Int {
        let calendar = Calendar.current
        let startOfToday = calendar.startOfDay(for: Date())
        let startOfExpiry = calendar.startOfDay(for: expiryDate)
        let components = calendar.dateComponents([.day], from: startOfToday, to: startOfExpiry)
        return components.day ?? 0
    }

    public func expiryStatus(expiringSoonDays: Int = 3) -> ExpiryStatus {
        let days = daysRemaining
        if days < 0 { return .expired }
        if days == 0 { return .expiringToday }
        if days <= expiringSoonDays { return .expiringSoon }
        return .fresh
    }

    public var daysRemainingFormatted: String {
        L10n.daysRemaining(daysRemaining)
    }
}

/// Swift port of `shared`'s `DateUtils.MAX_PLAUSIBLE_EXPIRY_YEARS` / `isPlausibleExpiryDate`:
/// how far from the current year an expiry date can be before it is almost certainly a
/// mis-read (e.g. "27" taken as year 27 AD) or a lot number. The OCR scanner drops candidates
/// outside this window; the add/edit form warns about them.
public enum ExpiryDatePlausibility {
    public static let maxYearsFromToday = 10

    public static func isPlausible(_ date: Date, today: Date = Date()) -> Bool {
        let calendar = Calendar(identifier: .gregorian)
        let currentYear = calendar.component(.year, from: today)
        let year = calendar.component(.year, from: date)
        return ((currentYear - maxYearsFromToday)...(currentYear + maxYearsFromToday)).contains(year)
    }
}

public struct ProductInfo: Codable {
    public let barcode: String
    public let name: String
    public let brand: String?
    public let imageUrl: String?
    public let category: String?
    public let quantity: String?
}

public struct NotificationSettings: Codable, Equatable {
    public var enabled: Bool
    public var leadDays: Int
    public var hour: Int
    public var minute: Int

    public init(enabled: Bool = true, leadDays: Int = 1, hour: Int = 9, minute: Int = 0) {
        self.enabled = enabled
        self.leadDays = leadDays
        self.hour = hour
        self.minute = minute
    }
}

public enum AppTheme: String, Codable, CaseIterable, Identifiable {
    case system
    case light
    case dark

    public var id: String { rawValue }

    public var localizedTitle: String {
        switch self {
        case .system: return L10n.settingsThemeSystem
        case .light: return L10n.settingsThemeLight
        case .dark: return L10n.settingsThemeDark
        }
    }

    public var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}

public enum SortOption: String, Codable, CaseIterable, Identifiable {
    case expiryDate
    case name
    case dateAdded
    case storageLocation

    public var id: String { rawValue }

    public var localizedTitle: String {
        switch self {
        case .expiryDate: return L10n.sortOptionExpiryDate
        case .name: return L10n.sortOptionName
        case .dateAdded: return L10n.sortOptionDateAdded
        case .storageLocation: return L10n.sortOptionStorageLocation
        }
    }
}

public struct AppSettings: Codable, Equatable {
    public var notifications: NotificationSettings
    public var expiringSoonDays: Int
    public var storageLocations: [String]
    public var defaultStorageLocation: String
    /// When on, a new item starts out in the storage location the previous new item was saved
    /// to (see `lastUsedStorageLocation`) rather than in `defaultStorageLocation`, so a batch
    /// of scans that all go into the freezer only needs the location picked once.
    public var rememberLastStorageLocation: Bool
    /// Storage location of the most recently added item. Only consulted while
    /// `rememberLastStorageLocation` is on; kept up to date by the view model on every new
    /// item save, and cleared when its location is deleted in Settings.
    public var lastUsedStorageLocation: String?
    public var theme: AppTheme
    public var sortOption: SortOption

    public init(
        notifications: NotificationSettings = NotificationSettings(),
        expiringSoonDays: Int = 3,
        storageLocations: [String] = ["Fridge", "Freezer", "Pantry"],
        defaultStorageLocation: String = "Pantry",
        rememberLastStorageLocation: Bool = true,
        lastUsedStorageLocation: String? = nil,
        theme: AppTheme = .system,
        sortOption: SortOption = .expiryDate
    ) {
        self.notifications = notifications
        self.expiringSoonDays = expiringSoonDays
        self.storageLocations = storageLocations
        self.defaultStorageLocation = defaultStorageLocation
        self.rememberLastStorageLocation = rememberLastStorageLocation
        self.lastUsedStorageLocation = lastUsedStorageLocation
        self.theme = theme
        self.sortOption = sortOption
    }

    /// The storage location a freshly created item should be pre-filled with. Falls back to
    /// `defaultStorageLocation` when the remembered one is off, unset, or no longer one of
    /// `storageLocations`.
    public var storageLocationForNewItem: String {
        if rememberLastStorageLocation,
           let last = lastUsedStorageLocation,
           storageLocations.contains(last) {
            return last
        }
        return defaultStorageLocation
    }

    // Custom decoding so settings saved before `sortOption` existed still decode instead of
    // silently falling back to all-defaults (JSONDecoder fails a whole struct on a missing
    // non-optional key with the synthesized initializer).
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        notifications = try container.decode(NotificationSettings.self, forKey: .notifications)
        expiringSoonDays = try container.decode(Int.self, forKey: .expiringSoonDays)
        storageLocations = try container.decode([String].self, forKey: .storageLocations)
        defaultStorageLocation = try container.decode(String.self, forKey: .defaultStorageLocation)
        rememberLastStorageLocation = try container.decodeIfPresent(Bool.self, forKey: .rememberLastStorageLocation) ?? true
        lastUsedStorageLocation = try container.decodeIfPresent(String.self, forKey: .lastUsedStorageLocation)
        theme = try container.decode(AppTheme.self, forKey: .theme)
        sortOption = try container.decodeIfPresent(SortOption.self, forKey: .sortOption) ?? .expiryDate
    }
}
