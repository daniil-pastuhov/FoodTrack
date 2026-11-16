package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StapleDao {
    @Query("SELECT * FROM staples ORDER BY addedAtEpochMillis ASC, id ASC")
    fun getAllFlow(): Flow<List<StapleEntity>>

    @Query("SELECT * FROM staples ORDER BY addedAtEpochMillis ASC, id ASC")
    suspend fun getAll(): List<StapleEntity>

    @Upsert
    suspend fun upsert(entity: StapleEntity)

    @Upsert
    suspend fun upsertAll(entities: List<StapleEntity>)

    @Query("DELETE FROM staples WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM staples")
    suspend fun clearAll()
}
