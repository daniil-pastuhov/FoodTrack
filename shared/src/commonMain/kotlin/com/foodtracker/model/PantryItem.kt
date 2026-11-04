package com.foodtracker.model

import com.foodtracker.util.DateUtils
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** The most of one item the app will count up to. An older item above it keeps its quantity. */
const val MAX_ITEM_QUANTITY = 99

@Serializable
data class PantryItem(
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val imageUrl: String? = null,
    val expiryDate: LocalDate,
    val addedDate: LocalDate,
    val storageLocation: String = DefaultStorageLocations.PANTRY,
    val category: String? = null,
    val quantity: Int = 1,
    val notes: String? = null,
    val essential: Boolean = true,
    /** Whether this item gets its own expiry reminder and appears in the summary. On by
     *  default; turning it off silences just this product, not notifications as a whole. */
    val notificationsEnabled: Boolean = true
) {
    fun daysRemaining(today: LocalDate): Int {
        return (expiryDate.toEpochDays() - today.toEpochDays()).toInt()
    }

    /** Key used to match this item's product against a shopping list entry: barcode when
     *  known, otherwise the trimmed/lowercased name (barcode-less matches are unreliable
     *  across re-scans, but still needed to dedupe manual entries and deletions). */
    fun shoppingMatchKey(): String = barcode ?: name.trim().lowercase()

    fun expiryStatus(today: LocalDate, expiringSoonDays: Int = 3): ExpiryStatus {
        return ExpiryStatus.compute(daysRemaining(today), expiringSoonDays)
    }

    val isExpired: Boolean
        get() = daysRemaining(currentSystemDate()) < 0

    companion object {
        /** Today in the device's own time zone.
         *
         *  This used to hand-roll the conversion through a chain of `let`s that read
         *  `Clock.System.now()` four times and then divided epoch millis by a fixed 86_400_000,
         *  which silently answered in UTC rather than the local zone. [DateUtils.today] does the
         *  same job correctly, in one call, and is what the rest of the codebase already uses. */
        fun currentSystemDate(): LocalDate = DateUtils.today()
    }
}
