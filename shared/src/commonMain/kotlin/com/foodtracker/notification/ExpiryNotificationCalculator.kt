package com.foodtracker.notification

import com.foodtracker.model.NotificationPayload
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate

object ExpiryNotificationCalculator {

    /**
     * Calculates the expiration notification payload for an item.
     * Triggers at 9:00 AM one day before expiration, so the user has time to act.
     * Returns null if the item already expired in the past.
     */
    fun calculateNotification(
        item: PantryItem,
        today: LocalDate,
        leadDays: Int = 1,
        hour: Int = 9,
        minute: Int = 0
    ): NotificationPayload? {
        // If the item already expired before today, do not schedule
        if (item.daysRemaining(today) < 0) return null

        val reminderDate = LocalDate.fromEpochDays(item.expiryDate.toEpochDays() - leadDays)

        // If the lead-day reminder window has already passed (e.g. the item was added the same
        // day it expires, or with less than leadDays to go), fall back to firing today instead
        // of silently missing the warning - the scheduler turns a trigger date whose time of
        // day has gone by into an immediate alarm.
        val triggerDate = if (reminderDate < today) today else reminderDate

        // Counted from the day the reminder actually fires, not from today: the payload's copy
        // is written now but read when the notification lands. An item five days out schedules
        // its reminder for the day before it expires, so it has to say "expires in 1 day" -
        // saying "in 5 days" would be describing a moment four days before the user sees it.
        val daysRemainingAtTrigger =
            (item.expiryDate.toEpochDays() - triggerDate.toEpochDays()).toInt()

        return NotificationPayload(
            id = item.id,
            itemName = item.name,
            brand = item.brand,
            expiresToday = daysRemainingAtTrigger == 0,
            daysRemaining = daysRemainingAtTrigger,
            triggerDate = triggerDate,
            triggerHour = hour,
            triggerMinute = minute
        )
    }
}
