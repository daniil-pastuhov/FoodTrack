package com.foodtracker.android.widget

import android.app.Application
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext

/** Ticking a row on the shopping-list widget, driven through the [TickOffShoppingEntryAction]
 *  callback Glance runs on a tap. */
@RunWith(RobolectricTestRunner::class)
// The plain Application: FoodTrackerApp would start the real Koin graph.
@Config(sdk = [34], application = Application::class)
class TickOffShoppingEntryActionTest {

    private val milk = entry("milk", "Milk", quantity = 3)
    private val bread = entry("bread", "Bread")
    private val rice = PantryItem(
        id = "rice",
        name = "Rice",
        expiryDate = DateUtils.today(),
        addedDate = DateUtils.today()
    )

    private val shopping = FakeShoppingListRepository(listOf(milk, bread))
    private val pantry = FakePantryRepository(listOf(rice))
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val glanceId = object : GlanceId {}

    private fun entry(id: String, name: String, quantity: Int = 1) = ShoppingListEntry(
        id = id,
        productName = name,
        quantity = quantity,
        addedDate = DateUtils.today()
    )

    @Before
    fun setUp() {
        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = FakeSettingsRepository(AppSettings()),
            shoppingListRepository = shopping,
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = FakeLocalProductRepository(),
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(emptyList(), shopping),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )
        startKoin { modules(module { single { stateHolder } }) }
    }

    @After
    fun tearDown() {
        stopKoin()
        scope.cancel()
    }

    private suspend fun tick(entryId: String?) {
        val parameters = if (entryId == null) {
            actionParametersOf()
        } else {
            actionParametersOf(widgetTargetIdKey to entryId)
        }
        TickOffShoppingEntryAction().onAction(context, glanceId, parameters)
    }

    @Test
    fun tickingRemovesTheWholeEntryAndLeavesTheOthers() = runTest {
        tick("milk")
        assertEquals(listOf(bread), shopping.entriesFlow.value)
    }

    @Test
    fun tickingDoesNotAddAnythingToThePantry() = runTest {
        tick("bread")
        assertEquals(listOf(rice), pantry.itemsFlow.value)
    }

    @Test
    fun tickingAnEntryThatIsAlreadyGoneChangesNothing() = runTest {
        tick("milk")
        tick("milk")
        assertEquals(listOf(bread), shopping.entriesFlow.value)
    }

    @Test
    fun aTapWithoutATargetChangesNothing() = runTest {
        tick(null)
        assertEquals(listOf(milk, bread), shopping.entriesFlow.value)
    }
}
