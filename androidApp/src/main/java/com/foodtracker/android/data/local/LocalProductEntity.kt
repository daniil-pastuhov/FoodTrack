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
    val shelfLifeDays: Int? = null
)
