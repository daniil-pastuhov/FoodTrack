import Foundation
import SwiftUI

public enum ExpiryStatus: String, Codable {
    case expired = "Expired"
    case expiringToday = "Expires Today"
    case expiringSoon = "Expiring Soon"
    case fresh = "Fresh"
}

public struct PantryItem: Identifiable, Codable {
    public let id: String
    public var name: String
    public var brand: String?
    public var barcode: String?
    public var imageUrl: String?
    public var expiryDate: Date
    public var addedDate: Date
    public var storageLocation: String
    public var category: String?
    public var notes: String?

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
        notes: String? = nil
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
        self.notes = notes
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

public struct AppSettings: Codable, Equatable {
    public var notifications: NotificationSettings
    public var expiringSoonDays: Int
    public var storageLocations: [String]
    public var defaultStorageLocation: String
    public var theme: AppTheme

    public init(
        notifications: NotificationSettings = NotificationSettings(),
        expiringSoonDays: Int = 3,
        storageLocations: [String] = ["Fridge", "Freezer", "Pantry"],
        defaultStorageLocation: String = "Pantry",
        theme: AppTheme = .system
    ) {
        self.notifications = notifications
        self.expiringSoonDays = expiringSoonDays
        self.storageLocations = storageLocations
        self.defaultStorageLocation = defaultStorageLocation
        self.theme = theme
    }
}
