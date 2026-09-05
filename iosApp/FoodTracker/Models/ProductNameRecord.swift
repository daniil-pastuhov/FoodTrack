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

    init(normalizedName: String, displayName: String, lastUsedAt: Date) {
        self.normalizedName = normalizedName
        self.displayName = displayName
        self.lastUsedAt = lastUsedAt
    }
}
