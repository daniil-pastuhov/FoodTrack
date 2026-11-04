package com.foodtracker.android.data.local

import com.foodtracker.model.PantryItem
import com.foodtracker.repository.PantryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomPantryRepository(private val dao: PantryDao) : PantryRepository {

    /**
     * `flowOn(IO)` puts the entity→domain mapping (which allocates a `PantryItem` and two
     * `LocalDate`s per row) on an IO thread rather than the collector's. `distinctUntilChanged`
     * drops re-emissions whose contents are identical — Room's invalidation is table-granular,
     * so touching any row re-runs this query and re-emits the whole table.
     *
     * Deliberately left cold rather than shared with `shareIn(replay = 1)`. Sharing would avoid
     * a duplicate query per subscriber, but a replayed value can predate a write that just
     * happened, and the widgets and the digest notification read this with a one-shot `first()`
     * immediately after writes — they would render stale data. The duplicate-subscriber problem
     * is solved in `WidgetUpdater` instead, by observing the state holder rather than opening a
     * second collection of this flow.
     */
    override fun getItemsFlow(): Flow<List<PantryItem>> =
        dao.getAllFlow()
            .map { entities -> entities.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun getItem(id: String): PantryItem? = dao.getById(id)?.toDomain()

    override suspend fun findByBarcode(barcode: String): List<PantryItem> {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return emptyList()
        return dao.findByBarcode(trimmed).map { it.toDomain() }
    }

    override suspend fun saveItem(item: PantryItem) = dao.upsert(item.toEntity())

    override suspend fun deleteItem(id: String) = dao.deleteById(id)

    override suspend fun getItems(ids: Collection<String>): List<PantryItem> =
        dao.getByIds(ids.toList()).map { it.toDomain() }

    override suspend fun deleteItems(ids: Collection<String>) = dao.deleteByIds(ids.toList())

    override suspend fun saveItems(items: List<PantryItem>) = dao.upsertAll(items.map { it.toEntity() })

    override suspend fun clearAll() = dao.clearAll()
}
