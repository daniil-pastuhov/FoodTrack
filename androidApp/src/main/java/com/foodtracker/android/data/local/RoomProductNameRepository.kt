package com.foodtracker.android.data.local

import com.foodtracker.repository.ProductNameRepository
import kotlinx.coroutines.flow.Flow

class RoomProductNameRepository(private val dao: ProductNameDao) : ProductNameRepository {

    override fun getKnownNamesFlow(): Flow<List<String>> = dao.getRecentNamesFlow()

    override suspend fun recordName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val normalized = trimmed.lowercase()
        // Preserve any manually-set sortOrder — this upsert runs on every scan/save of a
        // product name, not just the first time, and would otherwise silently reset the
        // remembered position to null each time.
        val existingSortOrder = dao.getSortOrder(normalized)
        dao.upsert(
            ProductNameEntity(
                normalizedName = normalized,
                displayName = trimmed,
                lastUsedAtEpochMillis = System.currentTimeMillis(),
                sortOrder = existingSortOrder
            )
        )
    }

    override suspend fun getSortOrder(name: String): Double? =
        dao.getSortOrder(name.trim().lowercase())

    override suspend fun recordSortOrder(name: String, sortOrder: Double) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val normalized = trimmed.lowercase()
        val rowsUpdated = dao.updateSortOrder(normalized, sortOrder)
        if (rowsUpdated == 0) {
            dao.upsert(
                ProductNameEntity(
                    normalizedName = normalized,
                    displayName = trimmed,
                    lastUsedAtEpochMillis = System.currentTimeMillis(),
                    sortOrder = sortOrder
                )
            )
        }
    }
}
