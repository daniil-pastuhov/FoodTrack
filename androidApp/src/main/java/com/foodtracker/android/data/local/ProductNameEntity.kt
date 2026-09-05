package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** [normalizedName] (trimmed, lowercased) is the primary key so recording the same product
 *  name again updates its recency instead of creating a duplicate suggestion; [displayName]
 *  keeps the casing the user actually typed or the barcode lookup returned. */
@Entity(tableName = "known_product_names")
data class ProductNameEntity(
    @PrimaryKey val normalizedName: String,
    val displayName: String,
    val lastUsedAtEpochMillis: Long
)
