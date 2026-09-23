package com.foodtracker.recognition

/**
 * A label as it comes off the on-device image classifier, before this module has decided whether
 * it is worth showing. [text] is the classifier's own English label; [confidence] is 0..1.
 */
data class RecognizedLabel(
    val text: String,
    val confidence: Float
)

/**
 * A label that survived filtering, ready to offer as a name suggestion.
 *
 * [key] is a stable identifier each platform localizes (the classifier only speaks English, and
 * this app ships in three languages), and [category] is the coarse grocery category the label
 * belongs to — which is what lets the per-category default shelf life from `ShelfLife` apply to a
 * photographed item that has no barcode to remember one against.
 */
data class FoodLabelSuggestion(
    val key: String,
    val category: String,
    val confidence: Float
)

/**
 * Turns raw on-device image-classification labels into grocery name suggestions.
 *
 * This is the barcode-less half of adding an item: produce, bakery and market goods carry no
 * barcode to look up, so today the user types the name every single time. The classifier runs
 * fully on-device (ML Kit Image Labeling on Android, Vision on iOS) — nothing about the photo
 * leaves the device.
 *
 * The classifier's vocabulary is general-purpose and mostly not about food, so the allow-list
 * below is doing real work: it is what stops "Person", "Table" or "Plastic" from being offered
 * as a product name. A label that isn't in it is dropped rather than shown, because a wrong
 * suggestion costs the user more than no suggestion does.
 *
 * iOS reimplements this by hand in `Recognition/FoodLabels.swift` (nothing under `iosApp/`
 * consumes this module), so the keys and categories here are a cross-platform contract.
 */
object FoodLabels {

    /** Below this the classifier is guessing, and a wrong name pre-filled into the form is
     *  worse than an empty one - the user has to notice it before they can correct it. */
    const val MIN_CONFIDENCE = 0.6f

    /** More than a few chips stops being a shortcut and becomes a menu to read. */
    const val MAX_SUGGESTIONS = 4

    // Coarse grocery categories. Deliberately few: these exist to hang a default shelf life off
    // (see ShelfLife.suggest) and to group the suggestion, not to mirror Open Food Facts' full
    // taxonomy.
    const val CATEGORY_FRUIT = "Fruit"
    const val CATEGORY_VEGETABLES = "Vegetables"
    const val CATEGORY_BAKERY = "Bakery"
    const val CATEGORY_DAIRY = "Dairy"
    const val CATEGORY_PROTEIN = "Meat & fish"
    const val CATEGORY_DRINKS = "Drinks"
    const val CATEGORY_SWEETS = "Sweets"
    const val CATEGORY_PANTRY = "Pantry"

    /**
     * Classifier label (lowercased) to (stable key, category).
     *
     * Keys are snake_case and stable: they are what each platform looks up to show the name in
     * the device's language, so renaming one silently breaks that lookup and drops the label
     * back to its raw English text.
     *
     * Several classifier labels intentionally share a key where the distinction doesn't matter
     * to a shopping list ("citrus" and "orange" are both an orange to someone unpacking a bag).
     */
    private val LABELS: Map<String, Pair<String, String>> = mapOf(
        // Fruit
        "apple" to ("apple" to CATEGORY_FRUIT),
        "banana" to ("banana" to CATEGORY_FRUIT),
        "orange" to ("orange" to CATEGORY_FRUIT),
        "citrus" to ("orange" to CATEGORY_FRUIT),
        "lemon" to ("lemon" to CATEGORY_FRUIT),
        "strawberry" to ("strawberry" to CATEGORY_FRUIT),
        "grape" to ("grape" to CATEGORY_FRUIT),
        "watermelon" to ("watermelon" to CATEGORY_FRUIT),
        "pineapple" to ("pineapple" to CATEGORY_FRUIT),
        "peach" to ("peach" to CATEGORY_FRUIT),
        "pear" to ("pear" to CATEGORY_FRUIT),
        "cherry" to ("cherry" to CATEGORY_FRUIT),
        "avocado" to ("avocado" to CATEGORY_FRUIT),
        "berry" to ("berry" to CATEGORY_FRUIT),
        "fruit" to ("fruit" to CATEGORY_FRUIT),

        // Vegetables
        "tomato" to ("tomato" to CATEGORY_VEGETABLES),
        "potato" to ("potato" to CATEGORY_VEGETABLES),
        "carrot" to ("carrot" to CATEGORY_VEGETABLES),
        "broccoli" to ("broccoli" to CATEGORY_VEGETABLES),
        "cucumber" to ("cucumber" to CATEGORY_VEGETABLES),
        "onion" to ("onion" to CATEGORY_VEGETABLES),
        "pumpkin" to ("pumpkin" to CATEGORY_VEGETABLES),
        "mushroom" to ("mushroom" to CATEGORY_VEGETABLES),
        "lettuce" to ("lettuce" to CATEGORY_VEGETABLES),
        "cabbage" to ("cabbage" to CATEGORY_VEGETABLES),
        "salad" to ("salad" to CATEGORY_VEGETABLES),
        "vegetable" to ("vegetable" to CATEGORY_VEGETABLES),

        // Bakery
        "bread" to ("bread" to CATEGORY_BAKERY),
        "baguette" to ("bread" to CATEGORY_BAKERY),
        "bun" to ("bun" to CATEGORY_BAKERY),
        "croissant" to ("croissant" to CATEGORY_BAKERY),
        "cake" to ("cake" to CATEGORY_BAKERY),
        "cookie" to ("cookie" to CATEGORY_BAKERY),
        "pastry" to ("pastry" to CATEGORY_BAKERY),
        "doughnut" to ("doughnut" to CATEGORY_BAKERY),
        "pie" to ("pie" to CATEGORY_BAKERY),
        "waffle" to ("waffle" to CATEGORY_BAKERY),
        "pancake" to ("pancake" to CATEGORY_BAKERY),

        // Dairy
        "cheese" to ("cheese" to CATEGORY_DAIRY),
        "milk" to ("milk" to CATEGORY_DAIRY),
        "yogurt" to ("yogurt" to CATEGORY_DAIRY),
        "butter" to ("butter" to CATEGORY_DAIRY),
        "egg" to ("egg" to CATEGORY_DAIRY),

        // Meat & fish
        "meat" to ("meat" to CATEGORY_PROTEIN),
        "steak" to ("steak" to CATEGORY_PROTEIN),
        "chicken" to ("chicken" to CATEGORY_PROTEIN),
        "sausage" to ("sausage" to CATEGORY_PROTEIN),
        "bacon" to ("bacon" to CATEGORY_PROTEIN),
        "fish" to ("fish" to CATEGORY_PROTEIN),
        "seafood" to ("seafood" to CATEGORY_PROTEIN),
        "shrimp" to ("shrimp" to CATEGORY_PROTEIN),

        // Drinks
        "juice" to ("juice" to CATEGORY_DRINKS),
        "coffee" to ("coffee" to CATEGORY_DRINKS),
        "tea" to ("tea" to CATEGORY_DRINKS),
        "beer" to ("beer" to CATEGORY_DRINKS),
        "wine" to ("wine" to CATEGORY_DRINKS),
        "cocktail" to ("drink" to CATEGORY_DRINKS),
        "drink" to ("drink" to CATEGORY_DRINKS),

        // Sweets
        "chocolate" to ("chocolate" to CATEGORY_SWEETS),
        "candy" to ("candy" to CATEGORY_SWEETS),
        "ice cream" to ("ice_cream" to CATEGORY_SWEETS),
        "dessert" to ("dessert" to CATEGORY_SWEETS),
        "honey" to ("honey" to CATEGORY_PANTRY),

        // Pantry
        "rice" to ("rice" to CATEGORY_PANTRY),
        "pasta" to ("pasta" to CATEGORY_PANTRY),
        "noodle" to ("pasta" to CATEGORY_PANTRY),
        "bean" to ("bean" to CATEGORY_PANTRY),
        "corn" to ("corn" to CATEGORY_PANTRY),
        "nut" to ("nut" to CATEGORY_PANTRY),
        "soup" to ("soup" to CATEGORY_PANTRY),
        "pizza" to ("pizza" to CATEGORY_PANTRY),
        "sandwich" to ("sandwich" to CATEGORY_PANTRY),
        "food" to ("food" to CATEGORY_PANTRY)
    )

    /**
     * Filters and ranks [labels] into the suggestions worth offering.
     *
     * Drops anything the allow-list doesn't recognise or that falls below [MIN_CONFIDENCE],
     * collapses labels that map to the same key (keeping the most confident), sorts by
     * confidence and caps the result at [MAX_SUGGESTIONS].
     *
     * The generic buckets ("food", "fruit", "vegetable") are deliberately ranked last whatever
     * their confidence: the classifier is nearly always very sure something is "Food", and
     * letting that outrank "Banana" would bury the only suggestion with any use in it.
     */
    fun suggestionsFrom(labels: List<RecognizedLabel>): List<FoodLabelSuggestion> =
        labels.asSequence()
            .filter { it.confidence >= MIN_CONFIDENCE }
            .mapNotNull { label ->
                LABELS[label.text.trim().lowercase()]?.let { (key, category) ->
                    FoodLabelSuggestion(key, category, label.confidence)
                }
            }
            .groupBy { it.key }
            .map { (_, group) -> group.maxBy { it.confidence } }
            .sortedWith(compareBy({ if (it.key in GENERIC_KEYS) 1 else 0 }, { -it.confidence }))
            .take(MAX_SUGGESTIONS)
            .toList()

    /** Keys that name a whole aisle rather than a product. Still worth offering - they carry a
     *  usable category - but never ahead of something specific. */
    private val GENERIC_KEYS = setOf("food", "fruit", "vegetable", "drink", "dessert")
}
