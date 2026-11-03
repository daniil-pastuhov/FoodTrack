import Foundation

/// How a restore treats what is already on the device.
public enum RestoreMode {
    /// Adds what's missing and leaves everything else alone. Existing pantry items keep their
    /// current state, and settings are not touched — they are this device's preferences, not the
    /// backup's. The safe default: nothing the user has can be lost by choosing it.
    case merge
    /// Wipes local data and restores the backup's, settings included. Chosen when the backup is
    /// the intended truth — a new phone, or undoing a mess.
    case replace
}

/// What a restore did, so the UI can report it rather than just saying "done".
public struct RestoreSummary: Equatable {
    public var itemsRestored = 0
    public var shoppingEntriesRestored = 0
    public var productsRestored = 0
    public var categoriesRestored = 0
    public var settingsRestored = false

    public var isEmpty: Bool {
        itemsRestored == 0 && shoppingEntriesRestored == 0 &&
        productsRestored == 0 && categoriesRestored == 0
    }
}

/// Why a backup file couldn't be read. Distinguished so the UI can tell the user something
/// actionable instead of a generic failure.
public enum BackupReadResult {
    case success(BackupDocument)
    /// Not JSON, or not this app's JSON.
    case malformed
    /// Written by a newer version of the app than this one.
    case unsupportedVersion(Int)
}

/// A full export of everything the app stores locally.
///
/// A hand-written port of `shared`'s `com.foodtracker.backup.BackupDocument`. The wire format is
/// a cross-platform contract — an Android backup has to restore on iOS and vice versa — and two
/// things make that more than a matter of matching field names:
///
///  - **Dates.** Kotlin stores `LocalDate` and serializes it as `"2026-10-01"`. Swift's
///    `PantryItem` uses `Date`, which `JSONEncoder` would write as a floating-point interval by
///    default. Dates are therefore coded explicitly as ISO calendar days here, in UTC, so the
///    string on both sides means the same calendar day rather than the same instant.
///  - **Enums.** `AppTheme` and `SortOption` have different raw values on the two platforms
///    (`system` vs `SYSTEM`, `expiryDate` vs `EXPIRY_DATE`). The backup uses Kotlin's spelling
///    as the canonical one, and this file maps to and from it, rather than exporting a value
///    Android would fail to decode.
///
/// **Photos are not included**, matching Android: an item's `imageUrl` path is kept, but the
/// image files are not embedded, since base64 in JSON would multiply a backup's size for data
/// the user can retake. A restore onto a different device shows those items with the placeholder.
public struct BackupDocument: Codable, Equatable {
    public static let currentVersion = 1

    public var version: Int
    public var exportedAtEpochMillis: Int64
    public var items: [PantryItem]
    public var shoppingList: [ShoppingListEntry]
    public var categoryPreferences: [CategoryPreference]
    public var products: [CachedProduct]
    public var knownProductNames: [String]
    public var settings: AppSettings

    public var isEmpty: Bool {
        items.isEmpty && shoppingList.isEmpty && products.isEmpty &&
        categoryPreferences.isEmpty && knownProductNames.isEmpty
    }

    public init(
        version: Int = BackupDocument.currentVersion,
        exportedAtEpochMillis: Int64 = 0,
        items: [PantryItem] = [],
        shoppingList: [ShoppingListEntry] = [],
        categoryPreferences: [CategoryPreference] = [],
        products: [CachedProduct] = [],
        knownProductNames: [String] = [],
        settings: AppSettings = AppSettings()
    ) {
        self.version = version
        self.exportedAtEpochMillis = exportedAtEpochMillis
        self.items = items
        self.shoppingList = shoppingList
        self.categoryPreferences = categoryPreferences
        self.products = products
        self.knownProductNames = knownProductNames
        self.settings = settings
    }

    // MARK: - Wire format

    private enum CodingKeys: String, CodingKey {
        case version, exportedAtEpochMillis, items, shoppingList
        case categoryPreferences, products, knownProductNames, settings
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decodeIfPresent(Int.self, forKey: .version) ?? BackupDocument.currentVersion
        exportedAtEpochMillis = try container.decodeIfPresent(Int64.self, forKey: .exportedAtEpochMillis) ?? 0
        items = (try container.decodeIfPresent([WireItem].self, forKey: .items) ?? []).map { $0.asPantryItem }
        shoppingList = (try container.decodeIfPresent([WireEntry].self, forKey: .shoppingList) ?? []).map { $0.asEntry }
        categoryPreferences = try container.decodeIfPresent([CategoryPreference].self, forKey: .categoryPreferences) ?? []
        products = try container.decodeIfPresent([CachedProduct].self, forKey: .products) ?? []
        knownProductNames = try container.decodeIfPresent([String].self, forKey: .knownProductNames) ?? []
        settings = (try container.decodeIfPresent(WireSettings.self, forKey: .settings))?.asSettings ?? AppSettings()
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(version, forKey: .version)
        try container.encode(exportedAtEpochMillis, forKey: .exportedAtEpochMillis)
        try container.encode(items.map(WireItem.init(from:)), forKey: .items)
        try container.encode(shoppingList.map(WireEntry.init(from:)), forKey: .shoppingList)
        try container.encode(categoryPreferences, forKey: .categoryPreferences)
        try container.encode(products, forKey: .products)
        try container.encode(knownProductNames, forKey: .knownProductNames)
        try container.encode(WireSettings(from: settings), forKey: .settings)
    }
}

/// One cached product, shelf life included — the storage-side view of a barcode, mirroring
/// `shared`'s `CachedProduct`. Distinct from `ProductInfo`, which doubles as the Open Food Facts
/// DTO and so carries no shelf life.
public struct CachedProduct: Codable, Equatable {
    public var barcode: String
    public var name: String
    public var brand: String?
    public var imageUrl: String?
    public var category: String?
    public var shelfLifeDays: Int?
    /// Where this barcode was last put away; nil in backups made before this was tracked.
    public var storageLocation: String?
    /// Cached so an allergen warning doesn't depend on the network being up at the moment it
    /// matters, and so a re-scan shows the same detail offline.
    public var nutrition: ProductNutrition

    public init(
        barcode: String,
        name: String,
        brand: String? = nil,
        imageUrl: String? = nil,
        category: String? = nil,
        shelfLifeDays: Int? = nil,
        storageLocation: String? = nil,
        nutrition: ProductNutrition = ProductNutrition()
    ) {
        self.barcode = barcode
        self.name = name
        self.brand = brand
        self.imageUrl = imageUrl
        self.category = category
        self.shelfLifeDays = shelfLifeDays
        self.storageLocation = storageLocation
        self.nutrition = nutrition
    }
}

// MARK: - Date coding

/// Calendar days, in UTC, formatted the way kotlinx-serialization writes a `LocalDate`.
/// UTC rather than the device's zone so that exporting in one time zone and restoring in another
/// can't shift every expiry date by a day.
enum BackupDates {
    static let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    static func string(from date: Date) -> String { formatter.string(from: date) }
    static func date(from string: String) -> Date { formatter.date(from: string) ?? Date() }
}

private struct WireItem: Codable {
    let id: String
    let name: String
    let brand: String?
    let barcode: String?
    let imageUrl: String?
    let expiryDate: String
    let addedDate: String
    let storageLocation: String
    let category: String?
    let quantity: Int
    let notes: String?
    let essential: Bool
    /// Optional so backups written before the per-item switch existed still decode; nil means on.
    let notificationsEnabled: Bool?

    init(from item: PantryItem) {
        id = item.id
        name = item.name
        brand = item.brand
        barcode = item.barcode
        imageUrl = item.imageUrl
        expiryDate = BackupDates.string(from: item.expiryDate)
        addedDate = BackupDates.string(from: item.addedDate)
        storageLocation = item.storageLocation
        category = item.category
        quantity = item.quantity
        notes = item.notes
        essential = item.essential
        notificationsEnabled = item.notificationsEnabled
    }

    var asPantryItem: PantryItem {
        PantryItem(
            id: id,
            name: name,
            brand: brand,
            barcode: barcode,
            imageUrl: imageUrl,
            expiryDate: BackupDates.date(from: expiryDate),
            addedDate: BackupDates.date(from: addedDate),
            storageLocation: storageLocation,
            category: category,
            quantity: quantity,
            notes: notes,
            essential: essential,
            notificationsEnabled: notificationsEnabled ?? true
        )
    }
}

private struct WireEntry: Codable {
    let id: String
    let productName: String
    let barcode: String?
    let category: String?
    let quantity: Int
    let addedDate: String
    let sortOrder: Double

    init(from entry: ShoppingListEntry) {
        id = entry.id
        productName = entry.productName
        barcode = entry.barcode
        category = entry.category
        quantity = entry.quantity
        addedDate = BackupDates.string(from: entry.addedDate)
        sortOrder = entry.sortOrder
    }

    var asEntry: ShoppingListEntry {
        ShoppingListEntry(
            id: id,
            productName: productName,
            barcode: barcode,
            category: category,
            quantity: quantity,
            addedDate: BackupDates.date(from: addedDate),
            sortOrder: sortOrder
        )
    }
}

/// Settings with the two enums spelled the way Kotlin serializes them. An unknown value decodes
/// to the default rather than failing the whole restore — a theme name is not worth losing a
/// pantry over.
private struct WireSettings: Codable {
    let notifications: NotificationSettings
    let expiringSoonDays: Int
    let storageLocations: [String]
    let defaultStorageLocation: String
    let rememberLastStorageLocation: Bool
    let lastUsedStorageLocation: String?
    let theme: String
    let sortOption: String

    init(from settings: AppSettings) {
        notifications = settings.notifications
        expiringSoonDays = settings.expiringSoonDays
        storageLocations = settings.storageLocations
        defaultStorageLocation = settings.defaultStorageLocation
        rememberLastStorageLocation = settings.rememberLastStorageLocation
        lastUsedStorageLocation = settings.lastUsedStorageLocation
        theme = WireSettings.themeNames[settings.theme] ?? "SYSTEM"
        sortOption = WireSettings.sortNames[settings.sortOption] ?? "EXPIRY_DATE"
    }

    var asSettings: AppSettings {
        AppSettings(
            notifications: notifications,
            expiringSoonDays: expiringSoonDays,
            storageLocations: storageLocations,
            defaultStorageLocation: defaultStorageLocation,
            rememberLastStorageLocation: rememberLastStorageLocation,
            lastUsedStorageLocation: lastUsedStorageLocation,
            theme: WireSettings.themeNames.first { $0.value == theme }?.key ?? .system,
            sortOption: WireSettings.sortNames.first { $0.value == sortOption }?.key ?? .expiryDate
        )
    }

    private static let themeNames: [AppTheme: String] = [
        .system: "SYSTEM", .light: "LIGHT", .dark: "DARK"
    ]
    private static let sortNames: [SortOption: String] = [
        .expiryDate: "EXPIRY_DATE", .name: "NAME",
        .dateAdded: "DATE_ADDED", .storageLocation: "STORAGE_LOCATION"
    ]
}

/// Reads and writes `BackupDocument`s.
public enum BackupSerializer {

    public static func encode(_ document: BackupDocument) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(document),
              let json = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return json
    }

    public static func decode(_ raw: String) -> BackupReadResult {
        guard let data = raw.data(using: .utf8),
              let document = try? JSONDecoder().decode(BackupDocument.self, from: data) else {
            return .malformed
        }
        // Reading a newer file with fields we don't understand would restore a subset and call
        // it a success, which is the one outcome worse than refusing.
        guard document.version <= BackupDocument.currentVersion else {
            return .unsupportedVersion(document.version)
        }
        return .success(document)
    }

    /// Filename for an export, stamped so successive backups don't overwrite each other.
    public static func suggestedFileName(isoDate: String) -> String {
        "foodtracker-backup-\(isoDate).json"
    }
}
