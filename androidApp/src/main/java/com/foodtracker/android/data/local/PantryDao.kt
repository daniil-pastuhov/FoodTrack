package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PantryDao {
    @Query("SELECT * FROM pantry_items ORDER BY expiryDateEpochDays ASC")
    fun getAllFlow(): Flow<List<PantryItemEntity>>

    @Query("SELECT * FROM pantry_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PantryItemEntity?

    @Upsert
    suspend fun upsert(item: PantryItemEntity)

    @Query("DELETE FROM pantry_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pantry_items")
    suspend fun clearAll()
}
