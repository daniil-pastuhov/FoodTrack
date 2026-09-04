import Foundation
import SwiftData

/// SwiftData persistence model for `ShoppingListEntry`, mirroring `PantryItemRecord`'s
/// split between a `@Model` reference type and the plain `Codable` struct used by views.
@Model
final class ShoppingListEntryRecord {
    @Attribute(.unique) var id: String
    var productName: String
    var barcode: String?
    var addedDate: Date

    init(from entry: ShoppingListEntry) {
        id = entry.id
        productName = entry.productName
        barcode = entry.barcode
        addedDate = entry.addedDate
    }

    var asEntry: ShoppingListEntry {
        ShoppingListEntry(
            id: id,
            productName: productName,
            barcode: barcode,
            addedDate: addedDate
        )
    }
}
