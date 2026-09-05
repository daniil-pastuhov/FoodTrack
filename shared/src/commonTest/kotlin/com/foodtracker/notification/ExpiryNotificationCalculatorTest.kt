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
        // The day-before window is in the past; NotificationHelper's "already passed"
        // fallback fires this immediately rather than waiting for the trigger date.
        assertEquals(LocalDate(2026, 9, 2), notification.triggerDate)
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
