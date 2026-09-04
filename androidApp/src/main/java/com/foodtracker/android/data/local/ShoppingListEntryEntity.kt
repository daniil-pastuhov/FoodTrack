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
    val addedDateEpochDays: Int
)

fun ShoppingListEntryEntity.toDomain(): ShoppingListEntry = ShoppingListEntry(
    id = id,
    productName = productName,
    barcode = barcode,
    addedDate = LocalDate.fromEpochDays(addedDateEpochDays)
)

fun ShoppingListEntry.toEntity(): ShoppingListEntryEntity = ShoppingListEntryEntity(
    id = id,
    productName = productName,
    barcode = barcode,
    addedDateEpochDays = addedDate.toEpochDays()
)
