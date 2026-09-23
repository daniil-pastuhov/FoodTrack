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
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
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
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

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
}
