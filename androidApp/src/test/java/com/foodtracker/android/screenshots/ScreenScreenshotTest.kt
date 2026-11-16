package com.foodtracker.android.screenshots

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeStapleRepository
import com.foodtracker.android.ui.components.ProductDetailActions
import com.foodtracker.android.ui.components.ProductDetailPane
import com.foodtracker.android.ui.screens.AddEditItemScreen
import com.foodtracker.android.ui.screens.PantryDashboardScreen
import com.foodtracker.android.ui.screens.ShoppingListScreen
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.model.AppSettings
import com.foodtracker.model.DefaultStorageLocations
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.viewmodel.PantryStateHolder
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziActivity
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.registerRoborazziActivityToRobolectricIfNeeded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The screens that take a [PantryStateHolder] and so have no @Preview (Home, the shopping list,
 * the add/edit form): each is built over in-memory repositories with the day pinned to [TODAY],
 * and checked like the previews (scripts/ui_check.sh). Images are named after this class and the
 * test method. In a plain test run nothing is captured or compared.
 */
@OptIn(ExperimentalRoborazziApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PIXEL_5_EN_GB)
class ScreenScreenshotTest {

    private val composeRule = createAndroidComposeRule<RoborazziActivity>(effectContext = StandardTestDispatcher())

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(object : TestWatcher() {
            override fun starting(description: Description) = registerRoborazziActivityToRobolectricIfNeeded()
        })
        .around(ScreenshotEnvironmentRule())
        .around(composeRule)

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @After
    fun tearDown() = scope.cancel()

    // Home

    @Test
    fun home() = capture { Home(pantry()) }

    @Test
    @Config(qualifiers = "+night")
    fun homeDark() = capture { Home(pantry()) }

    @Test
    fun homeEmpty() = capture { Home(emptyList()) }

    @Test
    @Config(qualifiers = "+ru")
    fun homeRussian() = capture { Home(pantry()) }

    @Test
    @Config(qualifiers = "+w1024dp-h768dp-land")
    fun homeTablet() = capture { Home(pantry()) }

    // Shopping list

    @Test
    fun shoppingList() = capture { Shopping(shopping()) }

    @Test
    @Config(qualifiers = "+night")
    fun shoppingListDark() = capture { Shopping(shopping()) }

    @Test
    fun shoppingListEmpty() = capture { Shopping(emptyList()) }

    // Add / edit item

    @Test
    fun addItem() = capture { AddEdit(item = null) }

    @Test
    @Config(qualifiers = "+night")
    fun addItemDark() = capture { AddEdit(item = null) }

    @Test
    fun editItem() = capture { AddEdit(item = pantry().first { it.id == "cheddar" }) }

    // Plumbing

    private fun capture(content: @Composable () -> Unit) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { LarderTheme(darkTheme = isSystemInDarkTheme()) { content() } }
        composeRule.mainClock.advanceTimeBy(FoodTrackerPreviewTester.CLOCK_MS)
        composeRule.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(compareOptions = FoodTrackerPreviewTester.compareOptions)
        )
    }

    private fun stateHolder(
        items: List<PantryItem> = pantry(),
        entries: List<ShoppingListEntry> = emptyList(),
    ): PantryStateHolder {
        val shopping = FakeShoppingListRepository(entries)
        return PantryStateHolder(
            repository = FakePantryRepository(items),
            settingsRepository = FakeSettingsRepository(AppSettings(onboardingCompleted = true)),
            shoppingListRepository = shopping,
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = FakeLocalProductRepository(),
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(entries = shopping),
            stapleRepository = FakeStapleRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext,
            todayProvider = { TODAY },
        )
    }

    @Composable
    private fun Home(items: List<PantryItem>) {
        val holder = remember { stateHolder(items) }
        PantryDashboardScreen(
            stateHolder = holder,
            onUseOne = {},
            onDeleteItem = {},
            onDeleteItems = {},
            onMoveToShoppingList = {},
            detailPane = { id ->
                ProductDetailPane(holder, id, ProductDetailActions({}, {}, {}, {}), bottomClearance = 160.dp)
            },
        )
    }

    @Composable
    private fun Shopping(entries: List<ShoppingListEntry>) {
        ShoppingListScreen(
            stateHolder = remember { stateHolder(entries = entries) },
            onScanNewEntry = {},
            onAddEntryManually = {},
        )
    }

    @Composable
    private fun AddEdit(item: PantryItem?) {
        AddEditItemScreen(
            stateHolder = remember { stateHolder() },
            initialItem = item,
            prefillBarcode = null,
            prefillName = null,
            prefillBrand = null,
            prefillImageUrl = null,
            prefillExpiryDate = null,
            prefillCategory = null,
            prefillSuggestion = null,
            settings = AppSettings(onboardingCompleted = true),
            onNavigateBack = {},
            onSaveItem = {},
            today = TODAY,
        )
    }

    private fun pantry(): List<PantryItem> = listOf(
        item("spinach", "Baby spinach", -1, DefaultStorageLocations.FRIDGE, "Vegetables"),
        item("yoghurt", "Greek yoghurt", 0, DefaultStorageLocations.FRIDGE, "Dairy", quantity = 2),
        item("milk", "Semi-skimmed milk", 1, DefaultStorageLocations.FRIDGE, "Dairy"),
        item("bread", "Sourdough bread", 2, DefaultStorageLocations.PANTRY, "Bakery"),
        item("cheddar", "Mature cheddar", 9, DefaultStorageLocations.FRIDGE, "Dairy", brand = "Wyke Farms"),
        item("peas", "Garden peas", 120, DefaultStorageLocations.FREEZER, "Frozen"),
        item("rice", "Basmati rice", 400, DefaultStorageLocations.PANTRY, "Grains", quantity = 3),
    )

    private fun item(
        id: String,
        name: String,
        days: Int,
        location: String,
        category: String,
        quantity: Int = 1,
        brand: String? = null,
    ) = PantryItem(
        id = id,
        name = name,
        brand = brand,
        expiryDate = LocalDate.fromEpochDays(TODAY.toEpochDays() + days),
        addedDate = LocalDate.fromEpochDays(TODAY.toEpochDays() - 3),
        storageLocation = location,
        category = category,
        quantity = quantity,
    )

    private fun shopping(): List<ShoppingListEntry> = listOf(
        ShoppingListEntry(id = "1", productName = "Oat milk", quantity = 2, addedDate = TODAY, sortOrder = 1.0),
        ShoppingListEntry(id = "2", productName = "Free-range eggs", addedDate = TODAY, sortOrder = 2.0),
        ShoppingListEntry(id = "3", productName = "Tomatoes", addedDate = TODAY, sortOrder = 3.0),
        ShoppingListEntry(id = "4", productName = "Olive oil", addedDate = TODAY, sortOrder = 4.0),
    )

    private companion object {
        /** Every date on these screens counts from here, never from the real clock. */
        val TODAY = LocalDate(2026, 11, 4)
    }
}
