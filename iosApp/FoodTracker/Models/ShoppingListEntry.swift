import Foundation

public struct ShoppingListEntry: Identifiable, Codable {
    public let id: String
    public var productName: String
    public var barcode: String?
    public var category: String?
    public var quantity: Int
    public var addedDate: Date

    public init(
        id: String = UUID().uuidString,
        productName: String,
        barcode: String? = nil,
        category: String? = nil,
        quantity: Int = 1,
        addedDate: Date = Date()
    ) {
        self.id = id
        self.productName = productName
        self.barcode = barcode
        self.category = category
        self.quantity = quantity
        self.addedDate = addedDate
    }

    /// Mirrors `PantryItem.shoppingMatchKey()` so a deleted essential pantry item and a
    /// manually-added shopping list entry dedupe against each other.
    public func matchKey() -> String {
        barcode ?? productName.trimmingCharacters(in: .whitespaces).lowercased()
    }
}
