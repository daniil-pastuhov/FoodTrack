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

    init(barcode: String, name: String, brand: String? = nil, imageUrl: String? = nil, category: String? = nil) {
        self.barcode = barcode
        self.name = name
        self.brand = brand
        self.imageUrl = imageUrl
        self.category = category
    }
}
