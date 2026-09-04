import Foundation
import SwiftData

/// SwiftData persistence model for `PantryItem`. Kept separate from the plain `PantryItem`
/// struct (which stays a `Codable` value type used throughout the view layer) because
/// `@Model` requires a reference type — `NativePantryViewModel` converts between the two.
@Model
final class PantryItemRecord {
    @Attribute(.unique) var id: String
    var name: String
    var brand: String?
    var barcode: String?
    var imageUrl: String?
    var expiryDate: Date
    var addedDate: Date
    var storageLocation: String
    var category: String?
    var notes: String?

    init(from item: PantryItem) {
        id = item.id
        name = item.name
        brand = item.brand
        barcode = item.barcode
        imageUrl = item.imageUrl
        expiryDate = item.expiryDate
        addedDate = item.addedDate
        storageLocation = item.storageLocation
        category = item.category
        notes = item.notes
    }

    func update(from item: PantryItem) {
        name = item.name
        brand = item.brand
        barcode = item.barcode
        imageUrl = item.imageUrl
        expiryDate = item.expiryDate
        addedDate = item.addedDate
        storageLocation = item.storageLocation
        category = item.category
        notes = item.notes
    }

    var asPantryItem: PantryItem {
        PantryItem(
            id: id,
            name: name,
            brand: brand,
            barcode: barcode,
            imageUrl: imageUrl,
            expiryDate: expiryDate,
            addedDate: addedDate,
            storageLocation: storageLocation,
            category: category,
            notes: notes
        )
    }
}
