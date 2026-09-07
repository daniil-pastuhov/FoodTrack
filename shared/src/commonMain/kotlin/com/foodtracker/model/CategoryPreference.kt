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

/** Sentinel [CategoryPreference.originName] standing in for products with no category assigned
 *  at all (`category == null`) - the "Other" bucket - so it can be renamed, reordered, or
 *  disabled from Settings just like any real category. A real scanned/typed category is always
 *  a non-blank trimmed string, so the empty string can never collide with one. */
const val UNCATEGORIZED_ORIGIN = ""

/**
 * [CategoryPreference]s indexed by [CategoryPreference.originName].
 *
 * Resolving a category is a per-item operation done across the whole pantry, so doing it with a
 * linear `find` over the preference list makes it O(items × preferences). Building this index
 * once per preferences change and resolving through it makes each lookup O(1).
 */
class CategoryPreferenceIndex(val preferences: List<CategoryPreference>) {
    private val byOrigin: Map<String, CategoryPreference> =
        preferences.associateBy { it.originName }

    operator fun get(origin: String?): CategoryPreference? = byOrigin[origin ?: UNCATEGORIZED_ORIGIN]

    companion object {
        val EMPTY = CategoryPreferenceIndex(emptyList())
    }
}

fun List<CategoryPreference>.index(): CategoryPreferenceIndex = CategoryPreferenceIndex(this)

/** Resolves how [origin] should be shown to the user: a disabled category folds into "no
 *  category" (null) everywhere, an origin with no preference yet (not auto-registered) shows
 *  as-is, and an enabled, known one shows its [CategoryPreference.displayName]. `null` (no
 *  category at all) resolves through the [UNCATEGORIZED_ORIGIN] sentinel the same way. */
fun resolveCategoryDisplayName(origin: String?, preferences: List<CategoryPreference>): String? {
    val key = origin ?: UNCATEGORIZED_ORIGIN
    val pref = preferences.find { it.originName == key } ?: return origin
    return if (pref.enabled) pref.displayName else null
}

/** [resolveCategoryDisplayName] resolved through a prebuilt [CategoryPreferenceIndex]. Prefer
 *  this wherever more than one item is being resolved against the same preferences. */
fun resolveCategoryDisplayName(origin: String?, index: CategoryPreferenceIndex): String? {
    val pref = index[origin] ?: return origin
    return if (pref.enabled) pref.displayName else null
}

/** Sort key for ordering categories by their user-chosen position: a disabled or not-yet-known
 *  origin (including null/uncategorized) always sorts last. */
fun categoryPreferenceSortOrder(origin: String?, preferences: List<CategoryPreference>): Double {
    val key = origin ?: UNCATEGORIZED_ORIGIN
    val pref = preferences.find { it.originName == key } ?: return Double.MAX_VALUE
    return if (pref.enabled) pref.sortOrder else Double.MAX_VALUE
}

/** [categoryPreferenceSortOrder] resolved through a prebuilt [CategoryPreferenceIndex]. */
fun categoryPreferenceSortOrder(origin: String?, index: CategoryPreferenceIndex): Double {
    val pref = index[origin] ?: return Double.MAX_VALUE
    return if (pref.enabled) pref.sortOrder else Double.MAX_VALUE
}

/** Resolves a possibly freehand-typed category name against existing preferences: if [typed]
 *  exactly matches a known category's [CategoryPreference.displayName] (case-insensitively),
 *  returns that category's [CategoryPreference.originName] instead - so typing a name that
 *  matches an existing rename (e.g. typing "test" when origin "test2" was already renamed to
 *  display as "test") reuses that same origin rather than fragmenting into a second, duplicate
 *  category. Falls back to the trimmed input itself (or null) when nothing matches. */
fun resolveCategoryOrigin(typed: String?, preferences: List<CategoryPreference>): String? {
    val trimmed = typed?.trim()
    if (trimmed.isNullOrBlank()) return null
    val matched = preferences.find { it.displayName.equals(trimmed, ignoreCase = true) }
    return matched?.originName ?: trimmed
}
