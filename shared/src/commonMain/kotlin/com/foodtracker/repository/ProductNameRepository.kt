package com.foodtracker.repository

import kotlinx.coroutines.flow.Flow

/** Backs the shopping list's autosuggest with every product name the user has ever typed or
 *  scanned, most-recently-used first. Persisted separately from shopping list entries and
 *  pantry items so a suggestion survives its originating entry being checked off or deleted. */
interface ProductNameRepository {
    fun getKnownNamesFlow(): Flow<List<String>>
    suspend fun recordName(name: String)
}
