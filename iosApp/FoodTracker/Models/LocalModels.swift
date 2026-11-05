import Foundation
import SwiftUI

public enum ExpiryStatus: String, Codable {
    case expired = "Expired"
    case expiringToday = "Expires Today"
    case expiringSoon = "Expiring Soon"
    case fresh = "Fresh"
}

/// The most of one item the app keeps count of, the same on both platforms.
public let maxItemQuantity = 99

public struct PantryItem: Identifiable, Codable, Equatable, Sendable {
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
    /// Whether this item gets its own expiry reminder and appears in the summary. Mirrors
    /// Android's `PantryItem.notificationsEnabled`.
    public var notificationsEnabled: Bool

    public init(
        id: String = UUID().uuidString,
        name: String,
        brand: String? = nil,
        barcode: String? = nil,
        imageUrl: String? = nil,
        expiryDate: Date,
        addedDate: Date = Date(),
        storageLocation: String = DefaultStorageLocations.pantry,
        category: String? = nil,
        quantity: Int = 1,
        notes: String? = nil,
        essential: Bool = true,
        notificationsEnabled: Bool = true
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
        self.notificationsEnabled = notificationsEnabled
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

    /// The words a status chip shows. See `L10n.expiryStatusLabel`.
    public func statusLabel(expiringSoonDays: Int) -> String {
        L10n.expiryStatusLabel(
            status: expiryStatus(expiringSoonDays: expiringSoonDays),
            daysRemaining: daysRemaining,
            expiryDate: expiryDate
        )
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

public struct ProductInfo: Codable, Equatable {
    public let barcode: String
    public let name: String
    public let brand: String?
    public let imageUrl: String?
    public let category: String?
    public let quantity: String?
    public var nutrition: ProductNutrition

    public init(
        barcode: String,
        name: String,
        brand: String? = nil,
        imageUrl: String? = nil,
        category: String? = nil,
        quantity: String? = nil,
        nutrition: ProductNutrition = ProductNutrition()
    ) {
        self.barcode = barcode
        self.name = name
        self.brand = brand
        self.imageUrl = imageUrl
        self.category = category
        self.quantity = quantity
        self.nutrition = nutrition
    }
}

/// How often the summary notification fires. Mirrors Android's `DigestFrequency`.
public enum DigestFrequency: String, Codable, CaseIterable, Identifiable {
    case daily = "DAILY"
    case weekly = "WEEKLY"
    public var id: String { rawValue }
}

public struct NotificationSettings: Codable, Equatable {
    /// Master switch: off silences every expiry notification, individual and summary alike.
    public var enabled: Bool
    public var leadDays: Int
    public var hour: Int
    public var minute: Int
    /// One reminder per product, `leadDays` before it expires.
    public var individualEnabled: Bool
    /// One grouped notification naming what expires in the coming week. Off by default: with
    /// individual reminders on too, every item would be announced twice.
    public var digestEnabled: Bool
    public var digestFrequency: DigestFrequency
    /// ISO day of week (1 = Monday ... 7 = Sunday) the summary fires on when weekly, matching
    /// Android's `digestWeekday`. Note this is *not* `Calendar`'s numbering (1 = Sunday).
    public var digestWeekday: Int
    /// Days between summaries when `digestFrequency` is `.daily` (1 = every day). Weekly stays
    /// its own frequency, so settings saved before this existed keep their meaning. Read
    /// through `digestEveryDays`. Mirrors Android's `digestIntervalDays`.
    public var digestIntervalDays: Int

    public init(
        enabled: Bool = true,
        leadDays: Int = 1,
        hour: Int = 9,
        minute: Int = 0,
        individualEnabled: Bool = true,
        digestEnabled: Bool = false,
        digestFrequency: DigestFrequency = .daily,
        digestWeekday: Int = 1,
        digestIntervalDays: Int = 1
    ) {
        self.enabled = enabled
        self.leadDays = leadDays
        self.hour = hour
        self.minute = minute
        self.individualEnabled = individualEnabled
        self.digestEnabled = digestEnabled
        self.digestFrequency = digestFrequency
        self.digestWeekday = digestWeekday
        self.digestIntervalDays = digestIntervalDays
    }

    // decodeIfPresent for everything added after the first release: settings saved before
    // then have no such keys, and the synthesized decoder would fail the whole struct - and
    // with it `AppSettings`, resetting every other setting too.
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try container.decode(Bool.self, forKey: .enabled)
        leadDays = try container.decode(Int.self, forKey: .leadDays)
        hour = try container.decode(Int.self, forKey: .hour)
        minute = try container.decode(Int.self, forKey: .minute)
        individualEnabled = try container.decodeIfPresent(Bool.self, forKey: .individualEnabled) ?? true
        digestEnabled = try container.decodeIfPresent(Bool.self, forKey: .digestEnabled) ?? false
        digestFrequency = try container.decodeIfPresent(DigestFrequency.self, forKey: .digestFrequency) ?? .daily
        digestWeekday = try container.decodeIfPresent(Int.self, forKey: .digestWeekday) ?? 1
        digestIntervalDays = try container.decodeIfPresent(Int.self, forKey: .digestIntervalDays) ?? 1
    }
}

/// How expiry reminders are delivered: the single choice the settings screen offers instead of
/// two independent toggles. UI-only - persisted as `individualEnabled` / `digestEnabled`, so
/// stored settings don't change shape. Mirrors `ReminderStyle` in shared `Settings.kt`.
public enum ReminderStyle: CaseIterable, Hashable {
    case eachItem
    case summary
    case both
}

extension NotificationSettings {
    /// The current style, or nil when both delivery types are off - a state saved by older
    /// versions, where the master switch was on but nothing would ever fire.
    public var reminderStyle: ReminderStyle? {
        switch (individualEnabled, digestEnabled) {
        case (true, true): return .both
        case (true, false): return .eachItem
        case (false, true): return .summary
        case (false, false): return nil
        }
    }

    public func withReminderStyle(_ style: ReminderStyle) -> NotificationSettings {
        var copy = self
        copy.individualEnabled = style != .summary
        copy.digestEnabled = style != .eachItem
        return copy
    }

    /// Days between summaries, 1...7: 7 for weekly, otherwise `digestIntervalDays`.
    public var digestEveryDays: Int {
        digestFrequency == .weekly ? 7 : min(max(digestIntervalDays, 1), 7)
    }

    /// Sets how often the summary fires. 7 means weekly, on `digestWeekday`.
    public func withDigestEveryDays(_ days: Int) -> NotificationSettings {
        let clamped = min(max(days, 1), 7)
        var copy = self
        copy.digestFrequency = clamped == 7 ? .weekly : .daily
        if clamped != 7 { copy.digestIntervalDays = clamped }
        return copy
    }

    /// Whether the summary fires on the local calendar day `epochDay` (days since 1970-01-01),
    /// whose ISO weekday (1 = Monday ... 7 = Sunday) is `isoWeekday`. Every N days is anchored
    /// to the epoch, not to "now": the digest is rescheduled on every pantry change, and an
    /// interval counted from now would keep moving out and never fire. Mirrors `isDigestDay`
    /// in shared `Settings.kt`, so both apps pick the same days.
    public func isDigestDay(epochDay: Int, isoWeekday: Int) -> Bool {
        switch digestEveryDays {
        case 1: return true
        case 7: return isoWeekday == min(max(digestWeekday, 1), 7)
        case let every:
            let remainder = epochDay % every
            return (remainder < 0 ? remainder + every : remainder) == 0
        }
    }

    /// Flips the master switch. Turning it on with no delivery type selected brings back
    /// per-item reminders, so "on" always means something will actually fire.
    public func withEnabled(_ enabled: Bool) -> NotificationSettings {
        var copy = self
        copy.enabled = enabled
        copy.individualEnabled = individualEnabled || (enabled && !digestEnabled)
        return copy
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

/// A shop the user wants to be reminded of their shopping list near.
///
/// Stored in `AppSettings` rather than SwiftData: there are only ever one or two of these, they
/// are settings rather than content, and keeping them here means the feature needs no schema
/// migration. Mirrors `shared`'s `com.foodtracker.model.StorePlace`.
public struct StorePlace: Codable, Equatable, Identifiable {
    public static let defaultRadiusMeters = 150.0

    public var id: String
    public var label: String
    public var latitude: Double
    public var longitude: Double
    /// How close counts as "at the shop". Smaller than the default and the reminder is
    /// unreliable — a phone's position indoors and among buildings is routinely off by that
    /// much. Much larger and it fires while merely driving past.
    public var radiusMeters: Double

    public init(
        id: String,
        label: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double = StorePlace.defaultRadiusMeters
    ) {
        self.id = id
        self.label = label
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
    }
}

/// The storage locations a fresh install starts with.
///
/// These English names are stored keys, not display text: they are what lands in
/// `AppSettings.storageLocations` and on every `PantryItem.storageLocation`, so they stay the
/// same whatever language the device is in. Views show them through
/// `L10n.storageLocationName`, which translates these three and leaves anything the user typed
/// as-is. Mirrors `DefaultStorageLocations` in the shared Kotlin module.
public enum DefaultStorageLocations {
    public static let fridge = "Fridge"
    public static let freezer = "Freezer"
    public static let pantry = "Pantry"

    public static let all = [fridge, freezer, pantry]
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
    /// Allergens the user wants to be warned about when a scanned product lists them. Empty by
    /// default: this is a safety feature for the people who need it, not a nag for everyone.
    public var allergens: Set<String>
    /// Shops to remind the user of their shopping list near. See `StorePlace`.
    public var stores: [StorePlace]
    /// Whether those reminders are armed. Off by default, and deliberately separate from
    /// `stores` being non-empty: this is the switch that gates asking for background location,
    /// the most intrusive permission the app requests.
    public var storeRemindersEnabled: Bool
    /// Whether first-run onboarding is finished. False on a fresh install. Someone upgrading
    /// with items already in the pantry never sees it, and neither does anyone who restores a
    /// backup with Replace: see `NativePantryViewModel.completeOnboardingIfPantryHasItems`.
    public var onboardingCompleted: Bool

    public init(
        notifications: NotificationSettings = NotificationSettings(),
        expiringSoonDays: Int = 3,
        storageLocations: [String] = DefaultStorageLocations.all,
        defaultStorageLocation: String = DefaultStorageLocations.pantry,
        rememberLastStorageLocation: Bool = true,
        lastUsedStorageLocation: String? = nil,
        theme: AppTheme = .system,
        sortOption: SortOption = .expiryDate,
        allergens: Set<String> = [],
        stores: [StorePlace] = [],
        storeRemindersEnabled: Bool = false,
        onboardingCompleted: Bool = false
    ) {
        self.notifications = notifications
        self.expiringSoonDays = expiringSoonDays
        self.storageLocations = storageLocations
        self.defaultStorageLocation = defaultStorageLocation
        self.rememberLastStorageLocation = rememberLastStorageLocation
        self.lastUsedStorageLocation = lastUsedStorageLocation
        self.theme = theme
        self.sortOption = sortOption
        self.allergens = allergens
        self.stores = stores
        self.storeRemindersEnabled = storeRemindersEnabled
        self.onboardingCompleted = onboardingCompleted
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
        // decodeIfPresent: settings stored before this feature existed have no such key, and
        // failing the whole decode would reset every other setting with it.
        allergens = try container.decodeIfPresent(Set<String>.self, forKey: .allergens) ?? []
        stores = try container.decodeIfPresent([StorePlace].self, forKey: .stores) ?? []
        storeRemindersEnabled = try container.decodeIfPresent(Bool.self, forKey: .storeRemindersEnabled) ?? false
        onboardingCompleted = try container.decodeIfPresent(Bool.self, forKey: .onboardingCompleted) ?? false
    }
}
