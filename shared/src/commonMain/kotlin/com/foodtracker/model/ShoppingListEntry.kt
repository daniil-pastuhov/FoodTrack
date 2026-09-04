package com.foodtracker.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class ShoppingListEntry(
    val id: String,
    val productName: String,
    val barcode: String? = null,
    val addedDate: LocalDate
) {
    /** Same matching rule as `PantryItem.shoppingMatchKey()`, so a deleted essential
     *  pantry item and a manually-added shopping list entry dedupe against each other. */
    fun matchKey(): String = barcode ?: productName.trim().lowercase()
}
