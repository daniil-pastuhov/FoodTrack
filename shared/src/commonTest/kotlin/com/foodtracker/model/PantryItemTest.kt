package com.foodtracker.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PantryItemTest {

    @Test
    fun testDaysRemainingAndExpiryStatus() {
        val today = LocalDate(2026, 9, 3)

        val expiredItem = PantryItem(
            id = "1",
            name = "Old Milk",
            expiryDate = LocalDate(2026, 9, 1),
            addedDate = LocalDate(2026, 8, 25),
            storageLocation = "Fridge"
        )
        assertEquals(-2, expiredItem.daysRemaining(today))
        assertEquals(ExpiryStatus.EXPIRED, expiredItem.expiryStatus(today))

        val todayItem = PantryItem(
            id = "2",
            name = "Fresh Salad",
            expiryDate = LocalDate(2026, 9, 3),
            addedDate = LocalDate(2026, 9, 1),
            storageLocation = "Fridge"
        )
        assertEquals(0, todayItem.daysRemaining(today))
        assertEquals(ExpiryStatus.EXPIRING_TODAY, todayItem.expiryStatus(today))

        val soonItem = PantryItem(
            id = "3",
            name = "Bread",
            expiryDate = LocalDate(2026, 9, 5),
            addedDate = LocalDate(2026, 9, 1),
            storageLocation = "Pantry"
        )
        assertEquals(2, soonItem.daysRemaining(today))
        assertEquals(ExpiryStatus.EXPIRING_SOON, soonItem.expiryStatus(today))

        val freshItem = PantryItem(
            id = "4",
            name = "Dry Rice",
            expiryDate = LocalDate(2027, 3, 1),
            addedDate = LocalDate(2026, 9, 1),
            storageLocation = "Pantry"
        )
        assertEquals(ExpiryStatus.FRESH, freshItem.expiryStatus(today))
    }
}
