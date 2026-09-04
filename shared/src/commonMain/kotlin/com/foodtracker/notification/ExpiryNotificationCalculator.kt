package com.foodtracker.notification

import com.foodtracker.model.ItemStatus
import com.foodtracker.model.NotificationPayload
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate

object ExpiryNotificationCalculator {

    /**
     * Calculates the expiration notification payload for an item.
     * Triggers at 9:00 AM one day before expiration, so the user has time to act.
     * Returns null if the item is not active (e.g. discarded) or expired in the past.
     */
    fun calculateNotification(
        item: PantryItem,
        today: LocalDate,
        leadDays: Int = 1,
        hour: Int = 9,
        minute: Int = 0
    ): NotificationPayload? {
        if (item.status != ItemStatus.ACTIVE) return null

        val daysRemaining = item.daysRemaining(today)

        // If the item already expired before today, do not schedule
        if (daysRemaining < 0) return null

        val reminderDate = LocalDate.fromEpochDays(item.expiryDate.toEpochDays() - leadDays)

        // If the item expires today, the lead-day reminder window has already passed
        // (e.g. it was added the same day it expires) - fall back to an immediate "today"
        // notification instead of silently missing the warning.
        return NotificationPayload(
            id = item.id,
            itemName = item.name,
            brand = item.brand,
            expiresToday = daysRemaining == 0,
            daysRemaining = daysRemaining,
            triggerDate = reminderDate,
            triggerHour = hour,
            triggerMinute = minute
        )
    }
}
