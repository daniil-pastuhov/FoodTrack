package com.foodtracker.repository

import com.foodtracker.model.ShoppingListEntry
import kotlinx.coroutines.flow.Flow

interface ShoppingListRepository {
    fun getEntriesFlow(): Flow<List<ShoppingListEntry>>
    suspend fun findByBarcode(barcode: String): ShoppingListEntry?
    suspend fun findByName(name: String): ShoppingListEntry?
    suspend fun addEntry(entry: ShoppingListEntry)
    suspend fun updateEntry(entry: ShoppingListEntry)
    suspend fun deleteEntry(id: String)
    suspend fun clearAll()
}
