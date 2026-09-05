package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductNameDao {
    @Query("SELECT displayName FROM known_product_names ORDER BY lastUsedAtEpochMillis DESC LIMIT 200")
    fun getRecentNamesFlow(): Flow<List<String>>

    @Upsert
    suspend fun upsert(entity: ProductNameEntity)
}
