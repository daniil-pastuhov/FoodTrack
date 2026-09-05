package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.foodtracker.model.ShoppingListEntry
import kotlinx.datetime.LocalDate

@Entity(tableName = "shopping_list_entries")
data class ShoppingListEntryEntity(
    @PrimaryKey val id: String,
    val productName: String,
    val barcode: String?,
    val category: String? = null,
    val quantity: Int = 1,
    val addedDateEpochDays: Int,
    val sortOrder: Double = 0.0
)

fun ShoppingListEntryEntity.toDomain(): ShoppingListEntry = ShoppingListEntry(
    id = id,
    productName = productName,
    barcode = barcode,
    category = category,
    quantity = quantity,
    addedDate = LocalDate.fromEpochDays(addedDateEpochDays),
    sortOrder = sortOrder
)

fun ShoppingListEntry.toEntity(): ShoppingListEntryEntity = ShoppingListEntryEntity(
    id = id,
    productName = productName,
    barcode = barcode,
    category = category,
    quantity = quantity,
    addedDateEpochDays = addedDate.toEpochDays().toInt(),
    sortOrder = sortOrder
)
