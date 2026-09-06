package com.foodtracker.android.data.local

import com.foodtracker.model.CategoryPreference
import com.foodtracker.repository.CategoryPreferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomCategoryPreferenceRepository(private val dao: CategoryPreferenceDao) : CategoryPreferenceRepository {

    override fun getPreferencesFlow(): Flow<List<CategoryPreference>> =
        dao.getAllFlow().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getMaxSortOrder(): Double? = dao.getMaxSortOrder()

    override suspend fun registerIfAbsent(originName: String, sortOrder: Double) =
        dao.insertIfAbsent(CategoryPreferenceEntity(originName = originName, displayName = originName, sortOrder = sortOrder))

    override suspend fun updateEntry(preference: CategoryPreference) = dao.upsert(preference.toEntity())
}
