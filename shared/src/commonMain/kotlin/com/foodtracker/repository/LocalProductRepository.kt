package com.foodtracker.repository

import com.foodtracker.model.CachedProduct
import com.foodtracker.model.ProductInfo

/** Caches product info keyed by barcode — both a successful Open Food Facts lookup and a
 *  barcode the API doesn't know but the user filled in by hand — so re-scanning the same
 *  barcode prefetches it from here instead of hitting the network again. This is what makes a
 *  once-manually-entered "custom" product (OFF has no record of it) recognized on a later scan. */
interface LocalProductRepository {
    suspend fun getByBarcode(barcode: String): ProductInfo?
    suspend fun saveProduct(product: ProductInfo)

    /** The shelf life, in days, this barcode was last given (see [recordShelfLifeDays]), or
     *  null if it has never been added or the span wasn't worth learning. Used to pre-fill the
     *  expiry date on a re-scan. */
    suspend fun getShelfLifeDays(barcode: String): Int?

    /** Records how long this barcode's product keeps, learned from an item the user just
     *  added. Deliberately narrower than [saveProduct]: it must leave the cached
     *  name/brand/image/category alone, since the caller may know the shelf life without
     *  holding a full, current [ProductInfo]. A barcode with no cached product yet is a
     *  no-op - [saveProduct] always runs first on the save path that calls this. */
    suspend fun recordShelfLifeDays(barcode: String, days: Int)

    /** The storage location this barcode was last added to (see [recordStorageLocation]), or
     *  null if it has never been added since this was tracked. */
    suspend fun getStorageLocation(barcode: String): String?

    /** Records where this barcode's product was just put away, so a re-scan pre-selects the
     *  same location. Same contract as [recordShelfLifeDays]: leaves the rest of the cached row
     *  alone, and is a no-op for a barcode with no cached product yet. */
    suspend fun recordStorageLocation(barcode: String, location: String)

    /** Every cached product, shelf lives included - the whole table, for a backup. */
    suspend fun getAll(): List<CachedProduct>

    /** Writes [products] wholesale, shelf lives included. Used when restoring a backup, which
     *  is the only place a shelf life is set without an item having just been added. */
    suspend fun saveAll(products: List<CachedProduct>)

    suspend fun clearAll()
}
