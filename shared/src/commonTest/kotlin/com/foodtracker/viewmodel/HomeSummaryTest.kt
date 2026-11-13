package com.foodtracker.viewmodel

import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.SortOption
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSummaryTest {

    private val today = LocalDate(2026, 11, 4)

    private fun dated(days: Int) = LocalDate.fromEpochDays(today.toEpochDays() + days)

    private fun row(days: Int, name: String = "Item $days", location: String = "Fridge") = PantryRow(
        item = PantryItem(id = name, name = name, expiryDate = dated(days), addedDate = today, storageLocation = location),
        daysRemaining = days,
        status = ExpiryStatus.compute(days),
        categoryDisplayName = null,
        sortName = name.lowercase()
    )

    @Test
    fun testEmptyPantryHasNoSentence() {
        assertEquals(HomeLede.None, HomeSummary.lede(emptyList()))
    }

    @Test
    fun testPastDateItemsCountOnceSomethingIsDueByTomorrow() {
        assertEquals(HomeLede.UseByTomorrow(3), HomeSummary.lede(listOf(row(-1), row(0), row(1), row(2))))
    }

    @Test
    fun testOneDueTomorrowIsSingular() {
        assertEquals(HomeLede.UseByTomorrow(1), HomeSummary.lede(listOf(row(1), row(30))))
    }

    @Test
    fun testOnlyPastDateItemsGetTheirOwnSentence() {
        assertEquals(HomeLede.PastDate(2), HomeSummary.lede(listOf(row(-3), row(-1), row(2))))
        assertEquals(HomeLede.PastDate(1), HomeSummary.lede(listOf(row(-1), row(30))))
    }

    @Test
    fun testNextUpNamesTheSoonestItemWithinTheWeek() {
        val lede = HomeSummary.lede(listOf(row(30), row(5, "Salmon"), row(2, "Milk")))
        assertEquals(HomeLede.NextUp("Milk", dated(2)), lede)
    }

    @Test
    fun testNextUpBreaksADateTieByName() {
        val lede = HomeSummary.lede(listOf(row(3, "Yoghurt"), row(3, "Apples")))
        assertEquals(HomeLede.NextUp("Apples", dated(3)), lede)
    }

    @Test
    fun testTheWeekEndsAfterSevenDays() {
        assertEquals(HomeLede.NextUp("Last", dated(7)), HomeSummary.lede(listOf(row(7, "Last"))))
        assertEquals(HomeLede.NothingThisWeek, HomeSummary.lede(listOf(row(8))))
    }

    @Test
    fun testExpirySortSplitsIntoDateSectionsAndDropsEmptyOnes() {
        val sections = HomeSummary.sections(listOf(row(-1), row(1), row(8)), SortOption.EXPIRY_DATE)
        assertEquals(listOf<HomeSectionTitle?>(HomeSectionTitle.UseFirst, HomeSectionTitle.Later), sections.map { it.title })
        assertEquals(2, sections.first().rows.size)
    }

    @Test
    fun testSectionBoundariesFollowTheLede() {
        val sections = HomeSummary.sections(listOf(row(1), row(2), row(7), row(8)), SortOption.EXPIRY_DATE)
        assertEquals(listOf(1, 2, 1), sections.map { it.rows.size })
    }

    @Test
    fun testNameAndDateAddedSortsAreOneFlatList() {
        val rows = listOf(row(8), row(-1))
        assertEquals(listOf(HomeSection(null, rows)), HomeSummary.sections(rows, SortOption.NAME))
        assertEquals(listOf(HomeSection(null, rows)), HomeSummary.sections(rows, SortOption.DATE_ADDED))
    }

    @Test
    fun testLocationSortHasOneSectionPerPlaceInListOrder() {
        val rows = listOf(row(1, "a", "Freezer"), row(2, "b", "Fridge"), row(3, "c", "Fridge"))
        val sections = HomeSummary.sections(rows, SortOption.STORAGE_LOCATION)
        assertEquals(
            listOf<HomeSectionTitle?>(HomeSectionTitle.Place("Freezer"), HomeSectionTitle.Place("Fridge")),
            sections.map { it.title }
        )
    }

    @Test
    fun testNoRowsMakeNoSections() {
        SortOption.entries.forEach { assertEquals(emptyList(), HomeSummary.sections(emptyList(), it)) }
    }

    @Test
    fun testCountsCapAtNineHundredAndNinetyNine() {
        assertEquals("0", HomeSummary.cappedCount(0))
        assertEquals("999", HomeSummary.cappedCount(999))
        assertEquals("999+", HomeSummary.cappedCount(1000))
    }
}
