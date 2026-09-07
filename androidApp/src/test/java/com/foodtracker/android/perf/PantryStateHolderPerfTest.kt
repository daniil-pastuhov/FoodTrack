package com.foodtracker.android.perf

import com.foodtracker.model.AppSettings
import com.foodtracker.model.SortOption
import com.foodtracker.perf.PerfDataset
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.StatusFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records how expensive the pantry filter/sort pipeline is, at several list sizes.
 *
 * Two different costs are measured separately, because the optimization work affects them very
 * differently and reporting only one would misrepresent the result:
 *
 *  - **Main-thread cost** ([interactionBlocksCallerThread]) — how long a filter interaction
 *    blocks the thread that triggered it. On the real app that thread is the UI thread, so this
 *    is what the user actually feels as a dropped frame. Moving the work to a background
 *    dispatcher collapses this to almost nothing regardless of how fast the algorithm is.
 *  - **Total algorithmic cost** ([filterPipelineThroughput]) — how much work the pipeline does
 *    in total, measured with an unconfined dispatcher so every stage runs inline on the calling
 *    thread. This is what improves from the map-based category lookup, the single-pass counters
 *    and the precomputed sort keys, and it is *not* helped by moving threads around.
 *
 * Neither test asserts a wall-clock threshold — they record numbers and assert only on
 * correctness, so a busy machine can never turn them into a spurious failure.
 */
class PantryStateHolderPerfTest {

    private val sizes = listOf(200, 1_000, 5_000)

    private class Fixture(itemCount: Int, unconfined: Boolean) {
        val pantry = FakePantryRepository(PerfDataset.pantryItems(itemCount))
        val settings = FakeSettingsRepository(AppSettings())
        val shopping = FakeShoppingListRepository(PerfDataset.shoppingListEntries(40))
        val names = FakeProductNameRepository()
        val products = FakeLocalProductRepository()
        val categories = FakeCategoryPreferenceRepository(PerfDataset.categoryPreferences())

        val scope: CoroutineScope = if (unconfined) {
            CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        } else {
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }

        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = settings,
            shoppingListRepository = shopping,
            productNameRepository = names,
            localProductRepository = products,
            categoryPreferenceRepository = categories,
            uncategorizedLabel = "Other",
            coroutineScope = scope
        )

        fun close() = scope.cancel()
    }

    /**
     * Measures only the caller-thread duration of a filter interaction: the state holder runs on
     * a real background dispatcher, exactly as it does in the app, so whatever this test sees is
     * work happening synchronously inside the click handler.
     */
    @Test
    fun interactionBlocksCallerThread() {
        sizes.forEach { size ->
            val fixture = Fixture(itemCount = size, unconfined = false)
            try {
                // Let the initial load settle so the first measured iteration isn't paying for
                // an empty-to-populated transition the others don't.
                awaitItemsLoaded(fixture, size)

                var counter = 0
                PerfHarness.measure("mainThread.searchQueryChanged", size) {
                    fixture.stateHolder.onSearchQueryChanged("Milk${counter++ % 7}")
                }

                var toggle = false
                PerfHarness.measure("mainThread.statusFilterSelected", size) {
                    toggle = !toggle
                    fixture.stateHolder.onStatusFilterSelected(
                        if (toggle) StatusFilter.EXPIRING_SOON else StatusFilter.ALL
                    )
                }

                var sortIndex = 0
                PerfHarness.measure("mainThread.sortOptionSelected", size) {
                    fixture.stateHolder.onSortOptionSelected(
                        SortOption.entries[sortIndex++ % SortOption.entries.size]
                    )
                }
            } finally {
                fixture.close()
            }
        }
    }

    /**
     * Measures the whole pipeline end to end. The unconfined dispatcher makes every flow stage
     * run inline, so the measured duration is the total work done rather than the portion that
     * happened to land on the caller's thread.
     */
    @Test
    fun filterPipelineThroughput() {
        sizes.forEach { size ->
            val fixture = Fixture(itemCount = size, unconfined = true)
            try {
                assertEquals(
                    "fixture should have loaded all items before measuring",
                    size,
                    fixture.stateHolder.uiState.value.items.size
                )

                var counter = 0
                PerfHarness.measure("pipeline.searchQueryChanged", size) {
                    fixture.stateHolder.onSearchQueryChanged("Milk${counter++ % 7}")
                    fixture.stateHolder.uiState.value.filteredItems.size
                }

                var toggle = false
                PerfHarness.measure(
                    "pipeline.statusFilterSelected",
                    size,
                    // Search narrows the list to almost nothing, which would make the following
                    // measurements look artificially cheap; clear it first.
                    reset = { fixture.stateHolder.onSearchQueryChanged("") }
                ) {
                    toggle = !toggle
                    fixture.stateHolder.onStatusFilterSelected(
                        if (toggle) StatusFilter.EXPIRING_SOON else StatusFilter.ALL
                    )
                    fixture.stateHolder.uiState.value.filteredItems.size
                }

                var sortIndex = 0
                PerfHarness.measure("pipeline.sortOptionSelected", size) {
                    fixture.stateHolder.onSortOptionSelected(
                        SortOption.entries[sortIndex++ % SortOption.entries.size]
                    )
                    fixture.stateHolder.uiState.value.filteredItems.size
                }

                // A whole-table re-emission, which is what every single pantry write costs:
                // Room's invalidation is table-granular, so one upsert re-runs all of this.
                val allItems = fixture.pantry.itemsFlow.value
                val truncated = allItems.dropLast(1)
                PerfHarness.measure(
                    "pipeline.repositoryReemission",
                    size,
                    reset = { fixture.pantry.itemsFlow.value = truncated }
                ) {
                    fixture.pantry.itemsFlow.value = allItems
                    fixture.stateHolder.uiState.value.filteredItems.size
                }
            } finally {
                fixture.close()
            }
        }
    }

    /** Guards the measurements above: if filtering silently stopped working, the numbers would
     *  improve dramatically and mean nothing. */
    @Test
    fun filteringStillProducesCorrectResults() {
        val fixture = Fixture(itemCount = 1_000, unconfined = true)
        try {
            val state = fixture.stateHolder.uiState
            assertEquals(1_000, state.value.items.size)
            assertEquals(1_000, state.value.filteredItems.size)
            assertEquals(
                "counters should account for every item exactly once",
                1_000,
                state.value.expiredCount + state.value.expiringSoonCount + state.value.freshCount
            )

            fixture.stateHolder.onStatusFilterSelected(StatusFilter.EXPIRED)
            assertEquals(state.value.expiredCount, state.value.filteredItems.size)

            fixture.stateHolder.onStatusFilterSelected(StatusFilter.ALL)
            fixture.stateHolder.onSearchQueryChanged("Milk")
            assertTrue(
                "search should narrow the list",
                state.value.filteredItems.size < state.value.items.size
            )

            fixture.stateHolder.onSearchQueryChanged("")
            fixture.stateHolder.onSortOptionSelected(SortOption.NAME)
            val names = state.value.filteredItems.map { it.name.lowercase() }
            assertEquals("NAME sort should order by lowercased name", names.sorted(), names)
        } finally {
            fixture.close()
        }
    }

    private fun awaitItemsLoaded(fixture: Fixture, expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (fixture.stateHolder.uiState.value.filteredItems.size != expected) {
            check(System.currentTimeMillis() < deadline) {
                "state holder never loaded $expected items " +
                    "(saw ${fixture.stateHolder.uiState.value.filteredItems.size})"
            }
            Thread.sleep(5)
        }
    }
}
