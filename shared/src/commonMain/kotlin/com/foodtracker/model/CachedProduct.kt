package com.foodtracker.model

import kotlinx.serialization.Serializable

/**
 * A row of the local product cache: everything known about a barcode, including the shelf life
 * learned from the last time it was added (see [ShelfLife]).
 *
 * Distinct from [ProductInfo], which doubles as the Open Food Facts DTO and so deliberately
 * carries no shelf life. This is the storage-side view, used where the whole cache has to be
 * read or written at once — backup and restore.
 */
@Serializable
data class CachedProduct(
    val barcode: String,
    val name: String,
    val brand: String? = null,
    val imageUrl: String? = null,
    val category: String? = null,
    val shelfLifeDays: Int? = null,
    /** Storage location this barcode was put in the last time it was added, so a re-scan
     *  pre-selects it. Null until then; may name a location that has since been removed or
     *  renamed, which callers must check against the current list. */
    val storageLocation: String? = null,
    /** Cached so an allergen warning doesn't depend on the network being up at the moment it
     *  matters, and so a re-scan shows the same detail offline. */
    val nutrition: ProductNutrition = ProductNutrition()
) {
    fun toProductInfo(): ProductInfo = ProductInfo(
        barcode = barcode,
        name = name,
        brand = brand,
        imageUrl = imageUrl,
        category = category,
        nutrition = nutrition
    )
}
