package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedRecipeDao {
    @Query("SELECT * FROM saved_recipes ORDER BY createdAtEpochMillis DESC, id DESC")
    fun getAllFlow(): Flow<List<SavedRecipeEntity>>

    @Query("SELECT * FROM saved_recipes WHERE id = :id")
    suspend fun getById(id: String): SavedRecipeEntity?

    @Upsert
    suspend fun upsert(recipe: SavedRecipeEntity)

    @Query("DELETE FROM saved_recipes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE saved_recipes SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Upsert
    suspend fun upsertAll(recipes: List<SavedRecipeEntity>)

    @Query("DELETE FROM saved_recipes WHERE id IN (:ids)")
    suspend fun deleteChunkByIds(ids: List<String>)

    /** Saved recipes' multi-select Delete. One transaction, so the list changes once however many
     *  chunks it takes (SQLite allows 999 bound values per statement on older Androids). */
    @Transaction
    suspend fun deleteByIds(ids: List<String>) {
        ids.chunked(MAX_IDS_PER_QUERY).forEach { deleteChunkByIds(it) }
    }

    @Query("DELETE FROM saved_recipes")
    suspend fun clear()
}
