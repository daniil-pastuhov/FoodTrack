package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.model.ProductInfo
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The two strengths of scan reset, which the scanner leans on to keep one camera mode's result
 * from turning up underneath another.
 *
 * [PantryStateHolder.clearScanResults] is what switching mode calls, so it has to spare the two
 * things that outlive a single scan: the batch queue, and the shopping-list entry the scan is
 * filling in. [PantryStateHolder.clearScanState] is what leaving the scanner calls, and takes
 * both — a queue that survives the screen showing it is a queue nobody can see.
 */
class ScanStateResetTest {

    private class Fixture {
        val pantry = FakePantryRepository(emptyList())
        val localProducts = FakeLocalProductRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = FakeSettingsRepository(),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        val state get() = stateHolder.uiState.value
        fun close() = scope.cancel()
    }

    private val someDate = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 30)

    @Test
    fun testClearScanResultsDropsTheSingleScanButKeepsTheQueue() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("222")
            fixture.stateHolder.onBarcodeScanned("111")
            fixture.stateHolder.onDateOcrDetected(someDate)
            assertNotNull(fixture.state.scannedProduct)

            fixture.stateHolder.clearScanResults()

            assertNull("a product card must not survive a mode switch", fixture.state.scannedProduct)
            assertNull(fixture.state.productNotFoundBarcode)
            assertNull("an OCR'd date must not survive a mode switch", fixture.state.detectedDate)
            assertNull(fixture.state.suggestedExpiry)
            assertEquals("the queue is not one scan's result", 1, fixture.state.batchQueue.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testClearScanResultsKeepsTheShoppingListEntryBeingScannedFor() {
        // Switching mode mid-scan must not lose which shopping list row this scan is filling:
        // saveItem uses it to check the entry off.
        val fixture = Fixture()
        try {
            fixture.stateHolder.beginScanForShoppingListEntry("entry-1")

            fixture.stateHolder.clearScanResults()

            assertEquals("entry-1", fixture.state.scanningShoppingListEntryId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testClearScanStateAlsoDropsTheShoppingListEntryButNotTheQueue() {
        // saveItem calls this, and commitBatchQueue saves each row through saveItem, so a
        // clearScanState that emptied the queue would have it clear itself out mid-commit.
        // Discarding the queue is clearBatchQueue's job, which the scanner calls on the
        // confirmed-leave path.
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.onDateOcrDetected(someDate)
            fixture.stateHolder.beginScanForShoppingListEntry("entry-1")

            fixture.stateHolder.clearScanState()

            assertNull(fixture.state.scanningShoppingListEntryId)
            assertNull(fixture.state.detectedDate)
            assertEquals(1, fixture.state.batchQueue.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testCommittingTheQueueSavesEveryRow() {
        // Pins the commit contract the reset split has to stay compatible with: every queued
        // row reaches the repository, the reported count matches, and the queue ends empty.
        // It survives saveItem calling clearScanState per row only because commitBatchQueue
        // iterates a snapshot taken before the loop - worth keeping honest if either moves.
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.localProducts.seed(ProductInfo(barcode = "333", name = "Eggs"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.enqueueBatchScan("222")
            fixture.stateHolder.enqueueBatchScan("333")

            var added = -1
            fixture.stateHolder.commitBatchQueue { added = it }

            assertEquals(3, added)
            assertEquals(3, fixture.pantry.itemsFlow.value.size)
            assertTrue(fixture.state.batchQueue.isEmpty())
        } finally {
            fixture.close()
        }
    }
}
