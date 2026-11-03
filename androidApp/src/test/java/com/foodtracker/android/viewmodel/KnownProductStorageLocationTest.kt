package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.repository.LocalProductRepository
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A scanned product the user has stored before is pre-selected in the location it was put last
 * time; the "remember last storage location" setting only decides for products the app has
 * never seen stored (see [PantryStateHolder.storageLocationForNewItem]).
 */
class KnownProductStorageLocationTest {

    /** Holds every product lookup until [release], so a test can act while one is in flight. */
    private class GatedLocalProductRepository(
        private val delegate: FakeLocalProductRepository
    ) : LocalProductRepository by delegate {
        private val gate = CompletableDeferred<Unit>()
        fun release() = gate.complete(Unit)
        override suspend fun getByBarcode(barcode: String): ProductInfo? {
            gate.await()
            return delegate.getByBarcode(barcode)
        }
    }

    private class Fixture(
        settings: AppSettings = AppSettings(),
        items: List<PantryItem> = emptyList(),
        val localProducts: FakeLocalProductRepository = FakeLocalProductRepository(),
        repositoryOverride: LocalProductRepository? = null
    ) {
        val pantry = FakePantryRepository(items)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = FakeSettingsRepository(settings),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = repositoryOverride ?: localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        fun close() = scope.cancel()
    }

    private fun item(id: String, barcode: String?, location: String, addedDaysAgo: Int = 0) = PantryItem(
        id = id,
        name = id,
        barcode = barcode,
        expiryDate = DateUtils.today().plus(DatePeriod(days = 7)),
        addedDate = DateUtils.today().plus(DatePeriod(days = -addedDaysAgo)),
        storageLocation = location
    )

    @Test
    fun knownProductGoesBackWhereItWasStoredRegardlessOfLastUsedLocation() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("ice-cream", "111", "Freezer"))
            // A different product saved afterwards moves the general "last used" location on.
            fixture.stateHolder.saveItem(item("bread", "222", "Pantry"))

            assertEquals("Freezer", fixture.stateHolder.storageLocationForNewItem("111"))
            assertEquals("Pantry", fixture.stateHolder.storageLocationForNewItem("222"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unknownProductFallsBackToTheLastUsedLocation() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("ice-cream", "111", "Freezer"))

            assertEquals("Freezer", fixture.stateHolder.storageLocationForNewItem("999"))
            assertEquals("Freezer", fixture.stateHolder.storageLocationForNewItem(null))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun knownProductWinsEvenWhenRememberLastLocationIsOff() = runBlocking {
        val fixture = Fixture(settings = AppSettings(rememberLastStorageLocation = false))
        try {
            fixture.stateHolder.saveItem(item("ice-cream", "111", "Freezer"))

            assertEquals("Freezer", fixture.stateHolder.storageLocationForNewItem("111"))
            assertEquals("Pantry", fixture.stateHolder.storageLocationForNewItem("999"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun editingAnExistingItemDoesNotTeachTheProductItsLocation() = runBlocking {
        val fixture = Fixture()
        try {
            val jam = item("jam", "333", "Pantry")
            fixture.stateHolder.saveItem(jam)
            // An opened jar moved to the fridge says nothing about where the next one goes.
            fixture.stateHolder.saveItem(jam.copy(storageLocation = "Fridge"))

            assertEquals("Pantry", fixture.localProducts.getStorageLocation("333"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun productSavedBeforeTrackingFallsBackToNewestPantryItemWithThatBarcode() = runBlocking {
        val fixture = Fixture(
            settings = AppSettings(lastUsedStorageLocation = "Pantry"),
            items = listOf(
                item("old", "444", "Fridge", addedDaysAgo = 10),
                item("new", "444", "Freezer", addedDaysAgo = 1)
            )
        )
        try {
            assertEquals("Freezer", fixture.stateHolder.storageLocationForNewItem("444"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rememberedLocationThatNoLongerExistsIsIgnored() = runBlocking {
        val fixture = Fixture(settings = AppSettings(lastUsedStorageLocation = "Fridge"))
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "555", name = "Peas"))
            fixture.localProducts.recordStorageLocation("555", "Garage")

            assertEquals("Fridge", fixture.stateHolder.storageLocationForNewItem("555"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun batchScanOfKnownProductStartsInItsRememberedLocation() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("ice-cream", "111", "Freezer"))
            fixture.stateHolder.saveItem(item("bread", "222", "Pantry"))

            fixture.stateHolder.enqueueBatchScan("111")

            val row = fixture.stateHolder.uiState.value.batchQueue.single()
            assertEquals("Freezer", row.storageLocation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun pantryFallbackSkipsANewerItemWhoseLocationNoLongerExists() = runBlocking {
        val fixture = Fixture(
            settings = AppSettings(lastUsedStorageLocation = "Pantry"),
            items = listOf(
                item("old", "444", "Fridge", addedDaysAgo = 10),
                item("new", "444", "Garage", addedDaysAgo = 1)
            )
        )
        try {
            assertEquals("Fridge", fixture.stateHolder.storageLocationForNewItem("444"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun batchRowKeepsALocationPickedWhileItsLookupWasInFlight() = runBlocking {
        val products = FakeLocalProductRepository()
        products.seed(ProductInfo(barcode = "111", name = "Ice cream"))
        products.storageLocations["111"] = "Freezer"
        val gated = GatedLocalProductRepository(products)
        val fixture = Fixture(
            settings = AppSettings(lastUsedStorageLocation = "Pantry"),
            localProducts = products,
            repositoryOverride = gated
        )
        try {
            fixture.stateHolder.enqueueBatchScan("111")
            val placeholder = fixture.stateHolder.uiState.value.batchQueue.single()
            assertEquals("Pantry", placeholder.storageLocation)

            fixture.stateHolder.updateBatchEntry(placeholder.copy(storageLocation = "Fridge"))
            gated.release()

            val row = fixture.stateHolder.uiState.value.batchQueue.single()
            assertEquals(false, row.isLookingUp)
            assertEquals("Fridge", row.storageLocation)
        } finally {
            fixture.close()
        }
    }
}
