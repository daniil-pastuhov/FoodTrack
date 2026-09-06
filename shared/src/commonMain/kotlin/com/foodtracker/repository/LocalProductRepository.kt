package com.foodtracker.repository

import com.foodtracker.model.ProductInfo

/** Caches product info keyed by barcode — both a successful Open Food Facts lookup and a
 *  barcode the API doesn't know but the user filled in by hand — so re-scanning the same
 *  barcode prefetches it from here instead of hitting the network again. This is what makes a
 *  once-manually-entered "custom" product (OFF has no record of it) recognized on a later scan. */
interface LocalProductRepository {
    suspend fun getByBarcode(barcode: String): ProductInfo?
    suspend fun saveProduct(product: ProductInfo)
}
