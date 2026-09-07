package com.foodtracker.android.data.local

import com.foodtracker.model.CategoryPreference
import com.foodtracker.repository.CategoryPreferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomCategoryPreferenceRepository(private val dao: CategoryPreferenceDao) : CategoryPreferenceRepository {

    /** Only `PantryStateHolder` observes this, so there's nothing to share — but the mapping
     *  still belongs off the collector's thread, and `distinctUntilChanged` matters here because
     *  this table is rewritten on every category toggle. */
    override fun getPreferencesFlow(): Flow<List<CategoryPreference>> =
        dao.getAllFlow()
            .map { entities -> entities.map { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun getMaxSortOrder(): Double? = dao.getMaxSortOrder()

    override suspend fun registerIfAbsent(originName: String, sortOrder: Double, displayName: String) =
        dao.insertIfAbsent(CategoryPreferenceEntity(originName = originName, displayName = displayName, sortOrder = sortOrder))

    override suspend fun updateEntry(preference: CategoryPreference) = dao.upsert(preference.toEntity())

    override suspend fun updateEntries(preferences: List<CategoryPreference>) {
        if (preferences.isEmpty()) return
        dao.upsertAll(preferences.map { it.toEntity() })
    }
}
