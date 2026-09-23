package com.foodtracker.repository

import com.foodtracker.model.PantryItem
import kotlinx.coroutines.flow.Flow

interface PantryRepository {
    fun getItemsFlow(): Flow<List<PantryItem>>
    suspend fun getItem(id: String): PantryItem?

    /** The items currently in the pantry carrying [barcode]. Used to offer "+1 to the one you
     *  already have" instead of silently creating a duplicate row on a re-scan. */
    suspend fun findByBarcode(barcode: String): List<PantryItem>
    suspend fun saveItem(item: PantryItem)
    suspend fun deleteItem(id: String)
    suspend fun clearAll()
}
