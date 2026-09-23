package com.foodtracker.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class NotificationPayload(
    val id: String,
    val itemName: String,
    val brand: String?,
    /** True when the item expires on [triggerDate] - i.e. on the day this reminder fires. */
    val expiresToday: Boolean,
    /** Days between [triggerDate] and the expiry date, so the copy reads correctly at the
     *  moment the notification lands rather than at the moment it was scheduled. */
    val daysRemaining: Int,
    val triggerDate: LocalDate,
    val triggerHour: Int = 9,
    val triggerMinute: Int = 0
)
