package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShelfLife
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
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Smart default expiry: saving a new item teaches the app how long that barcode's product
 * keeps, and a later scan of the same barcode pre-fills its expiry date from that. See
 * [ShelfLife] for the precedence rule this exercises through the state holder.
 *
 * Deliberately avoids the network: every barcode used here is seeded into the local product
 * cache first, which is the path [PantryStateHolder.onBarcodeScanned] prefers anyway.
 */
class SmartDefaultExpiryTest {

    private val today = DateUtils.today()

    private class Fixture(
        items: List<PantryItem> = emptyList(),
        categories: List<CategoryPreference> = emptyList()
    ) {
        val localProducts = FakeLocalProductRepository()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = FakePantryRepository(items),
            settingsRepository = FakeSettingsRepository(AppSettings()),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(categories),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        fun close() = scope.cancel()
    }

    private fun item(
        id: String,
        barcode: String? = "111",
        category: String? = null,
        addedDaysAgo: Int = 0,
        expiresInDays: Int
    ): PantryItem {
        val today = DateUtils.today()
        return PantryItem(
            id = id,
            name = "Item $id",
            barcode = barcode,
            category = category,
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + expiresInDays),
            addedDate = LocalDate.fromEpochDays(today.toEpochDays() - addedDaysAgo),
            storageLocation = "Fridge"
        )
    }

    private fun daysFromToday(days: Int) = LocalDate.fromEpochDays(today.toEpochDays() + days)

    @Test
    fun testSavingANewItemTeachesItsBarcodeShelfLife() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("milk", expiresInDays = 14))

            assertEquals(14, fixture.localProducts.shelfLives["111"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testEditingAnExistingItemDoesNotChangeTheRememberedShelfLife() {
        // Editing an old item's date says nothing about how long a *fresh* one lasts - the same
        // rule the remembered storage location follows.
        val existing = item("milk", expiresInDays = 14)
        val fixture = Fixture(items = listOf(existing))
        try {
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.stateHolder.saveItem(existing.copy(expiryDate = daysFromToday(2)))

            assertNull(fixture.localProducts.shelfLives["111"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAnItemAddedAlreadyExpiredTeachesNothing() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("old", addedDaysAgo = 0, expiresInDays = -3))

            assertNull(fixture.localProducts.shelfLives["111"])
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAnItemWithNoBarcodeTeachesNothing() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("bread", barcode = null, expiresInDays = 3))

            assertEquals(emptyMap<String, Int>(), fixture.localProducts.shelfLives)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testScanningAKnownProductSuggestsTheShelfLifeItWasGivenLastTime() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("milk", expiresInDays = 14))
            fixture.stateHolder.clearScanState()

            fixture.stateHolder.onBarcodeScanned("111")

            val suggestion = fixture.stateHolder.uiState.value.suggestedExpiry
            assertEquals(ShelfLifeSource.REMEMBERED, suggestion?.source)
            assertEquals(daysFromToday(14), suggestion?.date)
            assertEquals(14, suggestion?.days)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testScanningAProductNeverAddedFallsBackToItsCategoryDefault() {
        val fixture = Fixture(
            categories = listOf(
                CategoryPreference(
                    originName = "Dairy",
                    displayName = "Dairy",
                    defaultShelfLifeDays = 10
                )
            )
        )
        try {
            // The product is cached (so no network lookup happens) but has never been added,
            // so there is no per-barcode shelf life to prefer.
            fixture.localProducts.seed(
                ProductInfo(barcode = "222", name = "Cheese", category = "Dairy")
            )

            fixture.stateHolder.onBarcodeScanned("222")

            val suggestion = fixture.stateHolder.uiState.value.suggestedExpiry
            assertEquals(ShelfLifeSource.CATEGORY_DEFAULT, suggestion?.source)
            assertEquals(daysFromToday(10), suggestion?.date)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testARememberedShelfLifeBeatsTheCategoryDefault() {
        val fixture = Fixture(
            categories = listOf(
                CategoryPreference(originName = "Dairy", displayName = "Dairy", defaultShelfLifeDays = 10)
            )
        )
        try {
            fixture.stateHolder.saveItem(item("milk", category = "Dairy", expiresInDays = 4))
            fixture.stateHolder.clearScanState()

            fixture.stateHolder.onBarcodeScanned("111")

            val suggestion = fixture.stateHolder.uiState.value.suggestedExpiry
            assertEquals(ShelfLifeSource.REMEMBERED, suggestion?.source)
            assertEquals(daysFromToday(4), suggestion?.date)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testADateReadOffThePackagingWinsOverTheRememberedShelfLife() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("milk", expiresInDays = 14))
            fixture.stateHolder.clearScanState()

            // GS1 AI(17) 260101 - an expiry printed into the barcode itself.
            fixture.stateHolder.onBarcodeScanned("0104012345678903172601 01")
            fixture.stateHolder.onDateOcrDetected(daysFromToday(2))

            val suggestion = fixture.stateHolder.uiState.value.suggestedExpiry
            assertEquals(ShelfLifeSource.DETECTED, suggestion?.source)
            assertEquals(daysFromToday(2), suggestion?.date)
            assertNull("a detected date is a date, not a shelf life span", suggestion?.days)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testClearingTheScanStateDropsTheSuggestion() {
        // Otherwise a suggestion from one scan leaks into the next, unrelated Add/Edit.
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(item("milk", expiresInDays = 14))
            fixture.stateHolder.clearScanState()
            fixture.stateHolder.onBarcodeScanned("111")
            assertEquals(
                ShelfLifeSource.REMEMBERED,
                fixture.stateHolder.uiState.value.suggestedExpiry?.source
            )

            fixture.stateHolder.clearScanState()

            assertNull(fixture.stateHolder.uiState.value.suggestedExpiry)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testSettingACategoryDefaultAppliesToEveryOriginMergedOntoThatCategory() {
        // Two raw category values renamed to the same display name are one row to the user, so
        // the default has to move as a unit - the same rule enable/disable follows.
        val fixture = Fixture(
            categories = listOf(
                CategoryPreference(originName = "Dairy", displayName = "Dairy"),
                CategoryPreference(originName = "Milk products", displayName = "Dairy"),
                CategoryPreference(originName = "Bakery", displayName = "Bakery")
            )
        )
        try {
            fixture.stateHolder.setCategoryDefaultShelfLife("Dairy", 12)

            val prefs = fixture.stateHolder.uiState.value.categoryPreferences.associateBy { it.originName }
            assertEquals(12, prefs["Dairy"]?.defaultShelfLifeDays)
            assertEquals(12, prefs["Milk products"]?.defaultShelfLifeDays)
            assertNull(prefs["Bakery"]?.defaultShelfLifeDays)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAnImplausibleCategoryDefaultIsClampedRatherThanStored() {
        val fixture = Fixture(
            categories = listOf(CategoryPreference(originName = "Dairy", displayName = "Dairy"))
        )
        try {
            fixture.stateHolder.setCategoryDefaultShelfLife("Dairy", 99_999)

            assertEquals(
                ShelfLife.MAX_LEARNABLE_DAYS,
                fixture.stateHolder.uiState.value.categoryPreferences
                    .first { it.originName == "Dairy" }.defaultShelfLifeDays
            )
        } finally {
            fixture.close()
        }
    }
}
