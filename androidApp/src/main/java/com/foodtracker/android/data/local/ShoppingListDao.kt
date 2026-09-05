package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingListDao {
    @Query("SELECT * FROM shopping_list_entries ORDER BY sortOrder ASC, addedDateEpochDays DESC")
    fun getAllFlow(): Flow<List<ShoppingListEntryEntity>>

    @Query("SELECT * FROM shopping_list_entries WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): ShoppingListEntryEntity?

    @Query("SELECT * FROM shopping_list_entries WHERE LOWER(TRIM(productName)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByName(name: String): ShoppingListEntryEntity?

    @Query("SELECT MAX(sortOrder) FROM shopping_list_entries")
    suspend fun getMaxSortOrder(): Double?

    @Upsert
    suspend fun upsert(entry: ShoppingListEntryEntity)

    @Query("DELETE FROM shopping_list_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shopping_list_entries")
    suspend fun clearAll()
}
