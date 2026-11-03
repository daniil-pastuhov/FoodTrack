package com.foodtracker.repository

import com.foodtracker.model.ShoppingSection
import kotlinx.coroutines.flow.Flow

/** The user's shopping-list sections (see [ShoppingSection]). Implementations guarantee the
 *  default section exists: it is never returned missing and [delete] refuses to remove it. */
interface ShoppingSectionRepository {
    fun getSectionsFlow(): Flow<List<ShoppingSection>>
    suspend fun getAll(): List<ShoppingSection>
    suspend fun getMaxSortOrder(): Double?
    suspend fun upsert(section: ShoppingSection)
    suspend fun upsertAll(sections: List<ShoppingSection>)
    suspend fun setCollapsed(id: String, collapsed: Boolean)

    /** Removes the section and moves its entries to the default section, in one transaction so
     *  no entry is ever left pointing at a section that is gone. */
    suspend fun delete(id: String)

    /** Removes every user section, leaving only the default one. */
    suspend fun clearAll()
}
