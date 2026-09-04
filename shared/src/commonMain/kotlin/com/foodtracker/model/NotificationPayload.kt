package com.foodtracker.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class NotificationPayload(
    val id: String,
    val itemName: String,
    val brand: String?,
    val expiresToday: Boolean,
    val daysRemaining: Int,
    val triggerDate: LocalDate,
    val triggerHour: Int = 9,
    val triggerMinute: Int = 0
)
