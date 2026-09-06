package com.foodtracker.model

import kotlinx.serialization.Serializable

/** A user-editable preference for one raw category value as it comes off a scanned/added
 *  product (a `PantryItem`/`ShoppingListEntry`'s `category` field, e.g. from Open Food Facts).
 *  [originName] is that raw value and never changes once created - it's the stable key products
 *  are matched against. [displayName] is what the user renamed it to (defaults to [originName])
 *  and is resolved wherever a category is shown, so a rename applies retroactively to every
 *  product with that origin category, including ones scanned again in the future - without
 *  having to rewrite the category stored on each product. */
@Serializable
data class CategoryPreference(
    val originName: String,
    val displayName: String,
    val sortOrder: Double = 0.0,
    val enabled: Boolean = true
)

/** Resolves how [origin] should be shown to the user: a disabled category folds into "no
 *  category" (null) everywhere, an origin with no preference yet (not auto-registered) shows
 *  as-is, and an enabled, known one shows its [CategoryPreference.displayName]. */
fun resolveCategoryDisplayName(origin: String?, preferences: List<CategoryPreference>): String? {
    if (origin == null) return null
    val pref = preferences.find { it.originName == origin } ?: return origin
    return if (pref.enabled) pref.displayName else null
}

/** Sort key for ordering categories by their user-chosen position: a disabled or not-yet-known
 *  origin (including null/uncategorized) always sorts last. */
fun categoryPreferenceSortOrder(origin: String?, preferences: List<CategoryPreference>): Double {
    if (origin == null) return Double.MAX_VALUE
    val pref = preferences.find { it.originName == origin } ?: return Double.MAX_VALUE
    return if (pref.enabled) pref.sortOrder else Double.MAX_VALUE
}
