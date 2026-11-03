package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.foodtracker.model.ShoppingSection

@Entity(tableName = "shopping_sections")
data class ShoppingSectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Double = 0.0,
    val collapsed: Boolean = false
)

fun ShoppingSectionEntity.toDomain(): ShoppingSection =
    ShoppingSection(id = id, name = name, sortOrder = sortOrder, collapsed = collapsed)

fun ShoppingSection.toEntity(): ShoppingSectionEntity =
    ShoppingSectionEntity(id = id, name = name, sortOrder = sortOrder, collapsed = collapsed)
