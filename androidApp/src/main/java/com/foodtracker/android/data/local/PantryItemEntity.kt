package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate

@Entity(tableName = "pantry_items")
data class PantryItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String?,
    val barcode: String?,
    val imageUrl: String?,
    val expiryDateEpochDays: Int,
    val addedDateEpochDays: Int,
    val storageLocation: String,
    val category: String?,
    val quantity: Int = 1,
    val notes: String?,
    val essential: Boolean = true,
    val notificationsEnabled: Boolean = true
)

fun PantryItemEntity.toDomain(): PantryItem = PantryItem(
    id = id,
    name = name,
    brand = brand,
    barcode = barcode,
    imageUrl = imageUrl,
    expiryDate = LocalDate.fromEpochDays(expiryDateEpochDays),
    addedDate = LocalDate.fromEpochDays(addedDateEpochDays),
    storageLocation = storageLocation,
    category = category,
    quantity = quantity,
    notes = notes,
    essential = essential,
    notificationsEnabled = notificationsEnabled
)

fun PantryItem.toEntity(): PantryItemEntity = PantryItemEntity(
    id = id,
    name = name,
    brand = brand,
    barcode = barcode,
    imageUrl = imageUrl,
    expiryDateEpochDays = expiryDate.toEpochDays().toInt(),
    addedDateEpochDays = addedDate.toEpochDays().toInt(),
    storageLocation = storageLocation,
    category = category,
    quantity = quantity,
    notes = notes,
    essential = essential,
    notificationsEnabled = notificationsEnabled
)
