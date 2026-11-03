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
import com.foodtracker.model.UNCATEGORIZED_ORIGIN
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Persistence of the category drag-and-drop order (Settings → Categories).
 *
 * The case that used to break: the "Other" bucket is registered at [Double.MAX_VALUE] so it
 * always sorts last, and the old reorder gave the dragged row `before + 1.0` when it landed at
 * the bottom — which for MAX_VALUE is MAX_VALUE again. The drag looked right until the screen
 * was left and re-entered, at which point the row was back where it started.
 */
class CategoryReorderTest {

    private class Fixture(
        preferences: List<CategoryPreference> = emptyList(),
        items: List<PantryItem> = emptyList()
    ) {
        val categories = FakeCategoryPreferenceRepository(preferences)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = FakePantryRepository(items),
            settingsRepository = FakeSettingsRepository(AppSettings()),
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = FakeLocalProductRepository(),
            categoryPreferenceRepository = categories,
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        val visibleOrder: List<String>
            get() = stateHolder.uiState.value.categoryRows.map { it.preference.displayName }

        fun sortOrderOf(originName: String): Double =
            categories.preferencesFlow.value.first { it.originName == originName }.sortOrder

        fun close() = scope.cancel()
    }

    private fun pantryItem(id: String, category: String) = PantryItem(
        id = id,
        name = id,
        expiryDate = DateUtils.today(),
        addedDate = DateUtils.today(),
        category = category
    )

    @Test
    fun testDraggingACategoryBelowTheOtherBucketPersists() {
        val fixture = Fixture(
            preferences = listOf(
                CategoryPreference("bathroom", "Bathroom", 0.0),
                CategoryPreference(UNCATEGORIZED_ORIGIN, "Other", Double.MAX_VALUE)
            )
        )
        try {
            assertEquals(listOf("Bathroom", "Other"), fixture.visibleOrder)

            fixture.stateHolder.reorderCategoryPreferences(listOf(UNCATEGORIZED_ORIGIN, "bathroom"))

            assertEquals(listOf("Other", "Bathroom"), fixture.visibleOrder)
            assertTrue(fixture.sortOrderOf("bathroom") > fixture.sortOrderOf(UNCATEGORIZED_ORIGIN))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testReorderMovesEveryOriginMergedOntoTheSameRow() {
        val fixture = Fixture(
            preferences = listOf(
                CategoryPreference("en:dairy", "Dairy", 0.0),
                CategoryPreference("no:meieri", "Dairy", 1.0),
                CategoryPreference("en:snacks", "Snacks", 2.0)
            )
        )
        try {
            // "Other" is auto-registered by the state holder itself and sorts last.
            assertEquals(listOf("Dairy", "Snacks", "Other"), fixture.visibleOrder)

            fixture.stateHolder.reorderCategoryPreferences(
                listOf("en:snacks", "en:dairy", UNCATEGORIZED_ORIGIN)
            )

            assertEquals(listOf("Snacks", "Dairy", "Other"), fixture.visibleOrder)
            // Both origins of the merged row move together, so the row can't split in two.
            assertEquals(fixture.sortOrderOf("en:dairy"), fixture.sortOrderOf("no:meieri"), 0.0)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testNewlyRegisteredCategoriesGetDistinctSortOrders() {
        // Registration reads the highest sort order in use and adds one. Counting the "Other"
        // sentinel's MAX_VALUE there handed every new category that same value, leaving nothing
        // for a drag to reorder.
        val fixture = Fixture(
            items = listOf(
                pantryItem("a", "en:dairy"),
                pantryItem("b", "en:snacks")
            )
        )
        try {
            val registered = fixture.categories.preferencesFlow.value
                .filterNot { it.originName == UNCATEGORIZED_ORIGIN }
            assertEquals(2, registered.size)
            assertEquals(2, registered.map { it.sortOrder }.distinct().size)
            assertTrue(registered.none { it.sortOrder == Double.MAX_VALUE })
        } finally {
            fixture.close()
        }
    }
}
