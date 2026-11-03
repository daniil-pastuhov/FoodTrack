package com.foodtracker.android.data.local

import com.foodtracker.model.CachedProduct
import com.foodtracker.model.ProductNutrition
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
                category = it.category,
                nutrition = it.toNutrition()
            )
        }
    }

    override suspend fun saveProduct(product: ProductInfo) {
        val trimmed = product.barcode.trim()
        if (trimmed.isEmpty()) return
        // ProductInfo carries no shelf life (it doubles as the Open Food Facts DTO), so an
        // upsert built from one would reset an already-learned value to null. Carry the stored
        // one across rather than letting a plain re-scan forget it.
        val existing = dao.getByBarcode(trimmed)
        // A manually-entered product carries no nutrition, and neither does a lookup OFF has
        // nothing for. Keep whatever a previous successful lookup cached rather than blanking
        // it - losing a cached allergen list is the one outcome worth avoiding here.
        val nutrition = product.nutrition.takeIf { !it.isEmpty }
            ?: existing?.toNutrition()
            ?: ProductNutrition()
        dao.upsert(
            LocalProductEntity(
                barcode = trimmed,
                name = product.name,
                brand = product.brand,
                imageUrl = product.imageUrl,
                category = product.category,
                // Same for the remembered storage location: learned, not part of ProductInfo.
                shelfLifeDays = existing?.shelfLifeDays,
                storageLocation = existing?.storageLocation,
                nutriScore = nutrition.nutriScore,
                novaGroup = nutrition.novaGroup,
                allergens = nutrition.allergens.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                ingredients = nutrition.ingredients
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

    override suspend fun getStorageLocation(barcode: String): String? {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return null
        return dao.getStorageLocation(trimmed)
    }

    override suspend fun recordStorageLocation(barcode: String, location: String) {
        val trimmed = barcode.trim()
        if (trimmed.isEmpty()) return
        dao.updateStorageLocation(trimmed, location)
    }

    override suspend fun getAll(): List<CachedProduct> = dao.getAll().map {
        CachedProduct(
            barcode = it.barcode,
            name = it.name,
            brand = it.brand,
            imageUrl = it.imageUrl,
            category = it.category,
            shelfLifeDays = it.shelfLifeDays,
            storageLocation = it.storageLocation,
            nutrition = it.toNutrition()
        )
    }

    override suspend fun saveAll(products: List<CachedProduct>) {
        if (products.isEmpty()) return
        dao.upsertAll(
            products.mapNotNull { product ->
                val trimmed = product.barcode.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                LocalProductEntity(
                    barcode = trimmed,
                    name = product.name,
                    brand = product.brand,
                    imageUrl = product.imageUrl,
                    category = product.category,
                    shelfLifeDays = product.shelfLifeDays,
                    storageLocation = product.storageLocation,
                    nutriScore = product.nutrition.nutriScore,
                    novaGroup = product.nutrition.novaGroup,
                    allergens = product.nutrition.allergens.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                    ingredients = product.nutrition.ingredients
                )
            }
        )
    }

    override suspend fun clearAll() = dao.clearAll()
}

/** Splits the delimited allergen column back into a list. See [LocalProductEntity.allergens]. */
private fun LocalProductEntity.toNutrition(): ProductNutrition = ProductNutrition(
    nutriScore = nutriScore,
    novaGroup = novaGroup,
    allergens = allergens?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
    ingredients = ingredients
)
