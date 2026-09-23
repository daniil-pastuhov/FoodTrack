package com.foodtracker.android.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LocalProductDao {
    @Query("SELECT * FROM local_products WHERE barcode = :barcode")
    suspend fun getByBarcode(barcode: String): LocalProductEntity?

    @Upsert
    suspend fun upsert(entity: LocalProductEntity)

    @Query("SELECT shelfLifeDays FROM local_products WHERE barcode = :barcode")
    suspend fun getShelfLifeDays(barcode: String): Int?

    /** A targeted UPDATE rather than an upsert of a whole entity: the caller knows the shelf
     *  life but not necessarily the product's current name/brand/image, and an upsert built
     *  from partial data would overwrite them. A barcode with no cached row is a no-op. */
    @Query("UPDATE local_products SET shelfLifeDays = :days WHERE barcode = :barcode")
    suspend fun updateShelfLifeDays(barcode: String, days: Int)

    @Query("SELECT * FROM local_products")
    suspend fun getAll(): List<LocalProductEntity>

    /** Room runs a multi-entity @Upsert in one transaction, so restoring a backup's whole
     *  product cache fires the invalidation tracker once rather than once per row. */
    @Upsert
    suspend fun upsertAll(entities: List<LocalProductEntity>)

    @Query("DELETE FROM local_products")
    suspend fun clearAll()
}
