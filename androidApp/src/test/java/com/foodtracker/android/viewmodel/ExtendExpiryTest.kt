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
 * The notification's "Still good" action, which pushes an item's expiry out rather than
 * deleting it (see ExpiryNotificationReceiver.ACTION_STILL_GOOD).
 */
class ExtendExpiryTest {

    private class Fixture(items: List<PantryItem>) {
        val pantry = FakePantryRepository(items)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = FakeSettingsRepository(AppSettings()),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = FakeLocalProductRepository(),
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        fun item(id: String) = pantry.itemsFlow.value.firstOrNull { it.id == id }
        fun close() = scope.cancel()
    }

    private fun item(id: String, expiresInDays: Int): PantryItem {
        val today = DateUtils.today()
        return PantryItem(
            id = id,
            name = "Item $id",
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + expiresInDays),
            addedDate = today,
            storageLocation = "Fridge"
        )
    }

    @Test
    fun testExtendingCountsFromTheItemsOwnExpiryNotFromToday() {
        // Tapping "Still good" on something that expired three days ago must not quietly hand
        // it a fresh three days from now - the point is a small correction to a printed date.
        val fixture = Fixture(listOf(item("old", expiresInDays = -3)))
        try {
            fixture.stateHolder.extendExpiry("old", 3)

            val expected = LocalDate.fromEpochDays(DateUtils.today().toEpochDays())
            assertEquals(expected, fixture.item("old")?.expiryDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testExtendingAFreshItemMovesItsDateOut() {
        val fixture = Fixture(listOf(item("milk", expiresInDays = 1)))
        try {
            fixture.stateHolder.extendExpiry("milk", 3)

            val expected = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 4)
            assertEquals(expected, fixture.item("milk")?.expiryDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testTheUpdatedItemIsHandedBackSoItsReminderCanBeRescheduled() {
        // The notification that prompted this has already fired; without the callback the item
        // would never warn again.
        val fixture = Fixture(listOf(item("milk", expiresInDays = 1)))
        try {
            var updated: PantryItem? = null
            fixture.stateHolder.extendExpiry("milk", 3) { updated = it }

            assertEquals(fixture.item("milk")?.expiryDate, updated?.expiryDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testAMissingItemIsANoOp() {
        // The notification outlives the item it refers to: it can still be on screen after the
        // item was deleted from the app.
        val fixture = Fixture(emptyList())
        try {
            var updated: PantryItem? = null
            fixture.stateHolder.extendExpiry("gone", 3) { updated = it }

            assertNull(updated)
            assertEquals(emptyList<PantryItem>(), fixture.pantry.itemsFlow.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testANonPositiveExtensionChangesNothing() {
        val fixture = Fixture(listOf(item("milk", expiresInDays = 1)))
        try {
            val before = fixture.item("milk")?.expiryDate
            fixture.stateHolder.extendExpiry("milk", 0)
            fixture.stateHolder.extendExpiry("milk", -5)

            assertEquals(before, fixture.item("milk")?.expiryDate)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testExtendingKeepsEverythingElseAboutTheItem() {
        val original = item("milk", expiresInDays = 1).copy(
            quantity = 3,
            notes = "Top shelf",
            essential = true,
            barcode = "111"
        )
        val fixture = Fixture(listOf(original))
        try {
            fixture.stateHolder.extendExpiry("milk", 3)

            val updated = fixture.item("milk")
            assertEquals(3, updated?.quantity)
            assertEquals("Top shelf", updated?.notes)
            assertEquals("111", updated?.barcode)
            assertEquals(original.addedDate, updated?.addedDate)
        } finally {
            fixture.close()
        }
    }
}
