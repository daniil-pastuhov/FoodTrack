package com.foodtracker.android.data.local

import com.foodtracker.model.DEFAULT_SHOPPING_SECTION
import com.foodtracker.model.DEFAULT_SHOPPING_SECTION_ID
import com.foodtracker.model.ShoppingSection
import com.foodtracker.repository.ShoppingSectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * The default section's row is created by `MIGRATION_12_13` on upgrade, but a fresh install
 * starts without it. Rather than seeding it from a database callback, reads fill it in when it's
 * missing and [setCollapsed] - the only thing ever stored on it - inserts it on first use.
 */
class RoomShoppingSectionRepository(private val dao: ShoppingSectionDao) : ShoppingSectionRepository {

    override fun getSectionsFlow(): Flow<List<ShoppingSection>> =
        dao.getAllFlow()
            .map { rows -> rows.map { it.toDomain() }.withDefault() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun getAll(): List<ShoppingSection> = dao.getAll().map { it.toDomain() }.withDefault()

    override suspend fun getMaxSortOrder(): Double? = dao.getMaxSortOrder()

    override suspend fun upsert(section: ShoppingSection) = dao.upsert(section.toEntity())

    override suspend fun upsertAll(sections: List<ShoppingSection>) {
        if (sections.isEmpty()) return
        dao.upsertAll(sections.map { it.toEntity() })
    }

    override suspend fun setCollapsed(id: String, collapsed: Boolean) {
        if (id == DEFAULT_SHOPPING_SECTION_ID) dao.insertIfAbsent(DEFAULT_SHOPPING_SECTION.toEntity())
        dao.updateCollapsed(id, collapsed)
    }

    override suspend fun delete(id: String) {
        if (id == DEFAULT_SHOPPING_SECTION_ID) return
        dao.deleteAndMoveEntriesToDefault(id)
    }

    override suspend fun clearAll() = dao.clearUserSections()

    private fun List<ShoppingSection>.withDefault(): List<ShoppingSection> =
        if (any { it.isDefault }) this else listOf(DEFAULT_SHOPPING_SECTION) + this
}
