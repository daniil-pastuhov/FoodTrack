package com.foodtracker.android.notification

import com.foodtracker.model.AppSettings
import com.foodtracker.model.DigestFrequency
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.PantryItem
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
