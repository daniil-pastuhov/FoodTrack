package com.foodtracker.android.data.local

import com.foodtracker.model.PantryItem
import com.foodtracker.repository.PantryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomPantryRepository(private val dao: PantryDao) : PantryRepository {

    override fun getItemsFlow(): Flow<List<PantryItem>> =
        dao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getItem(id: String): PantryItem? = dao.getById(id)?.toDomain()

    override suspend fun saveItem(item: PantryItem) = dao.upsert(item.toEntity())

    override suspend fun deleteItem(id: String) = dao.deleteById(id)

    override suspend fun clearAll() = dao.clearAll()
}
