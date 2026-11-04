package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Under SQLite's limit of 999 bound variables on the oldest Android versions we support. */
private const val MAX_IDS_PER_QUERY = 500

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

    @Query("SELECT * FROM pantry_items WHERE id IN (:ids)")
    suspend fun getChunkByIds(ids: List<String>): List<PantryItemEntity>

    @Query("DELETE FROM pantry_items WHERE id IN (:ids)")
    suspend fun deleteChunkByIds(ids: List<String>)

    @Upsert
    suspend fun upsertAll(items: List<PantryItemEntity>)

    @Transaction
    suspend fun getByIds(ids: List<String>): List<PantryItemEntity> =
        ids.chunked(MAX_IDS_PER_QUERY).flatMap { getChunkByIds(it) }

    /** One transaction, so observers see a single change however many chunks it takes. */
    @Transaction
    suspend fun deleteByIds(ids: List<String>) {
        ids.chunked(MAX_IDS_PER_QUERY).forEach { deleteChunkByIds(it) }
    }

    @Query("DELETE FROM pantry_items")
    suspend fun clearAll()
}
