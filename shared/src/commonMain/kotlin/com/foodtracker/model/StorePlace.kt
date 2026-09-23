package com.foodtracker.model

import kotlinx.serialization.Serializable

/**
 * A shop the user wants to be reminded of their shopping list near.
 *
 * Stored in [AppSettings] rather than in its own table: there are only ever one or two of
 * these, they are settings rather than content, and keeping them here means the feature needs
 * no database migration on either platform.
 */
@Serializable
data class StorePlace(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    /**
     * How close counts as "at the shop".
     *
     * 150m by default. Smaller and the reminder is unreliable — a phone's position indoors and
     * among buildings is routinely off by that much, and a geofence that never fires is worse
     * than none. Much larger and it fires while the user is merely driving past.
     */
    val radiusMeters: Double = DEFAULT_RADIUS_METERS
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = 150.0
    }
}
