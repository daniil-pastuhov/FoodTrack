package com.foodtracker.android.data.local

import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.repository.ShoppingListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomShoppingListRepository(private val dao: ShoppingListDao) : ShoppingListRepository {

    override fun getEntriesFlow(): Flow<List<ShoppingListEntry>> =
        dao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override suspend fun findByBarcode(barcode: String): ShoppingListEntry? =
        dao.findByBarcode(barcode)?.toDomain()

    override suspend fun findByName(name: String): ShoppingListEntry? =
        dao.findByName(name)?.toDomain()

    override suspend fun addEntry(entry: ShoppingListEntry) = dao.upsert(entry.toEntity())

    override suspend fun updateEntry(entry: ShoppingListEntry) = dao.upsert(entry.toEntity())

    override suspend fun deleteEntry(id: String) = dao.deleteById(id)

    override suspend fun clearAll() = dao.clearAll()
}
