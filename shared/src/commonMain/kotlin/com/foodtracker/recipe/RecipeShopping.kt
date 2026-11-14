package com.foodtracker.recipe

/*
 * "Add to shopping list" on a recipe or a menu: the ingredients the cook doesn't have (not from
 * the pantry), gathered, tidied and merged for the dialog. The dialog adds the ticked ones through
 * the shopping list's ordinary add path (PantryStateHolder.addShoppingListEntries). iOS:
 * `RecipeShopping` in `Recipe/RecipeShopping.swift`, ported by hand.
 */

/** One row of the dialog. [key] is how the shopping list matches names (trimmed, lower case). */
data class ShoppingCandidate(
    val name: String,
    val key: String,
    /** On the shopping list already: shown unticked and disabled, never added or counted. */
    val alreadyListed: Boolean,
    /** The server marked it a staple (salt, oil, ...): listed last, ticked like the rest. */
    val staple: Boolean
)

/**
 * The non-pantry ingredients of [recipes] (a recipe, or every dish of a menu), in order of first
 * appearance with staples last, each tidied ([tidyShoppingName]) and listed once (ignoring case),
 * and marked when [listedNames] (the shopping list's names) has it already. Plain water is left
 * out when the server marked it a staple ([isPlainWater]); without that mark (older answers, the
 * phone's own recipes) every non-pantry ingredient is listed.
 */
fun shoppingCandidates(recipes: List<Recipe>, listedNames: Collection<String>): List<ShoppingCandidate> {
    val listed = listedNames.mapTo(HashSet()) { it.trim().lowercase() }
    val seen = LinkedHashMap<String, ShoppingCandidate>()
    for (ingredient in recipes.flatMap { it.ingredients }) {
        if (ingredient.fromPantry) continue
        val name = tidyShoppingName(ingredient.name) ?: continue
        if (ingredient.staple && isPlainWater(name)) continue
        val key = name.lowercase()
        val known = seen[key]
        // A staple elsewhere in the menu stays one only if every mention is one.
        if (known == null) {
            seen[key] = ShoppingCandidate(name, key, key in listed, ingredient.staple)
        } else if (known.staple && !ingredient.staple) {
            seen[key] = known.copy(staple = false)
        }
    }
    val all = seen.values.toList()
    return all.filterNot { it.staple } + all.filter { it.staple }
}

/** The rows the dialog ticks first: everything not on the list already (the owner's rule). */
fun defaultShoppingSelection(candidates: List<ShoppingCandidate>): Set<String> =
    candidates.filterNot { it.alreadyListed }.mapTo(LinkedHashSet()) { it.key }

/**
 * An ingredient's name as a shopping-list entry: what follows the first comma (a preparation
 * note such as ", thinly sliced") dropped, runs of whitespace made one space, trailing
 * punctuation trimmed, the first letter capitalised. Null when nothing is left.
 */
fun tidyShoppingName(raw: String): String? {
    val cut = raw.substringBefore(',').substringBefore('，')
    val collapsed = cut.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    val trimmed = collapsed.trimEnd('.', ';', ':', '-', '–', '—', ' ').trimStart('-', '–', '—', '•', '*', ' ')
    if (trimmed.isEmpty()) return null
    return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

/** Water in the ten recipe languages (the staple words' first group on the server). */
private val WATER_WORDS = setOf(
    "water", "vann", "vannet", "wasser", "agua", "eau", "acqua", "woda", "wody", "wodę", "vatten",
    "вода", "воды", "воду", "води", "кипяток", "окріп"
)

/** Whether a name the server marked a staple is water ("Water", "Cold water", "Тёплая вода"):
 *  nobody buys tap water for a recipe. */
fun isPlainWater(name: String): Boolean =
    name.lowercase().split(Regex("[\\s'’-]+")).any { it in WATER_WORDS }
