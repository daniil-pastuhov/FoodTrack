package com.foodtracker.model

import kotlinx.serialization.Serializable

/**
 * A user-made group on the shopping list ("Dairy aisle", "Pharmacy", ...).
 *
 * The shopping list used to be grouped by the product's category. It is now grouped by these
 * instead: everything lands in the default section ([DEFAULT_SHOPPING_SECTION_ID]) unless the
 * user has put it somewhere else, and the product remembers that section for the next time it
 * is added (see `ProductNameRepository.getSectionId`).
 *
 * The default section always exists, is always first, and has no stored [name] - its label is
 * localized at display time. [collapsed] is persisted, so the app and the home-screen widget
 * agree on what is folded away.
 */
@Serializable
data class ShoppingSection(
    val id: String,
    val name: String,
    val sortOrder: Double = 0.0,
    val collapsed: Boolean = false
) {
    val isDefault: Boolean get() = id == DEFAULT_SHOPPING_SECTION_ID
}

const val DEFAULT_SHOPPING_SECTION_ID = "default"

/** The default section as it is created on a fresh install (and re-created if ever missing). */
val DEFAULT_SHOPPING_SECTION = ShoppingSection(
    id = DEFAULT_SHOPPING_SECTION_ID,
    name = "",
    // Below anything a user section could ever be given, so plain sort-by-order keeps it first.
    sortOrder = -Double.MAX_VALUE
)

/** The section [entry] is shown in: its own, if that still exists, otherwise the default. */
fun resolveShoppingSectionId(sectionId: String?, existingIds: Set<String>): String =
    if (sectionId != null && sectionId in existingIds) sectionId else DEFAULT_SHOPPING_SECTION_ID
