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

    /** Soonest-expiring first, so a "+1 to what you already have" offer lands on the one the
     *  user should use up next rather than an arbitrary row. */
    @Query("SELECT * FROM pantry_items WHERE barcode = :barcode ORDER BY expiryDateEpochDays ASC")
    suspend fun findByBarcode(barcode: String): List<PantryItemEntity>

    @Upsert
    suspend fun upsert(item: PantryItemEntity)

    @Query("DELETE FROM pantry_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pantry_items")
    suspend fun clearAll()
}
