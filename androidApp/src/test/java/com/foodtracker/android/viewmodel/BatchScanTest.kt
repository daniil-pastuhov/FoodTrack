package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.BACKGROUND_DATE_READ_INTERVAL_MILLIS
import com.foodtracker.viewmodel.BATCH_DATE_WINDOW_MILLIS
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.RECENT_DATE_MILLIS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Batch scan mode and duplicate detection — the two halves of making a whole shop's worth of
 * scanning bearable.
 */
class BatchScanTest {

    private class Fixture(items: List<PantryItem> = emptyList(), settings: AppSettings = AppSettings()) {
        val pantry = FakePantryRepository(items)
        val localProducts = FakeLocalProductRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = FakeSettingsRepository(settings),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext,
            nowMillis = { now }
        )

        /** What the state holder's monotonic clock reads, in milliseconds. */
        var now = 0L

        val queue get() = stateHolder.uiState.value.batchQueue
        fun close() = scope.cancel()
    }

    private fun item(id: String, barcode: String?, quantity: Int = 1, expiresInDays: Int = 7): PantryItem {
        val today = DateUtils.today()
        return PantryItem(
            id = id,
            name = "Item $id",
            barcode = barcode,
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + expiresInDays),
            addedDate = today,
            storageLocation = "Fridge",
            quantity = quantity
        )
    }

    @Test
    fun testScanningQueuesAProductWithItsCachedDetails() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk", brand = "Tine"))

            fixture.stateHolder.enqueueBatchScan("111")

            val entry = fixture.queue.single()
            assertEquals("Milk", entry.name)
            assertEquals("Tine", entry.brand)
            assertEquals(false, entry.isLookingUp)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testScanningTheSameProductAgainBumpsItsQuantityRatherThanAddingARow() {
        // This is what scanning four identical yoghurts means, and it also absorbs the camera
        // re-reading a product that is simply still in frame.
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))

            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.enqueueBatchScan("111")

            assertEquals(1, fixture.queue.size)
            assertEquals(3, fixture.queue.single().quantity)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testARememberedShelfLifeFillsTheQueuedDate() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.recordShelfLife("111", 12)

            fixture.stateHolder.enqueueBatchScan("111")

            val entry = fixture.queue.single()
            assertEquals(ShelfLifeSource.REMEMBERED, entry.expirySource)
            assertEquals(LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 12), entry.expiryDate)
            assertEquals(false, entry.needsAttention)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAnUnknownProductIsFlaggedForAttentionRatherThanSilentlyDefaulted() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "999", name = "Mystery"))

            fixture.stateHolder.enqueueBatchScan("999")

            assertTrue(fixture.queue.single().needsAttention)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testQueuedItemsUseTheRememberedStorageLocation() {
        val fixture = Fixture(
            settings = AppSettings(rememberLastStorageLocation = true, lastUsedStorageLocation = "Freezer")
        )
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Peas"))
            fixture.stateHolder.enqueueBatchScan("111")

            assertEquals("Freezer", fixture.queue.single().storageLocation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testCommittingSavesEveryQueuedRowAndEmptiesTheQueue() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.enqueueBatchScan("222")

            var added = 0
            fixture.stateHolder.commitBatchQueue { added = it }

            assertEquals(2, added)
            assertEquals(emptyList<Any>(), fixture.queue)
            assertEquals(setOf("Milk", "Bread"), fixture.pantry.itemsFlow.value.map { it.name }.toSet())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testCommittingGoesThroughTheNormalSavePathSoShelfLivesAreStillLearned() {
        // The point of reusing saveItem: a batch add must not be a second, thinner write path
        // that quietly skips everything the single-item one does.
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.enqueueBatchScan("111")
            val entry = fixture.queue.single()
            fixture.stateHolder.updateBatchEntry(
                entry.copy(expiryDate = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 9))
            )

            fixture.stateHolder.commitBatchQueue()

            assertEquals(9, fixture.localProducts.shelfLives["111"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testRemovingAndClearingTheQueue() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.enqueueBatchScan("222")

            fixture.stateHolder.removeBatchEntry("111")
            assertEquals(listOf("Bread"), fixture.queue.map { it.name })

            fixture.stateHolder.clearBatchQueue()
            assertEquals(emptyList<Any>(), fixture.queue)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testCommittingAnEmptyQueueIsANoOp() {
        val fixture = Fixture()
        try {
            var added = -1
            fixture.stateHolder.commitBatchQueue { added = it }

            assertEquals(0, added)
            assertEquals(emptyList<PantryItem>(), fixture.pantry.itemsFlow.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testScanningABarcodeAlreadyInThePantryReportsTheExistingItem() {
        val fixture = Fixture(items = listOf(item("existing", barcode = "111", quantity = 2)))
        try {
            fixture.stateHolder.checkForDuplicate("111")

            assertEquals("existing", fixture.stateHolder.uiState.value.duplicateOf?.id)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testTheSoonestExpiringDuplicateIsTheOneOffered() {
        // "+1 to what you already have" should land on the one to use up next.
        val fixture = Fixture(
            items = listOf(
                item("later", barcode = "111", expiresInDays = 20),
                item("sooner", barcode = "111", expiresInDays = 2)
            )
        )
        try {
            fixture.stateHolder.checkForDuplicate("111")

            assertEquals("sooner", fixture.stateHolder.uiState.value.duplicateOf?.id)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAnUnseenBarcodeReportsNoDuplicate() {
        val fixture = Fixture(items = listOf(item("existing", barcode = "111")))
        try {
            fixture.stateHolder.checkForDuplicate("999")

            assertNull(fixture.stateHolder.uiState.value.duplicateOf)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testMergingADuplicateAddsAUnitAndClearsThePrompt() {
        val fixture = Fixture(items = listOf(item("existing", barcode = "111", quantity = 2)))
        try {
            fixture.stateHolder.checkForDuplicate("111")

            var updated: PantryItem? = null
            fixture.stateHolder.incrementQuantity("existing") { updated = it }

            assertEquals(3, updated?.quantity)
            assertEquals(3, fixture.pantry.itemsFlow.value.single().quantity)
            assertNull(fixture.stateHolder.uiState.value.duplicateOf)
            // Still one row - merging must not have created a second entry for the product.
            assertEquals(1, fixture.pantry.itemsFlow.value.size)
        } finally {
            fixture.close()
        }
    }

    // ---- The printed date after a barcode (BatchDateRead) ----------------------------

    private val printed = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 30)

    @Test
    fun testADateReadSoonAfterAScanLandsOnThatRowAndClosesTheWindow() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            assertFalse(fixture.stateHolder.readsBatchDate())

            fixture.stateHolder.enqueueBatchScan("111")
            assertTrue(fixture.stateHolder.readsBatchDate())
            fixture.now = BATCH_DATE_WINDOW_MILLIS - 1
            val read = fixture.stateHolder.onBatchDateRead(printed)

            assertEquals("111", read?.id)
            val entry = fixture.queue.single()
            assertEquals(printed, entry.expiryDate)
            assertEquals(ShelfLifeSource.DETECTED, entry.expirySource)
            assertFalse(entry.showsCheckDate)
            // One date per scan.
            assertFalse(fixture.stateHolder.readsBatchDate())
            assertNull(fixture.stateHolder.onBatchDateRead(printed.plusDays(1)))
            assertEquals(printed, fixture.queue.single().expiryDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testADateReadAfterTheWindowIsIgnored() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "999", name = "Mystery"))
            fixture.stateHolder.enqueueBatchScan("999")
            fixture.now = BATCH_DATE_WINDOW_MILLIS

            assertFalse(fixture.stateHolder.readsBatchDate())
            assertNull(fixture.stateHolder.onBatchDateRead(printed))
            assertEquals(ShelfLifeSource.FALLBACK, fixture.queue.single().expirySource)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testANewScanMovesTheTargetAndRestartsTheWindow() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.now = 8_000
            fixture.stateHolder.enqueueBatchScan("222")
            fixture.now = 16_000

            fixture.stateHolder.onBatchDateRead(printed)

            assertEquals(printed, fixture.queue.single { it.id == "222" }.expiryDate)
            assertEquals(ShelfLifeSource.FALLBACK, fixture.queue.single { it.id == "111" }.expirySource)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testARepeatScanOpensAWindowForTheRowItBumps() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.enqueueBatchScan("222")
            fixture.stateHolder.enqueueBatchScan("111")

            fixture.stateHolder.onBatchDateRead(printed)

            assertEquals(printed, fixture.queue.single { it.id == "111" }.expiryDate)
            assertEquals(2, fixture.queue.single { it.id == "111" }.quantity)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAGs1DateAHandSetDateOrARemovedRowIsNotOverwritten() {
        val gs1 = "0104012345678901" + "17261231"
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = gs1, name = "Ham"))
            fixture.stateHolder.enqueueBatchScan(gs1)
            assertNull(fixture.stateHolder.onBatchDateRead(printed))
            assertEquals(LocalDate(2026, 12, 31), fixture.queue.single().expiryDate)

            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.enqueueBatchScan("111")
            val byHand = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 3)
            fixture.stateHolder.updateBatchEntry(fixture.queue.single { it.id == "111" }.copy(expiryDate = byHand))
            assertNull(fixture.stateHolder.onBatchDateRead(printed))
            assertEquals(byHand, fixture.queue.single { it.id == "111" }.expiryDate)

            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("222")
            fixture.stateHolder.removeBatchEntry("222")
            assertFalse(fixture.stateHolder.readsBatchDate())
            assertNull(fixture.stateHolder.onBatchDateRead(printed))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testUndoRestoresThePreviousDateAndSourceWithoutReopeningTheWindow() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.recordShelfLife("111", 12)
            fixture.stateHolder.enqueueBatchScan("111")
            val before = fixture.queue.single()

            fixture.stateHolder.onBatchDateRead(printed)
            fixture.stateHolder.undoBatchDateRead("111")

            assertEquals(before, fixture.queue.single())
            assertFalse(fixture.stateHolder.readsBatchDate())
            assertNull(fixture.stateHolder.onBatchDateRead(printed))
        } finally {
            fixture.close()
        }
    }

    // ---- Round 2: the date read with the barcode, and rescans --------------------------

    @Test
    fun testADateReadJustBeforeABatchScanLandsOnItsRowAtOnceWithoutAWindow() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            assertNull(fixture.stateHolder.onBatchDateRead(printed))
            fixture.now = RECENT_DATE_MILLIS - 1

            val read = fixture.stateHolder.enqueueBatchScan("111")

            assertEquals("111", read?.id)
            assertEquals(printed, fixture.queue.single().expiryDate)
            assertEquals(ShelfLifeSource.DETECTED, fixture.queue.single().expirySource)
            assertFalse(fixture.stateHolder.readsBatchDate())
            // Used once: the next product doesn't get it.
            assertNull(fixture.stateHolder.enqueueBatchScan("222"))
            assertEquals(ShelfLifeSource.FALLBACK, fixture.queue.single { it.id == "222" }.expirySource)
            assertTrue(fixture.stateHolder.readsBatchDate())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testADateReadMoreThanTwoSecondsBeforeTheScanIsNotUsed() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.onBatchDateRead(printed)
            fixture.now = RECENT_DATE_MILLIS

            assertNull(fixture.stateHolder.enqueueBatchScan("111"))
            assertEquals(ShelfLifeSource.FALLBACK, fixture.queue.single().expirySource)
            assertTrue(fixture.stateHolder.readsBatchDate())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testTheDateJustLandedIsNotGivenToTheNextProduct() {
        // The camera still sees the first pack's date after it landed.
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.onBatchDateRead(printed)
            fixture.now = 3_000
            fixture.stateHolder.onBatchDateRead(printed)

            assertNull(fixture.stateHolder.enqueueBatchScan("222"))
            assertEquals(ShelfLifeSource.FALLBACK, fixture.queue.single { it.id == "222" }.expirySource)

            // Not even after another date was read in between, nor after an Undo.
            fixture.now = 20_000
            fixture.stateHolder.onBatchDateRead(printed.plusDays(1))
            fixture.stateHolder.onBatchDateRead(printed)
            fixture.localProducts.seed(ProductInfo(barcode = "333", name = "Jam"))
            assertEquals(printed.plusDays(1), fixture.stateHolder.enqueueBatchScan("333")?.expiryDate)
            fixture.stateHolder.undoBatchDateRead("333")
            fixture.now = 40_000
            fixture.stateHolder.onBatchDateRead(printed.plusDays(1))
            fixture.localProducts.seed(ProductInfo(barcode = "444", name = "Tea"))
            assertNull(fixture.stateHolder.enqueueBatchScan("444"))

            // Inside a window the same date still lands: two products, one printed date.
            fixture.stateHolder.onBatchDateRead(printed.plusDays(1))
            assertEquals(printed.plusDays(1), fixture.queue.single { it.id == "444" }.expiryDate)

            // A mode change forgets it.
            fixture.stateHolder.forgetScanDates()
            fixture.stateHolder.onBatchDateRead(printed.plusDays(1))
            fixture.localProducts.seed(ProductInfo(barcode = "555", name = "Rice"))
            assertEquals(printed.plusDays(1), fixture.stateHolder.enqueueBatchScan("555")?.expiryDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAModeChangeForgetsTheRecentDate() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.onBatchDateRead(printed)
            fixture.stateHolder.forgetScanDates()

            assertNull(fixture.stateHolder.enqueueBatchScan("111"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testBarcodeModeTakesARecentDateAsTheScansDetectedDate() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            assertFalse(fixture.stateHolder.onScanDateRead(printed))

            fixture.stateHolder.onBarcodeScanned("111")

            val state = fixture.stateHolder.uiState.value
            assertEquals("Milk", state.scannedProduct?.name)
            assertEquals(printed, state.detectedDate)
            assertTrue(state.detectedDateReadOffPack)
            assertEquals(ShelfLifeSource.DETECTED, state.suggestedExpiry?.source)
            assertEquals(printed, state.suggestedExpiry?.date)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testBarcodeModeScanAgainForgetsTheRecentDateAndTheLandedDateIsNotReused() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.onBarcodeScanned("111")
            fixture.stateHolder.onScanDateRead(printed)
            fixture.stateHolder.forgetRecentDate()
            fixture.stateHolder.clearScanResults()
            // The first pack, still in view.
            fixture.stateHolder.onScanDateRead(printed)
            fixture.stateHolder.onBarcodeScanned("222")
            assertNull(fixture.stateHolder.uiState.value.detectedDate)

            fixture.stateHolder.forgetRecentDate()
            fixture.stateHolder.clearScanResults()
            fixture.stateHolder.onScanDateRead(printed.plusDays(1))
            fixture.stateHolder.forgetRecentDate()
            fixture.stateHolder.onBarcodeScanned("222")
            assertNull(fixture.stateHolder.uiState.value.detectedDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testBarcodeModeKeepsAGs1DateOverARecentOne() {
        val gs1 = "0104012345678901" + "17261231"
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = gs1, name = "Ham"))
            fixture.stateHolder.onScanDateRead(printed)

            fixture.stateHolder.onBarcodeScanned(gs1)

            val state = fixture.stateHolder.uiState.value
            assertEquals(LocalDate(2026, 12, 31), state.detectedDate)
            assertFalse(state.detectedDateReadOffPack)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testBarcodeModeFillsTheHeldProductsDateOnceAndScanDateStillReplacesIt() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.onBarcodeScanned("111")
            assertNull(fixture.stateHolder.uiState.value.detectedDate)

            assertTrue(fixture.stateHolder.onScanDateRead(printed))
            assertFalse(fixture.stateHolder.onScanDateRead(printed.plusDays(5)))
            assertEquals(printed, fixture.stateHolder.uiState.value.detectedDate)
            assertEquals(ShelfLifeSource.DETECTED, fixture.stateHolder.uiState.value.suggestedExpiry?.source)

            // Date mode's explicit read.
            fixture.stateHolder.onDateOcrDetected(printed.plusDays(5))
            assertEquals(printed.plusDays(5), fixture.stateHolder.uiState.value.detectedDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testARescanReplacesADetectedDateWithADifferentOneAndUndoRestoresIt() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.enqueueBatchScan("111")
            val production = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() - 3)
            fixture.stateHolder.onBatchDateRead(production)
            val misread = fixture.queue.single()
            fixture.now = 20_000

            assertTrue(fixture.stateHolder.startBatchDateRescan("111"))
            // The same date again: ignored, still reading.
            assertNull(fixture.stateHolder.onBatchDateRead(production))
            assertTrue(fixture.stateHolder.readsBatchDate())
            val read = fixture.stateHolder.onBatchDateRead(printed)

            assertEquals(printed, read?.expiryDate)
            assertEquals(ShelfLifeSource.DETECTED, fixture.queue.single().expirySource)
            assertFalse(fixture.stateHolder.readsBatchDate())

            // One level of Undo: the date, source and set-by-hand flag from before the rescan.
            fixture.stateHolder.undoBatchDateRead("111")
            val undone = fixture.queue.single()
            assertEquals(misread.expiryDate, undone.expiryDate)
            assertEquals(misread.expirySource, undone.expirySource)
            assertEquals(misread.dateSetByHand, undone.dateSetByHand)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testARescanOverridesAHandSetDateButNeverUsesTheRecentDate() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.enqueueBatchScan("111")
            val byHand = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 3)
            fixture.stateHolder.updateBatchEntry(fixture.queue.single().copy(expiryDate = byHand))
            fixture.now = 20_000
            // No window open: kept as the recent date.
            fixture.stateHolder.onBatchDateRead(printed)

            fixture.stateHolder.startBatchDateRescan("111")

            assertEquals(byHand, fixture.queue.single().expiryDate)
            fixture.stateHolder.onBatchDateRead(printed.plusDays(2))
            assertEquals(printed.plusDays(2), fixture.queue.single().expiryDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testABarcodeScanEndsARescanAndBehavesAsAnyScan() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.onBatchDateRead(printed)
            fixture.stateHolder.startBatchDateRescan("111")

            fixture.stateHolder.enqueueBatchScan("222")
            val other = printed.plusDays(4)
            fixture.stateHolder.onBatchDateRead(other)

            assertEquals(printed, fixture.queue.single { it.id == "111" }.expiryDate)
            assertEquals(other, fixture.queue.single { it.id == "222" }.expiryDate)
            assertEquals(false, fixture.stateHolder.batchDateWindowState.value?.rescan == true)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAReceiptRowCannotBeRescanned() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.enqueueReceiptLines(listOf("MILK 1.20"))
            val row = fixture.queue.single()

            assertFalse(fixture.stateHolder.startBatchDateRescan(row.id))
            assertFalse(fixture.stateHolder.readsBatchDate())
        } finally {
            fixture.close()
        }
    }

    // ---- Round 3: review fixes ---------------------------------------------------------

    private val gs1 = "0104012345678901" + "17261231"
    private val gs1Printed = LocalDate(2026, 12, 31)

    @Test
    fun testAReadInAnOpenWindowThatDoesNotLandIsNotKeptAsRecent() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.stateHolder.enqueueBatchScan("111")
            val byHand = printed.plusDays(-20)
            fixture.stateHolder.updateBatchEntry(fixture.queue.single().copy(expiryDate = byHand))

            assertNull(fixture.stateHolder.onBatchDateRead(printed))

            assertNull(fixture.stateHolder.enqueueBatchScan("222"))
            assertEquals(ShelfLifeSource.FALLBACK, fixture.queue.single { it.id == "222" }.expirySource)

            // Nor a same-day read during a rescan.
            fixture.stateHolder.startBatchDateRescan("111")
            assertNull(fixture.stateHolder.onBatchDateRead(byHand))
            assertTrue(fixture.stateHolder.readsBatchDate())
            fixture.localProducts.seed(ProductInfo(barcode = "333", name = "Jam"))
            assertNull(fixture.stateHolder.enqueueBatchScan("333"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testABatchScanUsesUpTheRecentDateEvenWhenItsRowCannotTakeIt() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = gs1, name = "Ham"))
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.onBatchDateRead(printed)

            assertNull(fixture.stateHolder.enqueueBatchScan(gs1))
            assertNull(fixture.stateHolder.enqueueBatchScan("111"))
            assertEquals(ShelfLifeSource.FALLBACK, fixture.queue.single { it.id == "111" }.expirySource)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAGs1DateCountsAsLandedSoThePrintedSameDateIsNotKept() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = gs1, name = "Ham"))
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))

            // Batch.
            fixture.stateHolder.enqueueBatchScan(gs1)
            fixture.stateHolder.onBatchDateRead(gs1Printed)
            assertNull(fixture.stateHolder.enqueueBatchScan("111"))

            // Barcode mode.
            fixture.stateHolder.forgetScanDates()
            fixture.stateHolder.onBarcodeScanned(gs1)
            fixture.stateHolder.forgetRecentDate()
            fixture.stateHolder.clearScanResults()
            fixture.stateHolder.onScanDateRead(gs1Printed)
            fixture.stateHolder.onBarcodeScanned("111")
            assertNull(fixture.stateHolder.uiState.value.detectedDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testARowThatCannotTakeADateOpensNoWindowAndClosesTheLastOne() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = gs1, name = "Ham"))
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.enqueueBatchScan("111")
            assertTrue(fixture.stateHolder.readsBatchDate())

            fixture.stateHolder.enqueueBatchScan(gs1)

            assertFalse(fixture.stateHolder.readsBatchDate())
            assertEquals(BACKGROUND_DATE_READ_INTERVAL_MILLIS, fixture.stateHolder.dateReadIntervalMillis())

            // A repeat of a row set by hand.
            fixture.stateHolder.updateBatchEntry(fixture.queue.single { it.id == "111" }.copy(expiryDate = printed))
            fixture.stateHolder.enqueueBatchScan("111")
            assertFalse(fixture.stateHolder.readsBatchDate())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testBarcodeModePausesDateReadingWhileTheHeldScanHasItsDate() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            assertEquals(BACKGROUND_DATE_READ_INTERVAL_MILLIS, fixture.stateHolder.dateReadIntervalMillis())
            fixture.stateHolder.onBarcodeScanned("111")
            assertEquals(BACKGROUND_DATE_READ_INTERVAL_MILLIS, fixture.stateHolder.dateReadIntervalMillis())

            fixture.stateHolder.onScanDateRead(printed)
            assertNull(fixture.stateHolder.dateReadIntervalMillis())

            fixture.stateHolder.clearScanResults()
            assertEquals(BACKGROUND_DATE_READ_INTERVAL_MILLIS, fixture.stateHolder.dateReadIntervalMillis())

            fixture.stateHolder.enqueueBatchScan("111")
            assertEquals(0L, fixture.stateHolder.dateReadIntervalMillis())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testCommittingClosesTheWindowAndForgetsTheDates() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.seed(ProductInfo(barcode = "222", name = "Bread"))
            fixture.localProducts.seed(ProductInfo(barcode = "333", name = "Jam"))
            fixture.stateHolder.enqueueBatchScan("111")
            fixture.stateHolder.onBatchDateRead(printed)
            fixture.stateHolder.enqueueBatchScan("222")
            assertTrue(fixture.stateHolder.readsBatchDate())

            fixture.stateHolder.commitBatchQueue()

            assertFalse(fixture.stateHolder.readsBatchDate())
            // The landed date is forgotten too: the same date read now is kept and used.
            fixture.stateHolder.onBatchDateRead(printed)
            assertEquals(printed, fixture.stateHolder.enqueueBatchScan("333")?.expiryDate)
        } finally {
            fixture.close()
        }
    }

    private fun LocalDate.plusDays(days: Int) = LocalDate.fromEpochDays(toEpochDays() + days)
}
