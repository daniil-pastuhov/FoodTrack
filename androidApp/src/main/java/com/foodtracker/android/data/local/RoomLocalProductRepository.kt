package com.foodtracker.android.data.local

import com.foodtracker.model.ProductInfo
import com.foodtracker.repository.LocalProductRepository

class RoomLocalProductRepository(private val dao: LocalProductDao) : LocalProductRepository {

    override suspend fun getByBarcode(barcode: String): ProductInfo? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        return dao.getByBarcode(trimmed)?.let {
            ProductInfo(
                barcode = it.barcode,
                name = it.name,
                brand = it.brand,
                imageUrl = it.imageUrl,
                category = it.category
            )
        }
    }

    override suspend fun saveProduct(product: ProductInfo) {
        val trimmed = product.barcode.trim()
        if (trimmed.isEmpty()) return
        // ProductInfo carries no shelf life (it doubles as the Open Food Facts DTO), so an
        // upsert built from one would reset an already-learned value to null. Carry the stored
        // one across rather than letting a plain re-scan forget it.
        val existingShelfLife = dao.getShelfLifeDays(trimmed)
        dao.upsert(
            LocalProductEntity(
                barcode = trimmed,
                name = product.name,
                brand = product.brand,
                imageUrl = product.imageUrl,
                category = product.category,
                shelfLifeDays = existingShelfLife
            )
        )
    }

    override suspend fun getShelfLifeDays(barcode: String): Int? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        return dao.getShelfLifeDays(trimmed)
    }

    override suspend fun recordShelfLifeDays(barcode: String, days: Int) {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return
        dao.updateShelfLifeDays(trimmed, days)
    }
}
