package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * An app left running across midnight must not keep showing yesterday's countdowns: the
 * derived rows and counters are rebuilt when the date moves, not only when an item or
 * setting changes.
 */
class DayRolloverTest {

    private val monday = LocalDate(2026, 9, 28)
    private val tuesday = LocalDate(2026, 9, 29)

    @Test
    fun testRefreshingAfterMidnightRederivesLabelsAndStatus() {
        var today = monday
        val pantry = FakePantryRepository(
            listOf(
                PantryItem(
                    id = "milk",
                    name = "Milk",
                    expiryDate = tuesday,
                    addedDate = monday,
                    storageLocation = "Fridge"
                )
            )
        )
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
            computationContext = EmptyCoroutineContext,
            todayProvider = { today }
        )
        try {
            val before = stateHolder.uiState.value
            assertEquals(monday, before.today)
            assertEquals(1, before.allRows.single().daysRemaining)
            assertEquals(ExpiryStatus.EXPIRING_SOON, before.allRows.single().status)

            today = tuesday
            stateHolder.refreshToday()

            val after = stateHolder.uiState.value
            assertEquals(tuesday, after.today)
            assertEquals(0, after.allRows.single().daysRemaining)
            assertEquals(ExpiryStatus.EXPIRING_TODAY, after.allRows.single().status)
            assertEquals(0, after.filteredItems.single().daysRemaining)
        } finally {
            scope.cancel()
        }
    }
}
