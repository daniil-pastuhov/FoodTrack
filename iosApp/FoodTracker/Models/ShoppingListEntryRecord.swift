import Foundation
import SwiftData

/// SwiftData persistence model for `ShoppingListEntry`, mirroring `PantryItemRecord`'s
/// split between a `@Model` reference type and the plain `Codable` struct used by views.
@Model
final class ShoppingListEntryRecord {
    @Attribute(.unique) var id: String
    var productName: String
    var barcode: String?
    var category: String?
    var quantity: Int = 1
    var addedDate: Date
    var sortOrder: Double = 0

    init(from entry: ShoppingListEntry) {
        id = entry.id
        productName = entry.productName
        barcode = entry.barcode
        category = entry.category
        quantity = entry.quantity
        addedDate = entry.addedDate
        sortOrder = entry.sortOrder
    }

    func update(from entry: ShoppingListEntry) {
        productName = entry.productName
        barcode = entry.barcode
        category = entry.category
        quantity = entry.quantity
        addedDate = entry.addedDate
        sortOrder = entry.sortOrder
    }

    var asEntry: ShoppingListEntry {
        ShoppingListEntry(
            id: id,
            productName: productName,
            barcode: barcode,
            category: category,
            quantity: quantity,
            addedDate: addedDate,
            sortOrder: sortOrder
        )
    }
}
