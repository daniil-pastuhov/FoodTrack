import Foundation
import SwiftData

/// SwiftData row for one entry in Saved recipes: a recipe or a menu, as JSON in `payload`, the
/// same shape Android keeps in its `saved_recipes` table. Every stored property has a default so
/// the store migrates in place, now and when a later version adds one.
///
/// Compiled into the widget extension too, only because `NativePantryViewModel.sharedModelContainer`
/// lists it in the shared schema; the widget never reads it. So it stays free of the Recipe
/// domain types, which live in the app target alone.
@Model
final class SavedRecipeRecord {
    @Attribute(.unique) var id: String = UUID().uuidString
    /// "recipe" or "menu" (`SavedRecipeKind`).
    var kind: String = "recipe"
    /// The `Recipe` or `Menu` as JSON.
    var payload: Data = Data()
    var title: String = ""
    var createdAt: Date = Date()
    /// "server" or "device" (`RecipeSource`).
    var source: String = "device"

    init(id: String, kind: String, payload: Data, title: String, createdAt: Date, source: String) {
        self.id = id
        self.kind = kind
        self.payload = payload
        self.title = title
        self.createdAt = createdAt
        self.source = source
    }
}
