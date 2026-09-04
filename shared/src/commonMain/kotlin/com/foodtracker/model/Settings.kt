package com.foodtracker.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationSettings(
    val enabled: Boolean = true,
    val leadDays: Int = 1,
    val hour: Int = 9,
    val minute: Int = 0
)

@Serializable
enum class AppTheme {
    SYSTEM,
    LIGHT,
    DARK
}

@Serializable
data class AppSettings(
    val notifications: NotificationSettings = NotificationSettings(),
    val expiringSoonDays: Int = 3,
    val storageLocations: List<String> = listOf("Fridge", "Freezer", "Pantry"),
    val defaultStorageLocation: String = "Pantry",
    val theme: AppTheme = AppTheme.SYSTEM
)
