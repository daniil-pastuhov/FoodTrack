import Foundation

public struct ShoppingListEntry: Identifiable, Codable, Equatable {
    public let id: String
    public var productName: String
    public var barcode: String?
    public var category: String?
    public var quantity: Int
    public var addedDate: Date
    public var sortOrder: Double
    /// The `ShoppingSection` this entry is in. Nil, or an id that no longer exists, means the
    /// default section. `category` is kept (it is carried into the pantry on purchase) but no
    /// longer groups the shopping list.
    public var sectionId: String?

    public init(
        id: String = UUID().uuidString,
        productName: String,
        barcode: String? = nil,
        category: String? = nil,
        quantity: Int = 1,
        addedDate: Date = Date(),
        sortOrder: Double = 0,
        sectionId: String? = nil
    ) {
        self.id = id
        self.productName = productName
        self.barcode = barcode
        self.category = category
        self.quantity = quantity
        self.addedDate = addedDate
        self.sortOrder = sortOrder
        self.sectionId = sectionId
    }

    /// Mirrors `PantryItem.shoppingMatchKey()` so a deleted essential pantry item and a
    /// manually-added shopping list entry dedupe against each other.
    public func matchKey() -> String {
        barcode ?? productName.trimmingCharacters(in: .whitespaces).lowercased()
    }
}
