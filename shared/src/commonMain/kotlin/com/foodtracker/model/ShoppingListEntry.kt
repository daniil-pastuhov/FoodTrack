package com.foodtracker.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class ShoppingListEntry(
    val id: String,
    val productName: String,
    val barcode: String? = null,
    val category: String? = null,
    val quantity: Int = 1,
    val addedDate: LocalDate,
    val sortOrder: Double = 0.0,
    /** The [ShoppingSection] this entry is in. Null, or an id that no longer exists, means the
     *  default section (see [resolveShoppingSectionId]). [category] is kept - it is carried into
     *  the pantry when the product is bought - but no longer groups the shopping list. */
    val sectionId: String? = null
) {
    /** Same matching rule as `PantryItem.shoppingMatchKey()`, so a deleted essential
     *  pantry item and a manually-added shopping list entry dedupe against each other. */
    fun matchKey(): String = barcode ?: productName.trim().lowercase()
}
