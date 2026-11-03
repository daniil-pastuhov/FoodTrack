package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.foodtracker.model.DEFAULT_SHOPPING_SECTION_ID
import kotlinx.coroutines.flow.Flow

@Dao
interface ShoppingSectionDao {
    @Query("SELECT * FROM shopping_sections ORDER BY sortOrder ASC, name ASC")
    fun getAllFlow(): Flow<List<ShoppingSectionEntity>>

    @Query("SELECT * FROM shopping_sections ORDER BY sortOrder ASC, name ASC")
    suspend fun getAll(): List<ShoppingSectionEntity>

    @Query("SELECT MAX(sortOrder) FROM shopping_sections WHERE id != '$DEFAULT_SHOPPING_SECTION_ID'")
    suspend fun getMaxSortOrder(): Double?

    @Upsert
    suspend fun upsert(section: ShoppingSectionEntity)

    @Upsert
    suspend fun upsertAll(sections: List<ShoppingSectionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(section: ShoppingSectionEntity)

    @Query("UPDATE shopping_sections SET collapsed = :collapsed WHERE id = :id")
    suspend fun updateCollapsed(id: String, collapsed: Boolean)

    @Query("UPDATE shopping_list_entries SET sectionId = NULL WHERE sectionId = :id")
    suspend fun moveEntriesToDefault(id: String)

    @Query("DELETE FROM shopping_sections WHERE id = :id AND id != '$DEFAULT_SHOPPING_SECTION_ID'")
    suspend fun deleteById(id: String)

    /** Deleting a section and re-homing its entries happen together, so the list is never
     *  observed with entries pointing at a section that's gone. */
    @Transaction
    suspend fun deleteAndMoveEntriesToDefault(id: String) {
        moveEntriesToDefault(id)
        deleteById(id)
    }

    @Query("DELETE FROM shopping_sections WHERE id != '$DEFAULT_SHOPPING_SECTION_ID'")
    suspend fun clearUserSections()
}
