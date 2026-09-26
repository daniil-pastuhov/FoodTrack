package com.foodtracker.util

import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarMonthTest {

    @Test
    fun monthGridReturnsExactly42Days() {
        val grid = monthGrid(LocalDate(2026, 9, 1))
        assertEquals(42, grid.size)
    }

    @Test
    fun monthGridHasNoLeadingDaysWhenMonthStartsOnMonday() {
        // 2026-06-01 is a Monday.
        val grid = monthGrid(LocalDate(2026, 6, 1))
        assertEquals(LocalDate(2026, 6, 1), grid.first().date)
        assertTrue(grid.first().inCurrentMonth)
    }

    @Test
    fun monthGridPadsLeadingDaysFromPreviousMonthWhenMonthStartsMidWeek() {
        // 2026-09-01 is a Tuesday, so the grid should start on 2026-08-31 (Monday).
        val grid = monthGrid(LocalDate(2026, 9, 1))
        assertEquals(LocalDate(2026, 8, 31), grid.first().date)
        assertEquals(false, grid.first().inCurrentMonth)
        assertEquals(LocalDate(2026, 9, 1), grid[1].date)
        assertTrue(grid[1].inCurrentMonth)
    }

    @Test
    fun monthGridSpansYearBoundaryCorrectly() {
        // January 2027 starts on a Friday, so the grid should reach back into December 2026.
        val grid = monthGrid(LocalDate(2027, 1, 15))
        assertEquals(LocalDate(2026, 12, 28), grid.first().date)
        assertEquals(false, grid.first().inCurrentMonth)
        assertTrue(grid.any { it.date == LocalDate(2027, 1, 1) && it.inCurrentMonth })
    }

    @Test
    fun groupItemsByExpiryDateGroupsSameDateItemsTogether() {
        val date1 = LocalDate(2026, 9, 3)
        val date2 = LocalDate(2026, 9, 5)
        val items = listOf(
            PantryItem(id = "1", name = "Milk", expiryDate = date1, addedDate = date1),
            PantryItem(id = "2", name = "Yogurt", expiryDate = date1, addedDate = date1),
            PantryItem(id = "3", name = "Bread", expiryDate = date2, addedDate = date2)
        )

        val grouped = groupItemsByExpiryDate(items)

        assertEquals(2, grouped.size)
        assertEquals(listOf("1", "2"), grouped.getValue(date1).map { it.id })
        assertEquals(listOf("3"), grouped.getValue(date2).map { it.id })
    }

    @Test
    fun groupItemsByExpiryDateReturnsEmptyMapForEmptyList() {
        assertEquals(emptyMap(), groupItemsByExpiryDate(emptyList()))
    }

    @Test
    fun weekDaysRunMondayToSundayAroundTheGivenDate() {
        // 2026-09-26 is a Saturday.
        val days = weekDays(LocalDate(2026, 9, 26))
        assertEquals(LocalDate(2026, 9, 21), days.first())
        assertEquals(LocalDate(2026, 9, 27), days.last())
        assertEquals(7, days.size)
    }

    @Test
    fun weekStartOfAMondayIsThatMonday() {
        assertEquals(LocalDate(2026, 9, 21), weekStart(LocalDate(2026, 9, 21)))
    }

    @Test
    fun weekDaysSpanYearBoundary() {
        // 2027-01-01 is a Friday.
        val days = weekDays(LocalDate(2027, 1, 1))
        assertEquals(LocalDate(2026, 12, 28), days.first())
        assertEquals(LocalDate(2027, 1, 3), days.last())
    }
}
