import Foundation
import SwiftData

/// Backs the shopping list's autosuggest with every product name the user has ever typed or
/// scanned, most-recently-used first. A standalone table (not derived from
/// `ShoppingListEntryRecord`) so a name is still suggested after its entry is checked off or
/// deleted. `normalizedName` (trimmed, lowercased) is the unique key so recording the same
/// name again updates its recency instead of creating a duplicate suggestion.
@Model
final class ProductNameRecord {
    @Attribute(.unique) var normalizedName: String
    var displayName: String
    var lastUsedAt: Date
    /// The shopping-list manual position last recorded for this product name, or nil if it has
    /// never been dragged to a specific spot.
    var sortOrder: Double?
    /// The shopping-list section this product was last moved to, or nil for the default one.
    /// Defaulted so the store migrates in place.
    var shoppingSectionId: String? = nil

    init(normalizedName: String, displayName: String, lastUsedAt: Date, sortOrder: Double? = nil) {
        self.normalizedName = normalizedName
        self.displayName = displayName
        self.lastUsedAt = lastUsedAt
        self.sortOrder = sortOrder
    }
}
