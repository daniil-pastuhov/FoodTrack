package com.foodtracker.viewmodel

import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.CategoryPreferenceIndex
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.SortOption
import com.foodtracker.model.categoryPreferenceSortOrder
import com.foodtracker.model.index
import com.foodtracker.model.resolveCategoryDisplayName
import com.foodtracker.util.DateUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/**
 * The pure functions behind [PantryStateHolder]'s derived state.
 *
 * Separated from the state holder so this logic can be measured and tested on its own, without
 * a coroutine scope, flows, or fake repositories in the way — the flow plumbing's fixed cost per
 * emission is large enough to hide the cost of the work itself at realistic pantry sizes.
 */
object PantryDerivation {

    /** The filter-independent half of the derived state — see [PantryStateHolder.basePantry]. */
    data class Base(
        val items: List<PantryItem>,
        val allRows: List<PantryRow>,
        val itemsByExpiryDate: Map<LocalDate, List<PantryRow>>,
        val statusByExpiryDate: Map<LocalDate, ExpiryStatus>,
        val agenda: List<AgendaSection>,
        val availableCategories: List<String>,
        val expiredCount: Int,
        val expiringSoonCount: Int,
        val freshCount: Int,
        val expiringSoonDays: Int,
        /** The date [allRows] and [agenda] were derived against. */
        val today: LocalDate
    )

    fun deriveBase(
        items: List<PantryItem>,
        preferences: List<CategoryPreference>,
        expiringSoonDays: Int,
        today: LocalDate = DateUtils.today()
    ): Base {
        val index = preferences.index()

        // One pass builds every per-item value the UI needs. Each of these used to be derived
        // again per card in composition, and the category name cost a linear scan of the whole
        // preference list every time.
        val allRows = items.map { item ->
            val daysRemaining = item.daysRemaining(today)
            PantryRow(
                item = item,
                daysRemaining = daysRemaining,
                status = ExpiryStatus.compute(daysRemaining, expiringSoonDays),
                categoryDisplayName = resolveCategoryDisplayName(item.category, index),
                sortName = item.name.lowercase()
            )
        }

        // One pass for all three counters, rather than three full passes each recomputing
        // daysRemaining from scratch.
        var expired = 0
        var expiringSoon = 0
        var fresh = 0
        allRows.forEach { row ->
            when {
                row.daysRemaining < 0 -> expired++
                row.daysRemaining <= expiringSoonDays -> expiringSoon++
                else -> fresh++
            }
        }

        val itemsByExpiryDate = allRows.groupBy { it.item.expiryDate }

        return Base(
            items = items,
            allRows = allRows,
            itemsByExpiryDate = itemsByExpiryDate,
            // ExpiryStatus is declared most-urgent-first, so the lowest ordinal on a date is the
            // most urgent status expiring that day.
            statusByExpiryDate = itemsByExpiryDate.mapValues { (_, rows) ->
                rows.minBy { it.status.ordinal }.status
            },
            agenda = buildAgenda(allRows, today),
            availableCategories = availableCategories(allRows, index),
            expiredCount = expired,
            expiringSoonCount = expiringSoon,
            freshCount = fresh,
            expiringSoonDays = expiringSoonDays,
            today = today
        )
    }

    /** Groups [rows] into the Calendar's agenda sections (see [AgendaBucket]), dropping empty
     *  ones. Reads the rows' precomputed [PantryRow.daysRemaining], so [today] must be the same
     *  date those were derived against. */
    fun buildAgenda(rows: List<PantryRow>, today: LocalDate): List<AgendaSection> {
        // Days from today to this week's Sunday: 0 on a Sunday, 6 on a Monday.
        val daysLeftInWeek = 7 - today.dayOfWeek.isoDayNumber
        val byBucket = rows.groupBy { row ->
            val days = row.daysRemaining
            when {
                days < 0 -> AgendaBucket.OVERDUE
                days == 0 -> AgendaBucket.TODAY
                days == 1 -> AgendaBucket.TOMORROW
                days <= daysLeftInWeek -> AgendaBucket.THIS_WEEK
                days <= daysLeftInWeek + 7 -> AgendaBucket.NEXT_WEEK
                else -> AgendaBucket.LATER
            }
        }
        val order = compareBy<PantryRow> { it.item.expiryDate.toEpochDays() }.thenBy { it.sortName }
        return AgendaBucket.entries.mapNotNull { bucket ->
            byBucket[bucket]?.let { AgendaSection(bucket, it.sortedWith(order)) }
        }
    }

    fun filterAndSort(
        rows: List<PantryRow>,
        filter: FilterCriteria,
        expiringSoonDays: Int,
        sortOption: SortOption
    ): List<PantryRow> {
        val query = filter.query.takeIf { it.isNotBlank() }
        return rows.filter { row ->
            val item = row.item

            // Location filter
            val matchesLoc = filter.location == null || item.storageLocation == filter.location

            // Category filter. Matches on the resolved display name, so that multiple origins
            // renamed to the same name (or disabled categories folding into "Other") are treated
            // as a single selectable group. Already resolved onto the row.
            val matchesCategory = filter.category == null || row.categoryDisplayName == filter.category

            // Status filter
            val matchesStatus = when (filter.status) {
                StatusFilter.ALL -> true
                StatusFilter.EXPIRING_SOON -> row.daysRemaining in 0..expiringSoonDays
                StatusFilter.EXPIRED -> row.daysRemaining < 0
                StatusFilter.FRESH -> row.daysRemaining > expiringSoonDays
            }

            // Search query
            val matchesQuery = query == null ||
                    item.name.contains(query, ignoreCase = true) ||
                    (item.brand?.contains(query, ignoreCase = true) == true)

            matchesLoc && matchesCategory && matchesStatus && matchesQuery
        }.sortedWith(sortComparator(sortOption))
    }

    /** Distinct resolved category names present on [rows], ordered by the user's configured
     *  category order and then alphabetically. Built here rather than in the dashboard's filter
     *  sheet, where the order lookup rescanned the whole preference list on every comparison. */
    private fun availableCategories(
        rows: List<PantryRow>,
        index: CategoryPreferenceIndex
    ): List<String> {
        val sortOrders = HashMap<String, Double>()
        rows.forEach { row ->
            val name = row.categoryDisplayName ?: return@forEach
            val order = categoryPreferenceSortOrder(row.item.category, index)
            val existing = sortOrders[name]
            if (existing == null || order < existing) sortOrders[name] = order
        }
        return sortOrders.keys.sortedWith(compareBy({ sortOrders.getValue(it) }, { it }))
    }

    // Ties always need an explicit secondary key: `items` arrives from the repository in a fixed
    // order (expiry date ascending), and List.sortedWith is stable, so any comparator that can
    // tie - most visibly DATE_ADDED, since addedDate is day-granularity and several items are
    // commonly added on the same day - would otherwise silently fall back to that upstream order
    // instead of a sort tied to the chosen option.
    //
    // Every selector reads a precomputed field. The previous version called `name.lowercase()`
    // inside the selector, allocating a fresh string on each of the O(n log n) comparisons.
    private fun sortComparator(option: SortOption): Comparator<PantryRow> = when (option) {
        SortOption.EXPIRY_DATE -> compareBy<PantryRow> { it.item.expiryDate.toEpochDays() }
            .thenBy { it.sortName }
        SortOption.NAME -> compareBy<PantryRow> { it.sortName }
            .thenByDescending { it.item.addedDate.toEpochDays() }
        SortOption.DATE_ADDED -> compareByDescending<PantryRow> { it.item.addedDate.toEpochDays() }
            .thenBy { it.sortName }
        SortOption.STORAGE_LOCATION -> compareBy<PantryRow> { it.item.storageLocation.lowercase() }
            .thenBy { it.sortName }
    }

    /** Flattens [entries] into category-grouped rows with headers, ordered by the user's
     *  configured category order. Groups fold together when several origins resolve to the same
     *  display name, and disabled categories collapse into the uncategorized group. */
    fun buildShoppingRows(
        entries: List<ShoppingListEntry>,
        preferences: List<CategoryPreference>
    ): List<ShoppingRow> {
        val index = preferences.index()
        val rows = entries.map { ShoppingRow.Item(it, resolveCategoryDisplayName(it.category, index)) }
        val grouped = rows.groupBy { it.categoryDisplayName }
        // Each group's order key is computed once here rather than inside the comparator, which
        // the previous in-composition version re-evaluated on every comparison.
        val orderByCategory = grouped.mapValues { (_, group) ->
            group.minOf { categoryPreferenceSortOrder(it.entry.category, index) }
        }
        return buildList {
            grouped.entries
                .sortedWith(
                    compareBy(
                        { orderByCategory.getValue(it.key) },
                        // Uncategorized (null) sorts last among equal order keys.
                        { it.key ?: "￿" }
                    )
                )
                .forEach { (category, group) ->
                    add(ShoppingRow.Header(category))
                    addAll(group)
                }
        }
    }

    /** One row per user-visible category, carrying the other raw origins merged onto that same
     *  display name as a ready-made subtitle. */
    fun buildCategoryRows(preferences: List<CategoryPreference>): List<CategoryRow> =
        preferences
            .groupBy { it.displayName }
            .values
            .map { group ->
                val representative = group.minBy { it.sortOrder }
                CategoryRow(
                    preference = representative,
                    mergedOrigins = group
                        .map { it.originName }
                        .filter { it != representative.displayName && it.isNotEmpty() }
                        .sorted()
                        .joinToString(", ")
                )
            }
            .sortedBy { it.preference.sortOrder }
}
