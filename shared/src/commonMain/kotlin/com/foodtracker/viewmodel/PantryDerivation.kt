package com.foodtracker.viewmodel

import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.CategoryPreferenceIndex
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.DEFAULT_SHOPPING_SECTION
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import com.foodtracker.model.resolveShoppingSectionId
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

    /**
     * Flattens [entries] into sectioned rows: the default section first, then the user's sections
     * by their order. Every section emits a header - an empty one too, so it can be dropped
     * onto - and its items only while it is expanded. Items keep the order they arrive in
     * (the repository's `sortOrder`, then newest first), which is the user's manual order.
     *
     * [collapsedOverride], when given, replaces each section's stored `collapsed` flag; the
     * screen uses it to fold sections away for the duration of a drag without persisting it.
     */
    fun buildShoppingRows(
        entries: List<ShoppingListEntry>,
        sections: List<ShoppingSection>,
        collapsedOverride: ((ShoppingSection) -> Boolean)? = null
    ): List<ShoppingRow> {
        val ordered = orderedShoppingSections(sections)
        val ids = ordered.mapTo(HashSet()) { it.id }
        val bySection = entries.groupBy { resolveShoppingSectionId(it.sectionId, ids) }
        return buildList {
            ordered.forEach { section ->
                val group = bySection[section.id].orEmpty()
                add(ShoppingRow.Header(section, group.size))
                val collapsed = collapsedOverride?.invoke(section) ?: section.collapsed
                if (!collapsed) group.forEach { add(ShoppingRow.Item(it, section.id)) }
            }
        }
    }

    /** [sections] in display order, with the default section first (and present even if the
     *  store somehow lost it). */
    fun orderedShoppingSections(sections: List<ShoppingSection>): List<ShoppingSection> {
        val default = sections.firstOrNull { it.isDefault } ?: DEFAULT_SHOPPING_SECTION
        return listOf(default) + sections
            .filterNot { it.isDefault }
            .sortedWith(compareBy<ShoppingSection> { it.sortOrder }.thenBy { it.name.lowercase() })
    }

    /** One row per user-visible category, carrying the other raw origins merged onto that same
     *  display name as a ready-made subtitle. Disabled categories come after every enabled one,
     *  each part in the user's order. */
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
            .sortedWith(compareBy<CategoryRow>({ !it.preference.enabled }, { it.preference.sortOrder }))

    /** Whether the row at [from] in [rows] (as [buildCategoryRows] orders them) may take the
     *  place of the row at [to]. Only within its own part: enabled rows always come before
     *  disabled ones, so a move across would save a new order and then snap straight back. */
    fun canMoveCategoryRow(rows: List<CategoryRow>, from: Int, to: Int): Boolean =
        from in rows.indices && to in rows.indices &&
            rows[from].preference.enabled == rows[to].preference.enabled
}
