package com.foodtracker.android.notification

import com.foodtracker.model.AppSettings
import com.foodtracker.model.DigestFrequency
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.withDigestEveryDays
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class NotificationSettingsTest {

    /** 2026-10-07 is a Wednesday. */
    private fun wednesdayAt(hour: Int, minute: Int = 0): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, 7, hour, minute, 0)
    }

    private fun Calendar.ymdh() = listOf(
        get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH), get(Calendar.HOUR_OF_DAY)
    )

    @Test
    fun dailyDigestFiresLaterTodayWhileTheSlotIsAhead() {
        val next = NotificationHelper.nextDigestTime(wednesdayAt(8), NotificationSettings(hour = 9))
        assertEquals(listOf(2026, 10, 7, 9), next.ymdh())
    }

    @Test
    fun dailyDigestRollsToTomorrowOnceTheSlotHasPassed() {
        val next = NotificationHelper.nextDigestTime(wednesdayAt(10), NotificationSettings(hour = 9))
        assertEquals(listOf(2026, 10, 8, 9), next.ymdh())
    }

    @Test
    fun weeklyDigestLandsOnTheChosenWeekday() {
        val settings = NotificationSettings(hour = 9, digestFrequency = DigestFrequency.WEEKLY, digestWeekday = 6)
        val next = NotificationHelper.nextDigestTime(wednesdayAt(10), settings)
        assertEquals("Saturday", listOf(2026, 10, 10, 9), next.ymdh())
    }

    @Test
    fun weeklyDigestOnSundayMapsFromIsoSeven() {
        val settings = NotificationSettings(hour = 9, digestFrequency = DigestFrequency.WEEKLY, digestWeekday = 7)
        val next = NotificationHelper.nextDigestTime(wednesdayAt(10), settings)
        assertEquals(listOf(2026, 10, 11, 9), next.ymdh())
    }

    @Test
    fun weeklyDigestOnTodayFiresTodayOnlyWhileTheSlotIsAhead() {
        val settings = NotificationSettings(hour = 9, digestFrequency = DigestFrequency.WEEKLY, digestWeekday = 3)
        assertEquals(listOf(2026, 10, 7, 9), NotificationHelper.nextDigestTime(wednesdayAt(8), settings).ymdh())
        assertEquals(listOf(2026, 10, 14, 9), NotificationHelper.nextDigestTime(wednesdayAt(10), settings).ymdh())
    }

    @Test
    fun everyThreeDaysLandsOnTheNextEpochAlignedDay() {
        // 2026-10-07 is epoch day 20733 (20733 % 3 == 0), so the 3-day cycle hits Oct 7, 10, 13.
        val settings = NotificationSettings(hour = 9).withDigestEveryDays(3)
        assertEquals(listOf(2026, 10, 7, 9), NotificationHelper.nextDigestTime(wednesdayAt(8), settings).ymdh())
        assertEquals(listOf(2026, 10, 10, 9), NotificationHelper.nextDigestTime(wednesdayAt(10), settings).ymdh())
    }

    @Test
    fun everyNDaysDoesNotDriftWhenRescheduledDaily() {
        val settings = NotificationSettings(hour = 9).withDigestEveryDays(5)
        val fromWednesday = NotificationHelper.nextDigestTime(wednesdayAt(10), settings).ymdh()
        val fromThursday = NotificationHelper.nextDigestTime(wednesdayAt(10).apply { add(Calendar.DAY_OF_YEAR, 1) }, settings).ymdh()
        assertEquals(fromWednesday, fromThursday)
    }

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.OCTOBER, day, hour, minute, second)
    }

    @Test
    fun digestLandsExactlyOnTheConfiguredMinuteWithNoLeftoverSeconds() {
        val settings = NotificationSettings(hour = 9, minute = 30)
        val next = NotificationHelper.nextDigestTime(at(7, 8, 15, second = 37), settings)
        assertEquals(at(7, 9, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun aSlotExactlyAtNowIsAlreadyPast() {
        // The receiver reschedules at its own fire time; reusing that instant would fire twice.
        val settings = NotificationSettings(hour = 9, minute = 30)
        val next = NotificationHelper.nextDigestTime(at(7, 9, 30), settings)
        assertEquals(at(8, 9, 30).timeInMillis, next.timeInMillis)
    }

    @Test
    fun settingsSavedBeforeDeliveryModesExistDecodeToIndividualOnly() {
        val legacy = """{"notifications":{"enabled":true,"leadDays":2,"hour":8,"minute":30}}"""
        val notifications = Json.decodeFromString<AppSettings>(legacy).notifications
        assertTrue(notifications.individualEnabled)
        assertFalse(notifications.digestEnabled)
        assertEquals(DigestFrequency.DAILY, notifications.digestFrequency)
        assertEquals(2, notifications.leadDays)
    }

    @Test
    fun itemsStoredBeforeThePerItemSwitchKeepTheirReminder() {
        val legacy = """{"id":"a","name":"Milk","expiryDate":"2026-10-10","addedDate":"2026-10-01"}"""
        assertTrue(Json.decodeFromString<PantryItem>(legacy).notificationsEnabled)
    }
}
