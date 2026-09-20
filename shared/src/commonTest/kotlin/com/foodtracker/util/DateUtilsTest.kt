package com.foodtracker.util

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DateUtilsTest {

    private val today = LocalDate(2026, 9, 20)

    @Test
    fun plausibleWithinTenYearsEitherWay() {
        assertTrue(DateUtils.isPlausibleExpiryDate(LocalDate(2036, 12, 31), today))
        assertTrue(DateUtils.isPlausibleExpiryDate(LocalDate(2016, 1, 1), today))
        assertTrue(DateUtils.isPlausibleExpiryDate(today, today))
    }

    @Test
    fun implausibleBeyondTenYears() {
        assertFalse(DateUtils.isPlausibleExpiryDate(LocalDate(2037, 1, 1), today))
        assertFalse(DateUtils.isPlausibleExpiryDate(LocalDate(2015, 12, 31), today))
        // The bug that motivated this: a two-digit year "27" stored as year 27 AD.
        assertFalse(DateUtils.isPlausibleExpiryDate(LocalDate(27, 7, 29), today))
    }
}
