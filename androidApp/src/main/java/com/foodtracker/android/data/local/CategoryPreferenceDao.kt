package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryPreferenceDao {
    @Query("SELECT * FROM category_preferences ORDER BY sortOrder ASC, displayName ASC")
    fun getAllFlow(): Flow<List<CategoryPreferenceEntity>>

    @Query("SELECT MAX(sortOrder) FROM category_preferences")
    suspend fun getMaxSortOrder(): Double?

    // IGNORE means an origin category that's already known keeps its existing row (name/order/
    // enabled untouched) rather than being reset back to its raw scanned value.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: CategoryPreferenceEntity)

    @Upsert
    suspend fun upsert(entity: CategoryPreferenceEntity)

    /** Room runs a multi-entity @Upsert inside a single transaction, so the invalidation
     *  tracker fires once for the whole batch rather than once per row. */
    @Upsert
    suspend fun upsertAll(entities: List<CategoryPreferenceEntity>)
}
