import Foundation

/// Nutritional and dietary detail from Open Food Facts, kept separate from `ProductInfo`'s core
/// identity fields because every one of them is frequently absent — OFF is crowd-sourced, and a
/// product having a name says nothing about whether anyone has filled in its ingredients.
///
/// Cached alongside the product (see `CachedProduct`) so a scanned item's allergens are still
/// readable offline, and so an allergen warning doesn't depend on the network being up at the
/// moment it matters. Mirrors `shared`'s `com.foodtracker.model.ProductNutrition`.
public struct ProductNutrition: Codable, Equatable {
    /// Nutri-Score letter, lowercased a...e as OFF reports it, or nil when unrated.
    public var nutriScore: String?
    /// NOVA processing group, 1 (unprocessed) to 4 (ultra-processed).
    public var novaGroup: Int?
    /// Allergen names as OFF tags them, already stripped of their language prefix.
    public var allergens: [String]
    public var ingredients: String?

    public init(
        nutriScore: String? = nil,
        novaGroup: Int? = nil,
        allergens: [String] = [],
        ingredients: String? = nil
    ) {
        self.nutriScore = nutriScore
        self.novaGroup = novaGroup
        self.allergens = allergens
        self.ingredients = ingredients
    }

    public var isEmpty: Bool {
        nutriScore == nil && novaGroup == nil && allergens.isEmpty &&
        (ingredients?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
    }

    /// The entries of `userAllergens` this product appears to contain.
    ///
    /// Substring matching in both directions, because the two vocabularies don't line up: a user
    /// types "milk" while OFF tags "milk-proteins", and a user types "tree nuts" where OFF tags
    /// "nuts". Matching loosely errs toward warning about something harmless rather than staying
    /// quiet about something that isn't — the wrong error to make here is the silent one.
    public func matchedAllergens(_ userAllergens: Set<String>) -> [String] {
        guard !userAllergens.isEmpty, !allergens.isEmpty else { return [] }
        let normalizedUser = userAllergens
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty }
        guard !normalizedUser.isEmpty else { return [] }
        return allergens.filter { allergen in
            let normalized = allergen.trimmingCharacters(in: .whitespaces).lowercased()
            return normalizedUser.contains { normalized.contains($0) || $0.contains(normalized) }
        }
    }

    /// Builds nutrition from a raw Open Food Facts product dictionary. iOS calls OFF directly
    /// with `URLSession` (see `NativePantryViewModel`), so this is the counterpart of Kotlin's
    /// `OffProduct.toNutrition`.
    public static func from(offProduct product: [String: Any]) -> ProductNutrition {
        // OFF reports "not-applicable"/"unknown" for products it simply hasn't rated; those are
        // absent ratings rather than grades, and showing them as a badge would imply a
        // judgement OFF never made.
        let unrated: Set<String> = ["not-applicable", "unknown", ""]
        let grade = (product["nutriscore_grade"] as? String)?
            .trimmingCharacters(in: .whitespaces)
            .lowercased()

        let nova = product["nova_group"] as? Int
        let tags = (product["allergens_tags"] as? [String]) ?? []
        var seen = Set<String>()
        // Tags arrive language-prefixed ("en:milk"), the same shape categories do.
        let allergens = tags
            .map { stripLanguagePrefix($0).trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        let ingredients = (product["ingredients_text"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)

        return ProductNutrition(
            nutriScore: (grade.map { unrated.contains($0) } ?? true) ? nil : grade,
            novaGroup: (nova.map { (1...4).contains($0) } ?? false) ? nova : nil,
            allergens: allergens,
            ingredients: (ingredients?.isEmpty ?? true) ? nil : ingredients
        )
    }
}
