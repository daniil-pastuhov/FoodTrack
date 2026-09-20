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
enum class SortOption {
    EXPIRY_DATE,
    NAME,
    DATE_ADDED,
    STORAGE_LOCATION
}

@Serializable
data class AppSettings(
    val notifications: NotificationSettings = NotificationSettings(),
    val expiringSoonDays: Int = 3,
    val storageLocations: List<String> = listOf("Fridge", "Freezer", "Pantry"),
    val defaultStorageLocation: String = "Pantry",
    /** When on, a new item starts out in the storage location the previous new item was saved
     *  to (see [lastUsedStorageLocation]) rather than in [defaultStorageLocation], so a batch
     *  of scans that all go into the freezer only needs the location picked once. */
    val rememberLastStorageLocation: Boolean = true,
    /** Storage location of the most recently added item. Only consulted while
     *  [rememberLastStorageLocation] is on; kept up to date by the state holder on every new
     *  item save, and cleared when its location is deleted in Settings. */
    val lastUsedStorageLocation: String? = null,
    val theme: AppTheme = AppTheme.SYSTEM,
    val sortOption: SortOption = SortOption.EXPIRY_DATE
) {
    /** The storage location a freshly created item should be pre-filled with. Falls back to
     *  [defaultStorageLocation] when the remembered one is off, unset, or no longer one of
     *  [storageLocations] (e.g. it was renamed by hand-editing prefs, or removed). */
    fun storageLocationForNewItem(): String {
        val last = lastUsedStorageLocation
        return if (rememberLastStorageLocation && last != null && last in storageLocations) last
        else defaultStorageLocation
    }
}
