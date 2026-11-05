package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.api.ProductLookupResult
import com.foodtracker.model.MAX_ITEM_QUANTITY
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.repository.LocalProductRepository
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.BatchScanEntry
import com.foodtracker.viewmodel.PantryStateHolder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** Undo on the scanner's queue, the Check date mark, and Try again on an offline result. */
class ScannerQueueUndoTest {

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /** Holds the first lookup until released, for a row removed while it is still looking up. */
    private class GatedProducts(private val delegate: FakeLocalProductRepository) :
        LocalProductRepository by delegate {
        val gate = CompletableDeferred<Unit>()
        override suspend fun getByBarcode(barcode: String): ProductInfo? {
            gate.await()
            return delegate.getByBarcode(barcode)
        }
    }

    private inner class Fixture(gated: Boolean = false) {
        val localProducts = FakeLocalProductRepository()
        val gatedProducts = if (gated) GatedProducts(localProducts) else null
        val stateHolder = PantryStateHolder(
            repository = FakePantryRepository(emptyList()),
            settingsRepository = FakeSettingsRepository(),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = gatedProducts ?: localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            openFoodFactsApi = OpenFoodFactsApi(HttpClient(MockEngine { throw IOException("no network") })),
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )
        val state get() = stateHolder.uiState.value
        val queue get() = state.batchQueue

        fun seedAndQueue(vararg barcodes: String) = barcodes.forEach {
            localProducts.seed(ProductInfo(barcode = it, name = "Product $it"))
            stateHolder.enqueueBatchScan(it)
        }

        /** The mock engine answers on its own thread, so a lookup finishes after the call. */
        fun awaitLookups() = runBlocking {
            withTimeout(5_000) {
                stateHolder.uiState.first { state ->
                    !state.isLookingUpBarcode && state.batchQueue.none { it.isLookingUp }
                }
            }
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun entry(id: String, source: ShelfLifeSource, lookingUp: Boolean = false) = BatchScanEntry(
        id = id,
        name = id,
        expiryDate = DateUtils.today(),
        storageLocation = "Fridge",
        expirySource = source,
        isLookingUp = lookingUp
    )

    @Test
    fun testUndoPutsARemovedRowBackWhereItWas() {
        val fixture = Fixture()
        fixture.seedAndQueue("1", "2", "3")
        val middle = fixture.queue[1]

        fixture.stateHolder.removeBatchEntry(middle.id)
        fixture.stateHolder.restoreBatchEntry(middle, 1)

        assertEquals(listOf("1", "2", "3"), fixture.queue.map { it.id })
    }

    @Test
    fun testUndoCopesWithTheQueueHavingShrunkSince() {
        val fixture = Fixture()
        fixture.seedAndQueue("1", "2", "3")
        val last = fixture.queue[2]

        fixture.stateHolder.removeBatchEntry(last.id)
        fixture.stateHolder.removeBatchEntry("1")
        fixture.stateHolder.restoreBatchEntry(last, 2)

        assertEquals(listOf("2", "3"), fixture.queue.map { it.id })
    }

    @Test
    fun testUndoAddsToARowScannedAgainInTheMeantime() {
        val fixture = Fixture()
        fixture.seedAndQueue("1")
        fixture.stateHolder.enqueueBatchScan("1")
        val twice = fixture.queue.single()

        fixture.stateHolder.removeBatchEntry(twice.id)
        fixture.stateHolder.enqueueBatchScan("1")
        fixture.stateHolder.restoreBatchEntry(twice, 0)

        assertEquals(3, fixture.queue.single().quantity)
    }

    @Test
    fun testUndoNeverTakesARowPastTheQuantityLimit() {
        val fixture = Fixture()
        fixture.seedAndQueue("1")
        val full = fixture.queue.single().copy(quantity = MAX_ITEM_QUANTITY)

        fixture.stateHolder.restoreBatchEntry(full, 0)

        assertEquals(MAX_ITEM_QUANTITY, fixture.queue.single().quantity)
    }

    @Test
    fun testARowRemovedMidLookupFinishesAfterUndo() {
        val fixture = Fixture(gated = true)
        fixture.localProducts.seed(ProductInfo(barcode = "1", name = "Milk"))
        fixture.stateHolder.enqueueBatchScan("1")
        fixture.stateHolder.updateBatchEntry(fixture.queue.single().copy(quantity = 4))
        val looking = fixture.queue.single()
        assertTrue(looking.isLookingUp)

        fixture.stateHolder.removeBatchEntry(looking.id)
        fixture.gatedProducts!!.gate.complete(Unit)
        fixture.stateHolder.restoreBatchEntry(looking, 0)
        fixture.awaitLookups()

        val restored = fixture.queue.single()
        assertEquals("Milk", restored.name)
        assertEquals(4, restored.quantity)
        assertFalse(restored.isLookingUp)
    }

    @Test
    fun testUndoingClearBringsTheQueueBackInOrderAheadOfNewScans() {
        val fixture = Fixture()
        fixture.seedAndQueue("1", "2")
        val cleared = fixture.queue

        fixture.stateHolder.clearBatchQueue()
        fixture.seedAndQueue("3")
        fixture.stateHolder.restoreBatchQueue(cleared)

        assertEquals(listOf("1", "2", "3"), fixture.queue.map { it.id })
    }

    @Test
    fun testCheckDateMarksGuessesButNotKnownDates() {
        assertTrue(entry("1", ShelfLifeSource.FALLBACK).showsCheckDate)
        assertFalse(entry("1", ShelfLifeSource.DETECTED).showsCheckDate)
        assertFalse(entry("1", ShelfLifeSource.REMEMBERED).showsCheckDate)
        assertFalse(entry("1", ShelfLifeSource.CATEGORY_DEFAULT).showsCheckDate)
    }

    @Test
    fun testEveryRowFromAReceiptAsksForItsDateToBeChecked() {
        assertTrue(entry("receipt_1", ShelfLifeSource.CATEGORY_DEFAULT).showsCheckDate)
        assertTrue(entry("receipt_1", ShelfLifeSource.REMEMBERED).showsCheckDate)
    }

    @Test
    fun testARealReceiptRowCarriesTheMark() {
        val fixture = Fixture()

        fixture.stateHolder.enqueueReceiptLines(listOf("MILK 1.50"))

        assertTrue(fixture.queue.single().showsCheckDate)
    }

    @Test
    fun testARowStillLookingUpHasNoMarkYet() {
        assertFalse(entry("1", ShelfLifeSource.FALLBACK, lookingUp = true).showsCheckDate)
    }

    @Test
    fun testTryAgainLooksTheHeldBarcodeUpAgain() {
        val fixture = Fixture()
        fixture.stateHolder.onBarcodeScanned("123")
        fixture.awaitLookups()
        assertEquals(ProductLookupResult.Offline, fixture.state.lookupResult)
        // Back online, as far as this barcode goes: it now resolves from the device.
        fixture.localProducts.seed(ProductInfo(barcode = "123", name = "Oats"))

        val retried = fixture.stateHolder.retryBarcodeLookup()
        fixture.awaitLookups()

        assertEquals("123", retried)
        assertEquals("Oats", fixture.state.scannedProduct?.name)
        assertNull(fixture.state.productNotFoundBarcode)
    }

    @Test
    fun testTryAgainWithNothingHeldDoesNothing() {
        val fixture = Fixture()

        assertNull(fixture.stateHolder.retryBarcodeLookup())
        assertFalse(fixture.state.isLookingUpBarcode)
    }
}
