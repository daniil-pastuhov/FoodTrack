package com.foodtracker.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.foodtracker.model.CategoryPreference

@Entity(tableName = "category_preferences")
data class CategoryPreferenceEntity(
    @PrimaryKey val originName: String,
    val displayName: String,
    val sortOrder: Double = 0.0,
    val enabled: Boolean = true,
    val defaultShelfLifeDays: Int? = null
)

fun CategoryPreferenceEntity.toDomain(): CategoryPreference = CategoryPreference(
    originName = originName,
    displayName = displayName,
    sortOrder = sortOrder,
    enabled = enabled,
    defaultShelfLifeDays = defaultShelfLifeDays
)

fun CategoryPreference.toEntity(): CategoryPreferenceEntity = CategoryPreferenceEntity(
    originName = originName,
    displayName = displayName,
    sortOrder = sortOrder,
    enabled = enabled,
    defaultShelfLifeDays = defaultShelfLifeDays
)
