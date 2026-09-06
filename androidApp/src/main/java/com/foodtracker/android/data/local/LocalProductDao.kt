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
}
