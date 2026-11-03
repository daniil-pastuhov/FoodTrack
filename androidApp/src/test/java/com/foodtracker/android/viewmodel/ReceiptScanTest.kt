package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.ProductInfo
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Receipt scanning, from the state holder's side: `ReceiptParser` itself is covered by
 * `ReceiptParserTest` in `shared`, so what's tested here is what the queue does with the lines
 * it produces — matching them against known products, and keeping receipt rows distinct.
 */
class ReceiptScanTest {

    private class Fixture(settings: AppSettings = AppSettings()) {
        val localProducts = FakeLocalProductRepository()
        val productNames = FakeProductNameRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = FakePantryRepository(emptyList()),
            settingsRepository = FakeSettingsRepository(settings),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = productNames,
            localProductRepository = localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        val queue get() = stateHolder.uiState.value.batchQueue
        fun close() = scope.cancel()
    }

    private val receipt = listOf(
        "TESCO EXTRA",
        "MLK SEMI 2PT              1.25",
        "2 x BROWN BREAD           2.30",
        "TOTAL                     3.55"
    )

    @Test
    fun testReceiptLinesBecomeBatchRows() {
        val fixture = Fixture()
        try {
            var added = -1
            fixture.stateHolder.enqueueReceiptLines(receipt) { added = it }

            assertEquals(2, added)
            assertEquals(listOf("MLK SEMI 2PT", "BROWN BREAD"), fixture.queue.map { it.name })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testReceiptQuantityCarriesOntoTheRow() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.enqueueReceiptLines(receipt)

            assertEquals(2, fixture.queue.first { it.name == "BROWN BREAD" }.quantity)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testReceiptRowsHaveNoBarcodeWhenNothingMatches() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.enqueueReceiptLines(receipt)

            assertTrue(fixture.queue.all { it.barcode == null })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testReceiptRowsGetDistinctIdsSoTheyDoNotCollapse() {
        val fixture = Fixture()
        try {
            // Two lines for the same product: genuinely two purchases, not a double read, so
            // they must stay as two rows — unlike re-scanning one barcode.
            fixture.stateHolder.enqueueReceiptLines(
                listOf("APPLES LOOSE      1.20", "APPLES LOOSE      1.20")
            )

            assertEquals(2, fixture.queue.size)
            assertNotEquals(fixture.queue[0].id, fixture.queue[1].id)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAConfidentMatchAdoptsTheCachedProductsDetails() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(
                ProductInfo(
                    barcode = "111",
                    name = "Brown Bread",
                    brand = "Hovis",
                    category = "bakery"
                )
            )

            fixture.stateHolder.enqueueReceiptLines(receipt)

            val bread = fixture.queue.first { it.barcode == "111" }
            assertEquals("Brown Bread", bread.name)
            assertEquals("Hovis", bread.brand)
            assertEquals("bakery", bread.category)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAMatchedLineStartsInTheProductsRememberedLocationAndOthersInTheGeneralOne() {
        val fixture = Fixture(settings = AppSettings(lastUsedStorageLocation = "Pantry"))
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Brown Bread"))
            fixture.localProducts.storageLocations["111"] = "Freezer"

            fixture.stateHolder.enqueueReceiptLines(receipt)

            assertEquals("Freezer", fixture.queue.first { it.barcode == "111" }.storageLocation)
            assertEquals("Pantry", fixture.queue.first { it.name == "MLK SEMI 2PT" }.storageLocation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAnUnrelatedCachedProductIsNotAdopted() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "999", name = "Washing Powder"))

            fixture.stateHolder.enqueueReceiptLines(receipt)

            assertTrue(fixture.queue.none { it.barcode == "999" })
            assertNull(fixture.queue.first { it.name == "MLK SEMI 2PT" }.barcode)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAKnownNameIsAdoptedWhenNoCachedProductMatches() {
        val fixture = Fixture()
        try {
            fixture.productNames.namesFlow.value = listOf("Brown Bread")

            fixture.stateHolder.enqueueReceiptLines(receipt)

            val bread = fixture.queue.first { it.name == "Brown Bread" }
            // A remembered name carries no barcode of its own — it only improves the label.
            assertNull(bread.barcode)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testReceiptRowsUseTheStorageLocationConfiguredForNewItems() {
        val fixture = Fixture(
            settings = AppSettings(
                defaultStorageLocation = "Freezer",
                rememberLastStorageLocation = false
            )
        )
        try {
            fixture.stateHolder.enqueueReceiptLines(receipt)

            assertTrue(fixture.queue.all { it.storageLocation == "Freezer" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAReceiptWithNoProductLinesAddsNothing() {
        val fixture = Fixture()
        try {
            var added = -1
            fixture.stateHolder.enqueueReceiptLines(
                listOf("TESCO EXTRA", "TOTAL 3.55", "VISA 3.55")
            ) { added = it }

            assertEquals(0, added)
            assertTrue(fixture.queue.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testReceiptRowsAppendToAnExistingQueueRatherThanReplacingIt() {
        val fixture = Fixture()
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.enqueueBatchScan("111")

            fixture.stateHolder.enqueueReceiptLines(receipt)

            assertEquals(3, fixture.queue.size)
            assertEquals("111", fixture.queue.first().id)
        } finally {
            fixture.close()
        }
    }
}
