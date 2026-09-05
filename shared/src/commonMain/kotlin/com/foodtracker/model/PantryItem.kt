package com.foodtracker.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class PantryItem(
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val imageUrl: String? = null,
    val expiryDate: LocalDate,
    val addedDate: LocalDate,
    val storageLocation: String = "Pantry",
    val category: String? = null,
    val quantity: Int = 1,
    val notes: String? = null,
    val essential: Boolean = true
) {
    fun daysRemaining(today: LocalDate): Int {
        return expiryDate.toEpochDays() - today.toEpochDays()
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
        fun currentSystemDate(): LocalDate {
            val instant = kotlinx.datetime.Clock.System.now()
            val tz = kotlinx.datetime.TimeZone.currentSystemDefault()
            return kotlinx.datetime.Instant.fromEpochMilliseconds(instant.toEpochMilliseconds())
                .let {
                    // Extract local date using kotlinx.datetime
                    kotlinx.datetime.Clock.System.now()
                }
                .let {
                    val localDateTime = kotlinx.datetime.Clock.System.now()
                    // Safe conversion to LocalDate
                    val epochDays = (instant.toEpochMilliseconds() / (1000 * 60 * 60 * 24)).toInt()
                    LocalDate.fromEpochDays(epochDays)
                }
        }
    }
}
