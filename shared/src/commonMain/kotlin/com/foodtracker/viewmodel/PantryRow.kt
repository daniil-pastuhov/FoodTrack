package com.foodtracker.viewmodel

import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.model.ShoppingListEntry
import kotlinx.datetime.LocalDate

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

/**
 * One product waiting in the scanner's batch queue: scanned and looked up, but not yet saved.
 *
 * Batch mode exists because unpacking a weekly shop is fifteen to thirty items, and the normal
 * flow costs a full round trip through Add/Edit for each one. Here each scan is looked up and
 * parked, and the whole queue is reviewed and committed at the end.
 *
 * [expiryDate] starts from whatever the scan could work out — a date read off the packaging, or
 * the shelf life this barcode was last given (see `ShelfLife`) — so a queue of familiar products
 * is often correct without the user touching any of it. [isLookingUp] is true while the product
 * lookup is still in flight, so a row can appear immediately on the scan rather than after the
 * network answers.
 */
data class BatchScanEntry(
    val barcode: String,
    val name: String,
    val brand: String? = null,
    val imageUrl: String? = null,
    val category: String? = null,
    val expiryDate: LocalDate,
    val quantity: Int = 1,
    val storageLocation: String,
    /** Where [expiryDate] came from, so the review list can flag the rows that are guesses. */
    val expirySource: ShelfLifeSource,
    val isLookingUp: Boolean = false
) {
    /** The barcode is the identity: the queue holds at most one row per product, and scanning
     *  the same one again bumps its quantity rather than adding a second row. */
    val id: String get() = barcode

    /** True when nothing better than the fallback was known, so the user should look at it. */
    val needsAttention: Boolean
        get() = expirySource == ShelfLifeSource.FALLBACK
}
