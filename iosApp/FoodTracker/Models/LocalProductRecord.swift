import Foundation
import SwiftData

/// Caches a scanned/saved product's details keyed by `barcode` — a successful Open Food Facts
/// lookup, or one the user filled in by hand because the API had no record of it — so a later
/// scan of the same barcode prefetches it from here instead of the network.
@Model
final class LocalProductRecord {
    @Attribute(.unique) var barcode: String
    var name: String
    var brand: String?
    var imageUrl: String?
    var category: String?
    /// Shelf life in days learned from the last time this barcode was added to the pantry, used
    /// to pre-fill the expiry date on a re-scan. Nil until the product has been added once (or
    /// when the observed span was implausible - see `ShelfLife.learnableShelfLife`).
    var shelfLifeDays: Int?

    init(
        barcode: String,
        name: String,
        brand: String? = nil,
        imageUrl: String? = nil,
        category: String? = nil,
        shelfLifeDays: Int? = nil
    ) {
        self.barcode = barcode
        self.name = name
        self.brand = brand
        self.imageUrl = imageUrl
        self.category = category
        self.shelfLifeDays = shelfLifeDays
    }
}
