import SwiftUI

/// One pantry item as a `PantryRow`, with its place worded from the item and the category
/// preferences. The name stays on one line; the full name is on the product detail.
struct ItemRowView: View {
    let item: PantryItem
    var expiringSoonDays: Int = 3
    var categoryPreferences: [CategoryPreference] = []
    var onDecrement: (() -> Void)? = nil
    var selection: PantryRow.Selection? = nil
    var isCurrent: Bool = false
    var onOpen: (() -> Void)? = nil

    /// "Fridge · Dairy", or the location alone when the item has no category.
    static func place(of item: PantryItem, categoryPreferences: [CategoryPreference]) -> String {
        let location = L10n.storageLocationName(item.storageLocation)
        guard let category = resolveCategoryDisplayName(item.category, categoryPreferences), !category.isEmpty else {
            return location
        }
        return "\(location) · \(L10n.categoryName(category))"
    }

    var body: some View {
        PantryRow(
            item: item,
            expiringSoonDays: expiringSoonDays,
            place: Self.place(of: item, categoryPreferences: categoryPreferences),
            selection: selection,
            isCurrent: isCurrent,
            onOpen: onOpen,
            onUseOne: onDecrement
        )
    }
}
