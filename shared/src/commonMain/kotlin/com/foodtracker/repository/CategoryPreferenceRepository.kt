package com.foodtracker.repository

import com.foodtracker.model.CategoryPreference
import kotlinx.coroutines.flow.Flow

interface CategoryPreferenceRepository {
    fun getPreferencesFlow(): Flow<List<CategoryPreference>>
    suspend fun getMaxSortOrder(): Double?

    /** No-op if [originName] is already known - used to auto-register a category the first
     *  time it's seen on a scanned or manually added product. [displayName] seeds the initial
     *  display name (defaults to [originName]) - used for [UNCATEGORIZED_ORIGIN], whose raw
     *  value is an empty string and needs a real, localized default label instead. */
    suspend fun registerIfAbsent(originName: String, sortOrder: Double, displayName: String = originName)

    suspend fun updateEntry(preference: CategoryPreference)
}
