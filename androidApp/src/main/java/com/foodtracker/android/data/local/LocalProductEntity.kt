package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Caches a scanned/saved product's details keyed by [barcode] — a successful Open Food Facts
 *  lookup, or one the user filled in by hand because the API had no record of it — so a later
 *  scan of the same barcode prefetches it from here instead of the network. */
@Entity(tableName = "local_products")
data class LocalProductEntity(
    @PrimaryKey val barcode: String,
    val name: String,
    val brand: String?,
    val imageUrl: String?,
    val category: String?,
    /** Shelf life in days learned from the last time this barcode was added to the pantry, used
     *  to pre-fill the expiry date on a re-scan. Null until the product has been added once (or
     *  when the observed span was implausible - see `ShelfLife.learnableShelfLife`). */
    val shelfLifeDays: Int? = null,
    /** Storage location this barcode was put in the last time it was added to the pantry, used
     *  to pre-select the location on a re-scan. Null until the product has been added once. */
    val storageLocation: String? = null,
    /** Nutri-Score letter as Open Food Facts reports it (a..e), or null when unrated. */
    val nutriScore: String? = null,
    /** NOVA processing group, 1..4. */
    val novaGroup: Int? = null,
    /** Allergen names, newline-separated. A delimited column rather than a join table: this is
     *  a disposable cache of someone else's data, always read whole and never queried by
     *  allergen, so a second table would buy nothing. Newline because an allergen name can
     *  legitimately contain a comma. */
    val allergens: String? = null,
    val ingredients: String? = null
)
