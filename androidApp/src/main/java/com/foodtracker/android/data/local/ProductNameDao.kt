package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductNameDao {
    @Query("SELECT displayName FROM known_product_names ORDER BY lastUsedAtEpochMillis DESC LIMIT 200")
    fun getRecentNamesFlow(): Flow<List<String>>

    @Query("SELECT sortOrder FROM known_product_names WHERE normalizedName = :normalizedName")
    suspend fun getSortOrder(normalizedName: String): Double?

    /** Returns the number of rows updated, so callers can fall back to inserting a new row
     *  (via [upsert]) when this product name has no existing record yet. */
    @Query("UPDATE known_product_names SET sortOrder = :sortOrder WHERE normalizedName = :normalizedName")
    suspend fun updateSortOrder(normalizedName: String, sortOrder: Double): Int

    @Query("SELECT * FROM known_product_names WHERE normalizedName = :normalizedName")
    suspend fun find(normalizedName: String): ProductNameEntity?

    @Query("SELECT shoppingSectionId FROM known_product_names WHERE normalizedName = :normalizedName")
    suspend fun getSectionId(normalizedName: String): String?

    @Query("UPDATE known_product_names SET shoppingSectionId = :sectionId WHERE normalizedName = :normalizedName")
    suspend fun updateSectionId(normalizedName: String, sectionId: String?): Int

    @Upsert
    suspend fun upsert(entity: ProductNameEntity)

    @Query("DELETE FROM known_product_names")
    suspend fun clearAll()
}
