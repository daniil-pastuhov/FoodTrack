package com.foodtracker.notification

import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExpiryNotificationCalculatorTest {

    private val today = LocalDate(2026, 9, 3)

    @Test
    fun testActiveItemNotificationGeneratedOneDayBeforeExpiry() {
        val item = PantryItem(
            id = "item-123",
            name = "Organic Whole Milk",
            brand = "Organic Valley",
            expiryDate = LocalDate(2026, 9, 10),
            addedDate = today,
            storageLocation = "Fridge"
        )

        val notification = ExpiryNotificationCalculator.calculateNotification(item, today)
        assertNotNull(notification)
        assertEquals("item-123", notification.id)
        assertEquals("Organic Whole Milk", notification.itemName)
        assertEquals("Organic Valley", notification.brand)
        assertEquals(false, notification.expiresToday)
        assertEquals(LocalDate(2026, 9, 9), notification.triggerDate)
        // Counted from the trigger date, not from today: the reminder fires the day before
        // expiry, so that is what its copy has to describe - a week out when it was scheduled
        // is not what the user reads six days later.
        assertEquals(1, notification.daysRemaining)
        assertEquals(9, notification.triggerHour)
        assertEquals(0, notification.triggerMinute)
    }

    @Test
    fun testItemExpiringTodayFallsBackToImmediateTodayNotification() {
        val item = PantryItem(
            id = "item-999",
            name = "Fresh Salad",
            expiryDate = today,
            addedDate = today
        )

        val notification = ExpiryNotificationCalculator.calculateNotification(item, today)
        assertNotNull(notification)
        assertEquals(true, notification.expiresToday)
        assertEquals(0, notification.daysRemaining)
        // The day-before window is in the past, so the trigger date collapses to today;
        // NotificationHelper's "already passed" fallback then fires it immediately.
        assertEquals(today, notification.triggerDate)
    }

    @Test
    fun testLongerLeadTimeCountsDownFromTheDayTheReminderFires() {
        val item = PantryItem(
            id = "item-456",
            name = "Cheddar",
            expiryDate = LocalDate(2026, 9, 30),
            addedDate = today
        )

        val notification = ExpiryNotificationCalculator.calculateNotification(
            item,
            today,
            leadDays = 3
        )
        assertNotNull(notification)
        assertEquals(LocalDate(2026, 9, 27), notification.triggerDate)
        assertEquals(3, notification.daysRemaining)
        assertEquals(false, notification.expiresToday)
    }

    @Test
    fun testLeadWindowAlreadyPassedDescribesTheItemAsOfToday() {
        val item = PantryItem(
            id = "item-321",
            name = "Yoghurt",
            expiryDate = LocalDate(2026, 9, 4), // tomorrow
            addedDate = today
        )

        // A three-day lead on an item with one day left: the reminder date is in the past, so
        // it fires now and must say "in 1 day", not "in 3 days".
        val notification = ExpiryNotificationCalculator.calculateNotification(
            item,
            today,
            leadDays = 3
        )
        assertNotNull(notification)
        assertEquals(today, notification.triggerDate)
        assertEquals(1, notification.daysRemaining)
        assertEquals(false, notification.expiresToday)
    }

    @Test
    fun testPastExpiredItemDoesNotGenerateNotification() {
        val item = PantryItem(
            id = "item-789",
            name = "Old Bread",
            expiryDate = LocalDate(2026, 9, 1), // 2 days ago
            addedDate = LocalDate(2026, 8, 25)
        )

        val notification = ExpiryNotificationCalculator.calculateNotification(item, today)
        assertNull(notification)
    }
}
