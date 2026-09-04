import Foundation

public struct ShoppingListEntry: Identifiable, Codable {
    public let id: String
    public var productName: String
    public var barcode: String?
    public var addedDate: Date

    public init(id: String = UUID().uuidString, productName: String, barcode: String? = nil, addedDate: Date = Date()) {
        self.id = id
        self.productName = productName
        self.barcode = barcode
        self.addedDate = addedDate
    }

    /// Mirrors `PantryItem.shoppingMatchKey()` so a deleted essential pantry item and a
    /// manually-added shopping list entry dedupe against each other.
    public func matchKey() -> String {
        barcode ?? productName.trimmingCharacters(in: .whitespaces).lowercased()
    }
}
