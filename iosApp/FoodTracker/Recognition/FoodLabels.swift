import Foundation

/// A label as it comes off the on-device image classifier, before this module has decided whether
/// it is worth showing. `text` is the classifier's own English label; `confidence` is 0..1.
public struct RecognizedLabel: Equatable {
    public let text: String
    public let confidence: Float

    public init(text: String, confidence: Float) {
        self.text = text
        self.confidence = confidence
    }
}

/// A label that survived filtering, ready to offer as a name suggestion.
///
/// `key` is a stable identifier localized via `L10n.foodLabelName` (the classifier only speaks
/// English, and this app ships in three languages), and `category` is the coarse grocery
/// category the label belongs to — which is what lets the per-category default shelf life from
/// `ShelfLife` apply to a photographed item that has no barcode to remember one against.
public struct FoodLabelSuggestion: Equatable, Identifiable {
    public let key: String
    public let category: String
    public let confidence: Float

    public var id: String { key }

    public init(key: String, category: String, confidence: Float) {
        self.key = key
        self.category = category
        self.confidence = confidence
    }
}

/// Turns raw on-device image-classification labels into grocery name suggestions.
///
/// A hand-written port of `shared`'s `com.foodtracker.recognition.FoodLabels` - nothing under
/// `iosApp/` consumes that module. The keys and categories are a cross-platform contract; the
/// classifier feeding them differs (Vision here, ML Kit on Android), which is exactly why the
/// filtering lives behind one shared vocabulary rather than in each platform's camera code.
public enum FoodLabels {

    /// Below this the classifier is guessing, and a wrong name pre-filled into the form is worse
    /// than an empty one - the user has to notice it before they can correct it.
    public static let minConfidence: Float = 0.6

    /// More than a few chips stops being a shortcut and becomes a menu to read.
    public static let maxSuggestions = 4

    // Coarse grocery categories. Deliberately few: these exist to hang a default shelf life off
    // (see `ShelfLife.suggest`) and to group the suggestion, not to mirror Open Food Facts' full
    // taxonomy.
    public static let categoryFruit = "Fruit"
    public static let categoryVegetables = "Vegetables"
    public static let categoryBakery = "Bakery"
    public static let categoryDairy = "Dairy"
    public static let categoryProtein = "Meat & fish"
    public static let categoryDrinks = "Drinks"
    public static let categorySweets = "Sweets"
    public static let categoryPantry = "Pantry"

    /// Classifier label (lowercased) to (stable key, category).
    ///
    /// Several classifier labels intentionally share a key where the distinction doesn't matter
    /// to a shopping list ("citrus" and "orange" are both an orange to someone unpacking a bag).
    private static let labels: [String: (key: String, category: String)] = [
        "apple": ("apple", categoryFruit),
        "banana": ("banana", categoryFruit),
        "orange": ("orange", categoryFruit),
        "citrus": ("orange", categoryFruit),
        "lemon": ("lemon", categoryFruit),
        "strawberry": ("strawberry", categoryFruit),
        "grape": ("grape", categoryFruit),
        "watermelon": ("watermelon", categoryFruit),
        "pineapple": ("pineapple", categoryFruit),
        "peach": ("peach", categoryFruit),
        "pear": ("pear", categoryFruit),
        "cherry": ("cherry", categoryFruit),
        "avocado": ("avocado", categoryFruit),
        "berry": ("berry", categoryFruit),
        "fruit": ("fruit", categoryFruit),
        "tomato": ("tomato", categoryVegetables),
        "potato": ("potato", categoryVegetables),
        "carrot": ("carrot", categoryVegetables),
        "broccoli": ("broccoli", categoryVegetables),
        "cucumber": ("cucumber", categoryVegetables),
        "onion": ("onion", categoryVegetables),
        "pumpkin": ("pumpkin", categoryVegetables),
        "mushroom": ("mushroom", categoryVegetables),
        "lettuce": ("lettuce", categoryVegetables),
        "cabbage": ("cabbage", categoryVegetables),
        "salad": ("salad", categoryVegetables),
        "vegetable": ("vegetable", categoryVegetables),
        "bread": ("bread", categoryBakery),
        "baguette": ("bread", categoryBakery),
        "bun": ("bun", categoryBakery),
        "croissant": ("croissant", categoryBakery),
        "cake": ("cake", categoryBakery),
        "cookie": ("cookie", categoryBakery),
        "pastry": ("pastry", categoryBakery),
        "doughnut": ("doughnut", categoryBakery),
        "pie": ("pie", categoryBakery),
        "waffle": ("waffle", categoryBakery),
        "pancake": ("pancake", categoryBakery),
        "cheese": ("cheese", categoryDairy),
        "milk": ("milk", categoryDairy),
        "yogurt": ("yogurt", categoryDairy),
        "butter": ("butter", categoryDairy),
        "egg": ("egg", categoryDairy),
        "meat": ("meat", categoryProtein),
        "steak": ("steak", categoryProtein),
        "chicken": ("chicken", categoryProtein),
        "sausage": ("sausage", categoryProtein),
        "bacon": ("bacon", categoryProtein),
        "fish": ("fish", categoryProtein),
        "seafood": ("seafood", categoryProtein),
        "shrimp": ("shrimp", categoryProtein),
        "juice": ("juice", categoryDrinks),
        "coffee": ("coffee", categoryDrinks),
        "tea": ("tea", categoryDrinks),
        "beer": ("beer", categoryDrinks),
        "wine": ("wine", categoryDrinks),
        "cocktail": ("drink", categoryDrinks),
        "drink": ("drink", categoryDrinks),
        "chocolate": ("chocolate", categorySweets),
        "candy": ("candy", categorySweets),
        "ice cream": ("ice_cream", categorySweets),
        "dessert": ("dessert", categorySweets),
        "honey": ("honey", categoryPantry),
        "rice": ("rice", categoryPantry),
        "pasta": ("pasta", categoryPantry),
        "noodle": ("pasta", categoryPantry),
        "bean": ("bean", categoryPantry),
        "corn": ("corn", categoryPantry),
        "nut": ("nut", categoryPantry),
        "soup": ("soup", categoryPantry),
        "pizza": ("pizza", categoryPantry),
        "sandwich": ("sandwich", categoryPantry),
        "food": ("food", categoryPantry)
    ]

    /// Keys that name a whole aisle rather than a product. Still worth offering - they carry a
    /// usable category - but never ahead of something specific.
    private static let genericKeys: Set<String> = ["food", "fruit", "vegetable", "drink", "dessert"]

    /// Filters and ranks `labels` into the suggestions worth offering.
    ///
    /// Drops anything the allow-list doesn't recognise or that falls below `minConfidence`,
    /// collapses labels that map to the same key (keeping the most confident), sorts by
    /// confidence and caps the result at `maxSuggestions`.
    ///
    /// The generic buckets ("food", "fruit", "vegetable") are deliberately ranked last whatever
    /// their confidence: the classifier is nearly always very sure something is "Food", and
    /// letting that outrank "Banana" would bury the only suggestion with any use in it.
    public static func suggestions(from recognized: [RecognizedLabel]) -> [FoodLabelSuggestion] {
        var bestByKey: [String: FoodLabelSuggestion] = [:]
        for label in recognized where label.confidence >= minConfidence {
            let normalized = label.text.trimmingCharacters(in: .whitespaces).lowercased()
            guard let mapped = labels[normalized] else { continue }
            let candidate = FoodLabelSuggestion(
                key: mapped.key,
                category: mapped.category,
                confidence: label.confidence
            )
            if let existing = bestByKey[mapped.key], existing.confidence >= candidate.confidence {
                continue
            }
            bestByKey[mapped.key] = candidate
        }
        return bestByKey.values
            .sorted { lhs, rhs in
                let lhsGeneric = genericKeys.contains(lhs.key)
                let rhsGeneric = genericKeys.contains(rhs.key)
                if lhsGeneric != rhsGeneric { return !lhsGeneric }
                if lhs.confidence != rhs.confidence { return lhs.confidence > rhs.confidence }
                // Dictionary iteration order is undefined, so ties need a deterministic
                // tie-break or the chips reshuffle between runs on the same photo.
                return lhs.key < rhs.key
            }
            .prefixArray(maxSuggestions)
    }
}

private extension Array {
    func prefixArray(_ maxLength: Int) -> [Element] { Array(prefix(maxLength)) }
}
