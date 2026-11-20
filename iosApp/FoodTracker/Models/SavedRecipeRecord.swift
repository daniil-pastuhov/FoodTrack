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
    /// The dish's picture (JPEG) of a recipe from a pack asked with pictures, kept on this device
    /// only (never in a backup), outside the store's file. Nil without one, and in rows from before.
    @Attribute(.externalStorage) var dishImage: Data? = nil
    /// Its step pictures, a binary property list of step index (as text) to JPEG. Nil without any.
    @Attribute(.externalStorage) var stepImages: Data? = nil
    /// The dish picture cropped small for the Saved row. Nil without a picture.
    var thumbnail: Data? = nil
    /// Marked a favourite in Saved recipes. False in rows from before favourites came.
    var isFavorite: Bool = false

    init(id: String, kind: String, payload: Data, title: String, createdAt: Date, source: String) {
        self.id = id
        self.kind = kind
        self.payload = payload
        self.title = title
        self.createdAt = createdAt
        self.source = source
    }
}
