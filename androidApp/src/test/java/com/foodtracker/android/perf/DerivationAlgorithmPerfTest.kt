package com.foodtracker.android.perf

import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.SortOption
import com.foodtracker.model.resolveCategoryDisplayName
import com.foodtracker.perf.PerfDataset
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.FilterCriteria
import com.foodtracker.viewmodel.PantryDerivation
import com.foodtracker.viewmodel.StatusFilter
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Measures the filtering/derivation *algorithm* against the implementation it replaced, in the
 * same JVM, on the same data.
 *
 * This exists because neither of the other two JVM measurements answers "did the algorithm get
 * faster". `mainThread.*` collapses to nothing once the work moves to a background dispatcher,
 * regardless of how fast it is, and `pipeline.*` runs through `combine` and a flow collector,
 * whose fixed per-emission cost is large enough to dominate the work itself at realistic pantry
 * sizes. Both are useful; neither isolates the algorithm.
 *
 * [Legacy] below is the pre-optimization implementation, transcribed from git history, so the
 * comparison is direct rather than across two builds on two different days. Its correctness is
 * asserted against the current implementation before either is timed — a reference
 * implementation that has quietly drifted would make the whole comparison meaningless.
 */
class DerivationAlgorithmPerfTest {

    private val sizes = listOf(200, 1_000, 5_000)
    private val today: LocalDate = DateUtils.today()
    private val preferences: List<CategoryPreference> = PerfDataset.categoryPreferences()

    /** The implementation as it stood before this change, kept verbatim for comparison. */
    private object Legacy {
        fun counts(items: List<PantryItem>, today: LocalDate, expiringSoonDays: Int): Triple<Int, Int, Int> =
            Triple(
                items.count { it.daysRemaining(today) < 0 },
                items.count { it.daysRemaining(today) in 0..expiringSoonDays },
                items.count { it.daysRemaining(today) > expiringSoonDays }
            )

        fun computeFiltered(
            items: List<PantryItem>,
            location: String?,
            category: String?,
            filter: StatusFilter,
            query: String,
            today: LocalDate,
            expiringSoonDays: Int,
            sortOption: SortOption,
            preferences: List<CategoryPreference>
        ): List<PantryItem> = items.filter { item ->
            val matchesLoc = location == null || item.storageLocation == location
            // Resolved for every item even when no category filter is active, and each call is a
            // linear scan of the preference list.
            val itemDisplay = resolveCategoryDisplayName(item.category, preferences)
            val matchesCategory = category == null || itemDisplay == category
            val matchesStatus = when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.EXPIRING_SOON -> item.daysRemaining(today) in 0..expiringSoonDays
                StatusFilter.EXPIRED -> item.daysRemaining(today) < 0
                StatusFilter.FRESH -> item.daysRemaining(today) > expiringSoonDays
            }
            val matchesQuery = query.isBlank() ||
                    item.name.contains(query, ignoreCase = true) ||
                    (item.brand?.contains(query, ignoreCase = true) == true)
            matchesLoc && matchesCategory && matchesStatus && matchesQuery
        }.sortedWith(sortComparator(sortOption))

        // `lowercase()` inside the selector allocates a fresh string on every comparison.
        fun sortComparator(option: SortOption): Comparator<PantryItem> = when (option) {
            SortOption.EXPIRY_DATE -> compareBy<PantryItem> { it.expiryDate.toEpochDays() }
                .thenBy { it.name.lowercase() }
            SortOption.NAME -> compareBy<PantryItem> { it.name.lowercase() }
                .thenByDescending { it.addedDate.toEpochDays() }
            SortOption.DATE_ADDED -> compareByDescending<PantryItem> { it.addedDate.toEpochDays() }
                .thenBy { it.name.lowercase() }
            SortOption.STORAGE_LOCATION -> compareBy<PantryItem> { it.storageLocation.lowercase() }
                .thenBy { it.name.lowercase() }
        }
    }

    /**
     * The whole-list work a single pantry write triggers: counters plus the filtered list.
     * Room's invalidation is table-granular, so one `upsert` re-runs all of it.
     */
    @Test
    fun fullDerivationOnDataChange() {
        sizes.forEach { size ->
            val items = PerfDataset.pantryItems(size, today)
            val filter = FilterCriteria()

            assertSameResult(items, filter)

            PerfHarness.measure("algorithm.fullDerivation.legacy", size) {
                val counts = Legacy.counts(items, today, 3)
                val filtered = Legacy.computeFiltered(
                    items, null, null, StatusFilter.ALL, "", today, 3, SortOption.EXPIRY_DATE, preferences
                )
                // The legacy side once also priced the Calendar's per-date grouping. The Calendar
                // and its grouping are gone from both sides now, so neither includes it.
                counts.first + filtered.size
            }

            PerfHarness.measure("algorithm.fullDerivation.current", size) {
                val base = PantryDerivation.deriveBase(items, preferences, 3, today)
                val filtered = PantryDerivation.filterAndSort(base.allRows, filter, 3, SortOption.EXPIRY_DATE)
                base.expiredCount + filtered.size
            }
        }
    }

    /**
     * The work a filter tap or a keystroke triggers.
     *
     * The current implementation only re-filters and re-sorts already-derived rows here, which
     * is the whole reason the derivation is split in two — so this is not a like-for-like
     * comparison of the same code, it is the comparison that matters: what each version actually
     * does when the user changes a filter.
     */
    @Test
    fun filterChangeOnly() {
        sizes.forEach { size ->
            val items = PerfDataset.pantryItems(size, today)
            val rows = PantryDerivation.deriveBase(items, preferences, 3, today).allRows

            PerfHarness.measure("algorithm.filterChange.legacy", size) {
                Legacy.computeFiltered(
                    items, null, null, StatusFilter.EXPIRING_SOON, "", today, 3, SortOption.NAME, preferences
                ).size
            }

            PerfHarness.measure("algorithm.filterChange.current", size) {
                PantryDerivation.filterAndSort(
                    rows,
                    FilterCriteria(status = StatusFilter.EXPIRING_SOON),
                    3,
                    SortOption.NAME
                ).size
            }
        }
    }

    /** A comparison against a reference implementation is only meaningful if both still agree. */
    private fun assertSameResult(items: List<PantryItem>, filter: FilterCriteria) {
        val base = PantryDerivation.deriveBase(items, preferences, 3, today)
        val legacyCounts = Legacy.counts(items, today, 3)

        assertEquals("expired count", legacyCounts.first, base.expiredCount)
        assertEquals("expiring soon count", legacyCounts.second, base.expiringSoonCount)
        assertEquals("fresh count", legacyCounts.third, base.freshCount)

        SortOption.entries.forEach { sortOption ->
            StatusFilter.entries.forEach { status ->
                val legacy = Legacy.computeFiltered(
                    items, null, null, status, "milk", today, 3, sortOption, preferences
                ).map { it.id }
                val current = PantryDerivation.filterAndSort(
                    base.allRows, filter.copy(status = status, query = "milk"), 3, sortOption
                ).map { it.id }
                assertEquals("$sortOption / $status should produce the same list", legacy, current)
            }
        }
    }
}
