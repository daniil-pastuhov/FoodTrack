package com.foodtracker.repository

import com.foodtracker.model.PantryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class InMemoryPantryRepository(
    initialItems: List<PantryItem> = emptyList()
) : PantryRepository {

    private val itemsFlow = MutableStateFlow(initialItems)

    override fun getItemsFlow(): Flow<List<PantryItem>> = itemsFlow.asStateFlow()

    override suspend fun getItem(id: String): PantryItem? {
        return itemsFlow.value.firstOrNull { it.id == id }
    }

    override suspend fun saveItem(item: PantryItem) {
        val current = itemsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            current[index] = item
        } else {
            current.add(0, item)
        }
        itemsFlow.value = current
    }

    override suspend fun deleteItem(id: String) {
        itemsFlow.value = itemsFlow.value.filterNot { it.id == id }
    }

    override suspend fun clearAll() {
        itemsFlow.value = emptyList()
    }
}
