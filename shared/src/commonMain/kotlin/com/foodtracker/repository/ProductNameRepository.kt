package com.foodtracker.repository

import kotlinx.coroutines.flow.Flow

/** Backs the shopping list's autosuggest with every product name the user has ever typed or
 *  scanned, most-recently-used first. Persisted separately from shopping list entries and
 *  pantry items so a suggestion survives its originating entry being checked off or deleted. */
interface ProductNameRepository {
    fun getKnownNamesFlow(): Flow<List<String>>
    suspend fun recordName(name: String)

    /** The manual shopping-list position last recorded for [name] via [recordSortOrder], or
     *  null if it has never been manually reordered. Lets a shopping list entry re-enter the
     *  list at the position the user last dragged it to, rather than always appending. */
    suspend fun getSortOrder(name: String): Double?
    suspend fun recordSortOrder(name: String, sortOrder: Double)

    /** The shopping-list section [name] was last moved to via [recordSectionId], or null if it
     *  never left the default one. Lets a re-added product go back to its section. */
    suspend fun getSectionId(name: String): String?
    suspend fun recordSectionId(name: String, sectionId: String?)

    suspend fun clearAll()
}
