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
        dao.upsert(
            LocalProductEntity(
                barcode = trimmed,
                name = product.name,
                brand = product.brand,
                imageUrl = product.imageUrl,
                category = product.category
            )
        )
    }
}
