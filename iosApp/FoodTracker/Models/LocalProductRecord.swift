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
    /// Storage location this barcode was put in the last time it was added to the pantry, used
    /// to pre-select the location on a re-scan. Nil until the product has been added once.
    /// Defaulted so an existing store migrates in place.
    var storageLocation: String? = nil
    /// Nutri-Score letter as Open Food Facts reports it (a...e), or nil when unrated.
    var nutriScore: String?
    /// NOVA processing group, 1...4.
    var novaGroup: Int?
    /// Allergen names, newline-separated. A delimited property rather than a related model:
    /// this is a disposable cache of someone else's data, always read whole and never queried
    /// by allergen. Newline because an allergen name can legitimately contain a comma.
    var allergens: String?
    var ingredients: String?

    /// Splits the delimited allergen property back into a list.
    var nutrition: ProductNutrition {
        ProductNutrition(
            nutriScore: nutriScore,
            novaGroup: novaGroup,
            allergens: allergens?.components(separatedBy: "\n").filter { !$0.isEmpty } ?? [],
            ingredients: ingredients
        )
    }

    func apply(_ nutrition: ProductNutrition) {
        nutriScore = nutrition.nutriScore
        novaGroup = nutrition.novaGroup
        allergens = nutrition.allergens.isEmpty ? nil : nutrition.allergens.joined(separator: "\n")
        ingredients = nutrition.ingredients
    }

    init(
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
        self.nutriScore = nutrition.nutriScore
        self.novaGroup = nutrition.novaGroup
        self.allergens = nutrition.allergens.isEmpty ? nil : nutrition.allergens.joined(separator: "\n")
        self.ingredients = nutrition.ingredients
    }
}
