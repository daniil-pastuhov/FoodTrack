package com.foodtracker.viewmodel

import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry

/**
 * A pantry item with everything the UI needs already derived.
 *
 * Each of these fields used to be computed inside composition, once per card per recomposition:
 * [daysRemaining] and [status] from `DateUtils.today()`, and [categoryDisplayName] from a linear
 * scan over every [CategoryPreference]. They are computed once per item here instead, when the
 * underlying data changes, which is the only time any of them can actually change.
 *
 * [sortName] exists purely so the sort comparators don't call `name.lowercase()` inside their
 * selector — that allocates a fresh string on every one of the O(n log n) comparisons.
 */
data class PantryRow(
    val item: PantryItem,
    val daysRemaining: Int,
    val status: ExpiryStatus,
    val categoryDisplayName: String?,
    val sortName: String
) {
    val id: String get() = item.id
}

/**
 * A row in the shopping list's flattened, grouped-by-category layout: either a category header
 * (never draggable) or an entry (draggable within its own group only).
 *
 * The flattening, grouping and category-name resolution behind this used to run in composition
 * on every emission. [categoryDisplayName] is carried on [Item] so the drag handler's
 * "are these two rows in the same group?" check is a field comparison rather than two linear
 * preference scans per drag frame.
 */
sealed interface ShoppingRow {
    /** Stable identity for `LazyColumn`'s `key`. */
    val key: String

    data class Header(val category: String?) : ShoppingRow {
        override val key: String get() = "header_${category ?: ""}"
    }

    data class Item(
        val entry: ShoppingListEntry,
        val categoryDisplayName: String?
    ) : ShoppingRow {
        override val key: String get() = entry.id
    }
}

/**
 * One user-visible category in Settings, together with [mergedOrigins] — the other raw category
 * values that have been renamed onto the same display name, shown as a subtitle.
 *
 * Building that string used to happen inside the `LazyColumn` item body, so every row rescanned
 * and re-sorted the whole preference list on every recomposition, including on every frame of a
 * drag.
 */
data class CategoryRow(
    val preference: CategoryPreference,
    val mergedOrigins: String
)
