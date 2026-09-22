import Foundation

/// A user-editable preference for one raw category value as it comes off a scanned/added
/// product (a `PantryItem`/`ShoppingListEntry`'s `category` field, e.g. from Open Food Facts).
/// `originName` is that raw value and never changes once created - it's the stable key products
/// are matched against. `displayName` is what the user renamed it to (defaults to `originName`)
/// and is resolved wherever a category is shown, so a rename applies retroactively to every
/// product with that origin category, including ones scanned again in the future - without
/// having to rewrite the category stored on each product. Mirrors Android's
/// `com.foodtracker.model.CategoryPreference`.
public struct CategoryPreference: Identifiable, Codable, Equatable {
    public var originName: String
    public var displayName: String
    public var sortOrder: Double
    public var enabled: Bool
    /// Shelf life, in days, to pre-fill on a new item in this category whose barcode has no
    /// remembered shelf life of its own - see `ShelfLife.suggest`. Nil means no default has
    /// been configured, which is the state every category starts in.
    public var defaultShelfLifeDays: Int?

    public var id: String { originName }

    public init(
        originName: String,
        displayName: String,
        sortOrder: Double = 0,
        enabled: Bool = true,
        defaultShelfLifeDays: Int? = nil
    ) {
        self.originName = originName
        self.displayName = displayName
        self.sortOrder = sortOrder
        self.enabled = enabled
        self.defaultShelfLifeDays = defaultShelfLifeDays
    }
}

/// Sentinel `CategoryPreference.originName` standing in for products with no category assigned
/// at all (`category == nil`) - the "Other" bucket - so it can be renamed, reordered, or
/// disabled from Settings just like any real category.
public let UNCATEGORIZED_ORIGIN = ""

/// Resolves how `origin` should be shown to the user: a disabled category folds into "no
/// category" (nil) everywhere, an origin with no preference yet (not auto-registered) shows
/// as-is, and an enabled, known one shows its `CategoryPreference.displayName`. `nil` (no
/// category at all) resolves through the `UNCATEGORIZED_ORIGIN` sentinel the same way.
func resolveCategoryDisplayName(_ origin: String?, _ preferences: [CategoryPreference]) -> String? {
    let key = origin ?? UNCATEGORIZED_ORIGIN
    guard let pref = preferences.first(where: { $0.originName == key }) else { return origin }
    return pref.enabled ? pref.displayName : nil
}

/// Sort key for ordering categories by their user-chosen position: a disabled or not-yet-known
/// origin (including nil/uncategorized) always sorts last.
func categoryPreferenceSortOrder(_ origin: String?, _ preferences: [CategoryPreference]) -> Double {
    let key = origin ?? UNCATEGORIZED_ORIGIN
    guard let pref = preferences.first(where: { $0.originName == key }) else { return .greatestFiniteMagnitude }
    return pref.enabled ? pref.sortOrder : .greatestFiniteMagnitude
}
