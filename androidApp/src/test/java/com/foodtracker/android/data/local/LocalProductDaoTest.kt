package com.foodtracker.android.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ProductNutrition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the shelf-life half of the local product cache, which backs the smart default expiry
 * date (see `ShelfLife`). The interesting cases are all about the shelf life surviving the
 * *other* writes this table takes, since `ProductInfo` - the currency of `saveProduct`, and
 * also the Open Food Facts DTO - carries no shelf life of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalProductDaoTest {

    private lateinit var db: FoodTrackerDatabase
    private lateinit var repo: RoomLocalProductRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FoodTrackerDatabase::class.java
        ).allowMainThreadQueries().build()
        repo = RoomLocalProductRepository(db.localProductDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun product(name: String = "Yoghurt", brand: String? = "Tine") = ProductInfo(
        barcode = "7038010000000",
        name = name,
        brand = brand,
        imageUrl = "https://example.invalid/front.png",
        category = "Dairy"
    )

    @Test
    fun recordingAShelfLifeLeavesTheCachedProductDetailsAlone() = runTest {
        repo.saveProduct(product())
        repo.recordShelfLifeDays("7038010000000", 14)

        val cached = repo.getByBarcode("7038010000000")
        assertEquals("Yoghurt", cached?.name)
        assertEquals("Tine", cached?.brand)
        assertEquals("https://example.invalid/front.png", cached?.imageUrl)
        assertEquals("Dairy", cached?.category)
        assertEquals(14, repo.getShelfLifeDays("7038010000000"))
    }

    @Test
    fun rescanningAProductKeepsItsRememberedShelfLife() = runTest {
        repo.saveProduct(product())
        repo.recordShelfLifeDays("7038010000000", 14)

        // A later scan re-caches the product from an Open Food Facts response, which has no
        // shelf life on it. Upserting that verbatim would forget what the user taught the app.
        repo.saveProduct(product(name = "Yoghurt Natural", brand = "Tine Meierier"))

        assertEquals("Yoghurt Natural", repo.getByBarcode("7038010000000")?.name)
        assertEquals(14, repo.getShelfLifeDays("7038010000000"))
    }

    @Test
    fun recordingAShelfLifeForAnUncachedBarcodeIsANoOp() = runTest {
        // The targeted UPDATE must not conjure a half-populated row: nothing downstream could
        // tell it apart from a real cached product, and it would show up as a nameless hit.
        repo.recordShelfLifeDays("0000000000000", 9)

        assertNull(repo.getByBarcode("0000000000000"))
        assertNull(repo.getShelfLifeDays("0000000000000"))
    }

    @Test
    fun aProductNeverAddedHasNoShelfLifeYet() = runTest {
        repo.saveProduct(product())
        assertNull(repo.getShelfLifeDays("7038010000000"))
    }

    @Test
    fun aLaterPurchaseReplacesTheRememberedShelfLife() = runTest {
        repo.saveProduct(product())
        repo.recordShelfLifeDays("7038010000000", 14)
        repo.recordShelfLifeDays("7038010000000", 5)

        assertEquals(5, repo.getShelfLifeDays("7038010000000"))
    }

    @Test
    fun barcodesAreTrimmedOnEveryPath() = runTest {
        repo.saveProduct(product())
        repo.recordShelfLifeDays("  7038010000000  ", 21)

        assertEquals(21, repo.getShelfLifeDays(" 7038010000000 "))
    }

    @Test
    fun recordingAStorageLocationLeavesTheCachedProductAndShelfLifeAlone() = runTest {
        repo.saveProduct(product())
        repo.recordShelfLifeDays("7038010000000", 14)
        repo.recordStorageLocation("7038010000000", "Freezer")

        assertEquals("Freezer", repo.getStorageLocation("7038010000000"))
        assertEquals(14, repo.getShelfLifeDays("7038010000000"))
        assertEquals("Yoghurt", repo.getByBarcode("7038010000000")?.name)
    }

    @Test
    fun rescanningAProductKeepsItsRememberedStorageLocation() = runTest {
        repo.saveProduct(product())
        repo.recordStorageLocation("7038010000000", "Freezer")

        // Same hazard as the shelf life: ProductInfo carries no location, so a verbatim upsert
        // on re-scan would forget where the user puts this product.
        repo.saveProduct(product(name = "Yoghurt Natural"))

        assertEquals("Freezer", repo.getStorageLocation("7038010000000"))
    }

    @Test
    fun recordingAStorageLocationForAnUncachedBarcodeIsANoOp() = runTest {
        repo.recordStorageLocation("0000000000000", "Freezer")

        assertNull(repo.getByBarcode("0000000000000"))
        assertNull(repo.getStorageLocation("0000000000000"))
    }

    @Test
    fun storageLocationBarcodesAreTrimmed() = runTest {
        repo.saveProduct(product())
        repo.recordStorageLocation("  7038010000000  ", "Fridge")

        assertEquals("Fridge", repo.getStorageLocation(" 7038010000000 "))
    }

    @Test
    fun storageLocationSurvivesTheBackupReadAndWritePaths() = runTest {
        repo.saveProduct(product())
        repo.recordStorageLocation("7038010000000", "Freezer")
        val exported = repo.getAll()
        assertEquals("Freezer", exported.single().storageLocation)

        repo.clearAll()
        repo.saveAll(exported)

        assertEquals("Freezer", repo.getStorageLocation("7038010000000"))
    }

    @Test
    fun storageLocationIsScopedToItsOwnBarcode() = runTest {
        repo.saveProduct(product())
        repo.saveProduct(product().copy(barcode = "7038010000001", name = "Skyr"))
        repo.saveProduct(product().copy(barcode = "7038010000002", name = "Kefir"))
        repo.recordStorageLocation("7038010000002", "Fridge")

        repo.recordStorageLocation("7038010000001", "Freezer")

        assertEquals("Freezer", repo.getStorageLocation("7038010000001"))
        assertNull(repo.getStorageLocation("7038010000000"))
        assertEquals("Fridge", repo.getStorageLocation("7038010000002"))
        // A barcode with no cached row must not pick up a neighbouring product's location
        // (this one sorts between ...000 and ...001).
        assertNull(repo.getStorageLocation("70380100000005"))
    }

    @Test
    fun resavingWithoutNutritionKeepsTheCachedNutrition() = runTest {
        repo.saveProduct(product().copy(nutrition = ProductNutrition(nutriScore = "b", allergens = listOf("Milk"))))

        // A manual save carries no nutrition; it must not blank what a lookup cached.
        repo.saveProduct(product(name = "Yoghurt Natural"))

        val cached = repo.getByBarcode("7038010000000")
        assertEquals("b", cached?.nutrition?.nutriScore)
        assertEquals(listOf("Milk"), cached?.nutrition?.allergens)
    }
}
