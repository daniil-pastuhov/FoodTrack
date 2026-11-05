package com.foodtracker.viewmodel

import com.foodtracker.model.SortOption
import kotlinx.datetime.LocalDate

/** The sentence at the top of Home. Each platform words it. */
sealed interface HomeLede {
    /** An empty pantry has no sentence: the empty state speaks. */
    data object None : HomeLede
    /** [count] includes past-date items, because they want using as much as today's. */
    data class UseByTomorrow(val count: Int) : HomeLede
    data class PastDate(val count: Int) : HomeLede
    data class NextUp(val name: String, val date: LocalDate) : HomeLede
    data object NothingThisWeek : HomeLede
}

/** What heads a group of rows on Home. [Place.location] is the stored key, not a label. */
sealed interface HomeSectionTitle {
    data object UseFirst : HomeSectionTitle
    data object LaterThisWeek : HomeSectionTitle
    data object Later : HomeSectionTitle
    data class Place(val location: String) : HomeSectionTitle
}

/** A run of rows on Home. A null [title] is one flat list with no heading. */
data class HomeSection(val title: HomeSectionTitle?, val rows: List<PantryRow>)

/** Home's rules, kept apart from the UI so they can be tested. iOS repeats them in `HomeSummary.swift`. */
object HomeSummary {
    /** Today and tomorrow are "use first". */
    private const val USE_FIRST_LAST_DAY = 1

    /** The sentence and "Later this week" look this far ahead. */
    private const val WEEK_LAST_DAY = 7

    /** Counts past this read "999+". */
    private const val COUNT_CAP = 999

    /**
     * Always takes the whole pantry, so the sentence doesn't jump when a filter changes.
     *
     * Past-date items only count towards "to use by tomorrow" when something is actually due
     * today or tomorrow. On their own they get the plainer "past its date" sentence.
     */
    fun lede(allRows: List<PantryRow>): HomeLede {
        if (allRows.isEmpty()) return HomeLede.None
        val past = allRows.count { it.daysRemaining < 0 }
        val dueSoon = allRows.count { it.daysRemaining in 0..USE_FIRST_LAST_DAY }
        if (dueSoon > 0) return HomeLede.UseByTomorrow(past + dueSoon)
        if (past > 0) return HomeLede.PastDate(past)
        val next = allRows
            .filter { it.daysRemaining <= WEEK_LAST_DAY }
            .minWithOrNull(compareBy<PantryRow> { it.daysRemaining }.thenBy { it.sortName })
            ?: return HomeLede.NothingThisWeek
        return HomeLede.NextUp(next.item.name, next.item.expiryDate)
    }

    /**
     * Groups already sorted [rows] for display. The date sections belong to the expiry sort, and
     * the location sort gets one section per place. The other sorts are one flat list. Empty
     * sections are left out.
     */
    fun sections(rows: List<PantryRow>, sortOption: SortOption): List<HomeSection> {
        if (rows.isEmpty()) return emptyList()
        return when (sortOption) {
            SortOption.EXPIRY_DATE -> listOf(
                HomeSection(HomeSectionTitle.UseFirst, rows.filter { it.daysRemaining <= USE_FIRST_LAST_DAY }),
                HomeSection(
                    HomeSectionTitle.LaterThisWeek,
                    rows.filter { it.daysRemaining in (USE_FIRST_LAST_DAY + 1)..WEEK_LAST_DAY }
                ),
                HomeSection(HomeSectionTitle.Later, rows.filter { it.daysRemaining > WEEK_LAST_DAY })
            ).filter { it.rows.isNotEmpty() }
            SortOption.STORAGE_LOCATION -> rows
                .groupBy { it.item.storageLocation }
                .map { (location, placeRows) -> HomeSection(HomeSectionTitle.Place(location), placeRows) }
            SortOption.NAME, SortOption.DATE_ADDED -> listOf(HomeSection(null, rows))
        }
    }

    /** The number on a chip. Screen readers get the real one. */
    fun cappedCount(count: Int): String = if (count > COUNT_CAP) "$COUNT_CAP+" else count.toString()
}
