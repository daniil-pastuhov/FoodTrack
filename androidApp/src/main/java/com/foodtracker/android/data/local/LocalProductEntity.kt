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
    val category: String?
)
