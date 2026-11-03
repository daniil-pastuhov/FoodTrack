import Foundation
import SwiftData

/// SwiftData persistence model for `ShoppingSection`. Every stored property has a default so
/// adding this model (and any later field) migrates the existing store in place.
@Model
final class ShoppingSectionRecord {
    @Attribute(.unique) var id: String = ""
    var name: String = ""
    var sortOrder: Double = 0
    var collapsed: Bool = false

    init(from section: ShoppingSection) {
        id = section.id
        name = section.name
        sortOrder = section.sortOrder
        collapsed = section.collapsed
    }

    func update(from section: ShoppingSection) {
        name = section.name
        sortOrder = section.sortOrder
        collapsed = section.collapsed
    }

    var asSection: ShoppingSection {
        ShoppingSection(id: id, name: name, sortOrder: sortOrder, collapsed: collapsed)
    }
}
