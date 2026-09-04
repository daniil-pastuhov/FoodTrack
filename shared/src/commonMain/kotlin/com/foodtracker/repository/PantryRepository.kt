package com.foodtracker.repository

import com.foodtracker.model.PantryItem
import kotlinx.coroutines.flow.Flow

interface PantryRepository {
    fun getItemsFlow(): Flow<List<PantryItem>>
    suspend fun getItem(id: String): PantryItem?
    suspend fun saveItem(item: PantryItem)
    suspend fun deleteItem(id: String)
    suspend fun clearAll()
}
