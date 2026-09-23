package com.foodtracker.model

import kotlinx.serialization.Serializable

/**
 * Nutritional and dietary detail from Open Food Facts, kept separate from [ProductInfo]'s core
 * identity fields because every one of them is frequently absent — OFF is crowd-sourced, and a
 * product having a name says nothing about whether anyone has filled in its ingredients.
 *
 * Cached alongside the product (see `CachedProduct`) so a scanned item's allergens are still
 * readable offline, and so an allergen warning doesn't depend on the network being up at the
 * moment it matters.
 */
@Serializable
data class ProductNutrition(
    /** Nutri-Score letter, lowercased a..e as OFF reports it, or null when unrated. */
    val nutriScore: String? = null,
    /** NOVA processing group, 1 (unprocessed) to 4 (ultra-processed). */
    val novaGroup: Int? = null,
    /** Allergen names as OFF tags them, already stripped of their language prefix. */
    val allergens: List<String> = emptyList(),
    val ingredients: String? = null
) {
    val isEmpty: Boolean
        get() = nutriScore == null && novaGroup == null && allergens.isEmpty() && ingredients.isNullOrBlank()

    /**
     * The entries of [userAllergens] this product appears to contain.
     *
     * Substring matching in both directions, because the two vocabularies don't line up: a user
     * types "milk" while OFF tags "milk-proteins", and a user types "tree nuts" where OFF tags
     * "nuts". Matching loosely errs toward warning about something harmless rather than staying
     * quiet about something that isn't — the wrong error to make here is the silent one.
     */
    fun matchedAllergens(userAllergens: Set<String>): List<String> {
        if (userAllergens.isEmpty() || allergens.isEmpty()) return emptyList()
        val normalizedUser = userAllergens.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return allergens.filter { allergen ->
            val normalized = allergen.trim().lowercase()
            normalizedUser.any { normalized.contains(it) || it.contains(normalized) }
        }
    }
}

@Serializable
data class ProductInfo(
    val barcode: String,
    val name: String,
    val brand: String? = null,
    val imageUrl: String? = null,
    val category: String? = null,
    val quantity: String? = null,
    val nutrition: ProductNutrition = ProductNutrition()
)
