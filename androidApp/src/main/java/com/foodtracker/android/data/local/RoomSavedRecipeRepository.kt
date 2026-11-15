package com.foodtracker.android.data.local

import com.foodtracker.recipe.SavedRecipe
import com.foodtracker.recipe.SavedRecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomSavedRecipeRepository(private val dao: SavedRecipeDao) : SavedRecipeRepository {

    /** Payloads are decoded off the collector's thread: a menu is several recipes of JSON. */
    override fun getAllFlow(): Flow<List<SavedRecipe>> =
        dao.getAllFlow()
            .map { rows -> rows.mapNotNull { it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

    override suspend fun save(recipe: SavedRecipe) = dao.upsert(recipe.toEntity())

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun clearAll() = dao.clear()
}
