package com.foodtracker.android.ui.screens

import com.foodtracker.android.R
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.util.weekStart
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarLogicTest {
    private val today = LocalDate(2026, 11, 4)

    @Test
    fun testEachStatusHasItsOwnShapeAndFreshHasNone() {
        assertEquals(DayMarkShape.Square, dayMarkShape(ExpiryStatus.EXPIRED))
        assertEquals(DayMarkShape.Circle, dayMarkShape(ExpiryStatus.EXPIRING_TODAY))
        assertEquals(DayMarkShape.Diamond, dayMarkShape(ExpiryStatus.EXPIRING_SOON))
        assertEquals(DayMarkShape.None, dayMarkShape(ExpiryStatus.FRESH))
    }

    @Test
    fun testCountCapsAboveNine() {
        assertEquals("1", dayCountLabel(1))
        assertEquals("9", dayCountLabel(9))
        assertEquals("9+", dayCountLabel(10))
        assertEquals("9+", dayCountLabel(120))
    }

    @Test
    fun testTappingADaySelectsItAndTappingItAgainWidensBack() {
        assertEquals(today, toggledDay(null, today))
        assertNull(toggledDay(today, today))
        val other = LocalDate(2026, 11, 6)
        assertEquals(other, toggledDay(today, other))
    }

    @Test
    fun testPagingBackToTodaysMonthRestsOnToday() {
        assertEquals(today, pagedAnchor(LocalDate(2026, 12, 4), -1, today))
        assertEquals(today, pagedAnchor(LocalDate(2026, 10, 31), 1, today))
    }

    @Test
    fun testPagingCrossesTheYear() {
        assertEquals(LocalDate(2027, 1, 4), pagedAnchor(LocalDate(2026, 12, 31), 1, today))
        assertEquals(LocalDate(2025, 12, 4), pagedAnchor(LocalDate(2026, 1, 1), -1, today))
    }

    /** The week strip titles a week by its Thursday, so reopening on a paged month has to land on
     *  a week of that month. November 2026 starts on a Sunday, whose week is titled October. */
    @Test
    fun testPagedAnchorsWeekIsTitledByThatMonthEveryMonth() {
        var month = LocalDate(2026, 1, 1)
        repeat(36) {
            val anchor = pagedAnchor(month, 0, LocalDate(2000, 1, 1))
            val thursday = weekStart(anchor).plus(DatePeriod(days = 3))
            assertEquals(month.month, thursday.month)
            month = month.plus(DatePeriod(months = 1))
        }
        assertEquals(Month.NOVEMBER, weekStart(pagedAnchor(LocalDate(2026, 11, 1), 0, LocalDate(2000, 1, 1)))
            .plus(DatePeriod(days = 3)).month)
    }

    @Test
    fun testLastUnitOfAnEssentialSaysItWentToTheShoppingList() {
        assertEquals(R.string.calendar_used_snackbar, usedSnackbarText(addedToShoppingList = false))
        assertEquals(R.string.item_added_to_shopping_list_snackbar, usedSnackbarText(addedToShoppingList = true))
    }
}
