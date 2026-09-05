package com.foodtracker.android.data.local

import com.foodtracker.repository.ProductNameRepository
import kotlinx.coroutines.flow.Flow

class RoomProductNameRepository(private val dao: ProductNameDao) : ProductNameRepository {

    override fun getKnownNamesFlow(): Flow<List<String>> = dao.getRecentNamesFlow()

    override suspend fun recordName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        dao.upsert(
            ProductNameEntity(
                normalizedName = trimmed.lowercase(),
                displayName = trimmed,
                lastUsedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }
}
