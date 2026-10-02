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

/// What the item editor's category field shows for a stored `category`: the name it is shown
/// under everywhere else, so the field never shows a raw English key. Mirrors
/// `categoryFieldText` in the Android app.
func categoryFieldText(_ category: String, _ preferences: [CategoryPreference]) -> String {
    L10n.categoryName(preferences.first { $0.originName == category }?.displayName ?? category)
}

/// The category to store for what the item editor's field says when saving an item that had
/// `original` as its category.
///
/// Unchanged text keeps `original`: several categories can share a display name (merged in
/// Settings), so mapping the name back could otherwise move the item to a different one just
/// because it was edited. Otherwise a built-in category's translated name maps to its English
/// key (what shelf-life defaults match on), a name shown for an existing category maps to that
/// category, and anything else is a new category, stored as typed.
func categoryForFieldText(_ text: String, original: String?, _ preferences: [CategoryPreference]) -> String {
    if let original, text == categoryFieldText(original, preferences) { return original }
    if let builtIn = FoodLabels.builtInCategories.first(where: { L10n.categoryName($0) == text }) { return builtIn }
    if let match = preferences.first(where: { L10n.categoryName($0.displayName) == text }) { return match.originName }
    return text
}

/// The display name to store when the user renames a category to `text`. Picking a translated
/// name ("Молочное") stands for the built-in key it translates ("Dairy"), so that renaming a
/// category to it merges the two, as renaming to any other existing name does.
func categoryNameForInput(_ text: String, _ displayNames: [String]) -> String {
    displayNames.first { L10n.categoryName($0) == text } ?? text
}

/// The display name to rename a category currently shown as `current` to when the user confirms
/// `input`, or nil to leave it alone. Confirming the name the field was prefilled with must
/// change nothing, even when another category shows the same text: mapping it back could
/// otherwise pick that other category's name, renaming or merging this one, or freezing an
/// untouched built-in in today's language. Mirrors `categoryRename` in the Android app.
func categoryRename(current: String, input: String, _ displayNames: [String]) -> String? {
    let text = input.trimmingCharacters(in: .whitespaces)
    if text.isEmpty || text == L10n.categoryName(current) { return nil }
    let newName = categoryNameForInput(text, displayNames)
    return newName == current ? nil : newName
}

/// Sort key for ordering categories by their user-chosen position: a disabled or not-yet-known
/// origin (including nil/uncategorized) always sorts last.
func categoryPreferenceSortOrder(_ origin: String?, _ preferences: [CategoryPreference]) -> Double {
    let key = origin ?? UNCATEGORIZED_ORIGIN
    guard let pref = preferences.first(where: { $0.originName == key }) else { return .greatestFiniteMagnitude }
    return pref.enabled ? pref.sortOrder : .greatestFiniteMagnitude
}
