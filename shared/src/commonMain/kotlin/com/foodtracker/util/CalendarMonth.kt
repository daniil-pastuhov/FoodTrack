package com.foodtracker.util

import com.foodtracker.model.PantryItem
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

data class CalendarDay(val date: LocalDate, val inCurrentMonth: Boolean)

/**
 * Fixed 6-week (42-day), Monday-start grid of days covering [anyDateInMonth]'s month, padded
 * with leading/trailing days from adjacent months. A fixed cell count keeps the grid height
 * constant across months.
 */
fun monthGrid(anyDateInMonth: LocalDate): List<CalendarDay> {
    val firstOfMonth = LocalDate(anyDateInMonth.year, anyDateInMonth.month, 1)
    val leadingDays = firstOfMonth.dayOfWeek.isoDayNumber - 1
    val gridStart = firstOfMonth.minus(DatePeriod(days = leadingDays))
    return (0 until 42).map { offset ->
        val date = gridStart.plus(DatePeriod(days = offset))
        CalendarDay(date = date, inCurrentMonth = date.month == anyDateInMonth.month && date.year == anyDateInMonth.year)
    }
}

/** The Monday that starts [date]'s Monday-start week (the same weeks [monthGrid] lays out). */
fun weekStart(date: LocalDate): LocalDate =
    date.minus(DatePeriod(days = date.dayOfWeek.isoDayNumber - 1))

/** The seven days, Monday to Sunday, of the week containing [date]. */
fun weekDays(date: LocalDate): List<LocalDate> {
    val start = weekStart(date)
    return (0 until 7).map { start.plus(DatePeriod(days = it)) }
}

fun groupItemsByExpiryDate(items: List<PantryItem>): Map<LocalDate, List<PantryItem>> =
    items.groupBy { it.expiryDate }
