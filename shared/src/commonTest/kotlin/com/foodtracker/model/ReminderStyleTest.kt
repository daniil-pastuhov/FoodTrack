package com.foodtracker.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReminderStyleTest {

    @Test
    fun styleReflectsWhichDeliveryTypesAreOn() {
        assertEquals(ReminderStyle.EACH_ITEM, NotificationSettings(individualEnabled = true, digestEnabled = false).reminderStyle)
        assertEquals(ReminderStyle.SUMMARY, NotificationSettings(individualEnabled = false, digestEnabled = true).reminderStyle)
        assertEquals(ReminderStyle.BOTH, NotificationSettings(individualEnabled = true, digestEnabled = true).reminderStyle)
        assertNull(NotificationSettings(individualEnabled = false, digestEnabled = false).reminderStyle)
    }

    @Test
    fun defaultsAreEachItem() {
        assertEquals(ReminderStyle.EACH_ITEM, NotificationSettings().reminderStyle)
    }

    @Test
    fun choosingAStyleRoundTrips() {
        ReminderStyle.entries.forEach { style ->
            val start = NotificationSettings(individualEnabled = false, digestEnabled = false)
            assertEquals(style, start.withReminderStyle(style).reminderStyle)
        }
    }

    @Test
    fun choosingAStyleKeepsTheOtherFields() {
        val start = NotificationSettings(leadDays = 3, hour = 7, digestFrequency = DigestFrequency.WEEKLY, digestWeekday = 5)
        val updated = start.withReminderStyle(ReminderStyle.SUMMARY)
        assertEquals(start.copy(individualEnabled = false, digestEnabled = true), updated)
    }

    @Test
    fun enablingWithNothingSelectedRestoresEachItem() {
        val updated = NotificationSettings(enabled = false, individualEnabled = false, digestEnabled = false).withEnabled(true)
        assertTrue(updated.enabled)
        assertEquals(ReminderStyle.EACH_ITEM, updated.reminderStyle)
    }

    @Test
    fun enablingKeepsAnExistingSummaryOnlyChoice() {
        val updated = NotificationSettings(enabled = false, individualEnabled = false, digestEnabled = true).withEnabled(true)
        assertEquals(ReminderStyle.SUMMARY, updated.reminderStyle)
    }

    @Test
    fun disablingLeavesTheStyleAlone() {
        val start = NotificationSettings(individualEnabled = false, digestEnabled = true)
        val updated = start.withEnabled(false)
        assertFalse(updated.enabled)
        assertEquals(ReminderStyle.SUMMARY, updated.reminderStyle)
    }

    @Test
    fun legacyFrequenciesMapToDays() {
        assertEquals(1, NotificationSettings(digestFrequency = DigestFrequency.DAILY).digestEveryDays)
        assertEquals(7, NotificationSettings(digestFrequency = DigestFrequency.WEEKLY, digestIntervalDays = 3).digestEveryDays)
    }

    @Test
    fun choosingDaysRoundTripsAndSevenIsWeekly() {
        listOf(1, 2, 3, 5, 7).forEach { days ->
            assertEquals(days, NotificationSettings().withDigestEveryDays(days).digestEveryDays)
        }
        assertEquals(DigestFrequency.WEEKLY, NotificationSettings().withDigestEveryDays(7).digestFrequency)
        assertEquals(DigestFrequency.DAILY, NotificationSettings(digestFrequency = DigestFrequency.WEEKLY).withDigestEveryDays(3).digestFrequency)
    }

    @Test
    fun everyNDaysFiresOnEpochAlignedDaysOnly() {
        val settings = NotificationSettings().withDigestEveryDays(3)
        val firing = (20_000L..20_008L).filter { settings.isDigestDay(it, isoWeekday = 1) }
        assertEquals(listOf(20_001L, 20_004L, 20_007L), firing)
    }

    @Test
    fun dailyFiresEveryDayAndWeeklyOnItsWeekday() {
        assertTrue(NotificationSettings().isDigestDay(12_345L, isoWeekday = 4))
        val weekly = NotificationSettings(digestWeekday = 5).withDigestEveryDays(7)
        assertTrue(weekly.isDigestDay(1L, isoWeekday = 5))
        assertFalse(weekly.isDigestDay(1L, isoWeekday = 4))
    }

    @Test
    fun togglingTheMasterSwitchKeepsEveryStyle() {
        ReminderStyle.entries.forEach { style ->
            val start = NotificationSettings().withReminderStyle(style)
            assertEquals(style, start.withEnabled(false).withEnabled(true).reminderStyle)
        }
    }

    @Test
    fun storedIntervalOutsideTheRangeIsClamped() {
        // A corrupt or hand-edited value of 0 would otherwise make isDigestDay divide by zero.
        assertEquals(1, NotificationSettings(digestIntervalDays = 0).digestEveryDays)
        assertEquals(1, NotificationSettings(digestIntervalDays = -4).digestEveryDays)
        assertEquals(7, NotificationSettings(digestIntervalDays = 10).digestEveryDays)
        assertTrue(NotificationSettings(digestIntervalDays = 0).isDigestDay(20_000L, isoWeekday = 1))
    }

    @Test
    fun choosingDaysOutsideTheRangeIsClamped() {
        val tooMany = NotificationSettings().withDigestEveryDays(10)
        assertEquals(DigestFrequency.WEEKLY, tooMany.digestFrequency)
        assertEquals(7, tooMany.digestEveryDays)
        assertEquals(1, NotificationSettings().withDigestEveryDays(0).digestEveryDays)
    }
}
