package com.foodtracker.repository

import com.foodtracker.model.CategoryPreference
import kotlinx.coroutines.flow.Flow

interface CategoryPreferenceRepository {
    fun getPreferencesFlow(): Flow<List<CategoryPreference>>

    /** Highest sort order in use, **excluding** the [UNCATEGORIZED_ORIGIN] sentinel. That one is
     *  parked at [Double.MAX_VALUE] so the "Other" bucket sorts last, and counting it here would
     *  make the next order `MAX_VALUE + 1.0` — which in Double arithmetic is `MAX_VALUE` again,
     *  so every newly registered category would land on that same order and the list would fall
     *  back to ordering by display name. */
    suspend fun getMaxSortOrder(): Double?

    /** No-op if [originName] is already known - used to auto-register a category the first
     *  time it's seen on a scanned or manually added product. [displayName] seeds the initial
     *  display name (defaults to [originName]) - used for [UNCATEGORIZED_ORIGIN], whose raw
     *  value is an empty string and needs a real, localized default label instead. */
    suspend fun registerIfAbsent(originName: String, sortOrder: Double, displayName: String = originName)

    suspend fun updateEntry(preference: CategoryPreference)

    /** Writes several preferences at once. Toggling a category, and especially the "all
     *  categories" switch, updates every origin sharing a display name — as individual writes
     *  that is one transaction and one flow re-emission per preference, each of which re-runs
     *  the whole downstream derivation. */
    suspend fun updateEntries(preferences: List<CategoryPreference>)

    /** Inserts or replaces [preferences] wholesale, for a backup restore. Unlike
     *  [registerIfAbsent] this overwrites an existing row, since the backup's version of a
     *  category is the one being restored. */
    suspend fun upsertAll(preferences: List<CategoryPreference>)

    suspend fun clearAll()
}
