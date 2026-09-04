package com.foodtracker.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExpiryStatus(val label: String) {
    EXPIRED("Expired"),
    EXPIRING_TODAY("Expires Today"),
    EXPIRING_SOON("Expiring Soon"),
    FRESH("Fresh");

    companion object {
        fun compute(daysRemaining: Int, expiringSoonDays: Int = 3): ExpiryStatus {
            return when {
                daysRemaining < 0 -> EXPIRED
                daysRemaining == 0 -> EXPIRING_TODAY
                daysRemaining <= expiringSoonDays -> EXPIRING_SOON
                else -> FRESH
            }
        }
    }
}
