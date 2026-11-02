package com.foodtracker.model

import kotlinx.serialization.Serializable

@Serializable
data class NotificationSettings(
    /** Master switch: off silences every expiry notification, individual and summary alike. */
    val enabled: Boolean = true,
    val leadDays: Int = 1,
    val hour: Int = 9,
    val minute: Int = 0,
    /** One reminder per product, [leadDays] before it expires. */
    val individualEnabled: Boolean = true,
    /** One grouped notification naming what expires in the coming week. Off by default: with
     *  individual reminders on too, every item would be announced twice. */
    val digestEnabled: Boolean = false,
    val digestFrequency: DigestFrequency = DigestFrequency.DAILY,
    /** ISO day of week (1 = Monday .. 7 = Sunday) the summary fires on when [digestFrequency]
     *  is [DigestFrequency.WEEKLY]. Ignored for a daily summary. */
    val digestWeekday: Int = 1,
    /** Days between summaries when [digestFrequency] is [DigestFrequency.DAILY]: 1 is every
     *  day. Weekly stays its own frequency (it has a weekday), so settings saved before this
     *  field existed keep meaning what they did. Read through [digestEveryDays]. */
    val digestIntervalDays: Int = 1
)

/**
 * How expiry reminders are delivered: the single choice the settings screen offers instead of
 * two independent toggles. UI-only - persisted as [NotificationSettings.individualEnabled] and
 * [NotificationSettings.digestEnabled], so stored settings don't change shape.
 */
enum class ReminderStyle {
    EACH_ITEM,
    SUMMARY,
    BOTH
}

/** The current [ReminderStyle], or null when both delivery types are off - a state saved by
 *  older versions, where the master switch was on but nothing would ever fire. */
val NotificationSettings.reminderStyle: ReminderStyle?
    get() = when {
        individualEnabled && digestEnabled -> ReminderStyle.BOTH
        individualEnabled -> ReminderStyle.EACH_ITEM
        digestEnabled -> ReminderStyle.SUMMARY
        else -> null
    }

fun NotificationSettings.withReminderStyle(style: ReminderStyle): NotificationSettings = copy(
    individualEnabled = style != ReminderStyle.SUMMARY,
    digestEnabled = style != ReminderStyle.EACH_ITEM
)

/** Flips the master switch. Turning it on with no delivery type selected brings back per-item
 *  reminders, so "on" always means something will actually fire. */
fun NotificationSettings.withEnabled(enabled: Boolean): NotificationSettings = copy(
    enabled = enabled,
    individualEnabled = individualEnabled || (enabled && !digestEnabled)
)

@Serializable
enum class DigestFrequency {
    DAILY,
    WEEKLY
}

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

/**
 * The storage locations a fresh install starts with.
 *
 * These English names are stored keys, not display text: they are what lands in
 * [AppSettings.storageLocations] and on every [PantryItem.storageLocation], so they stay the same
 * whatever language the device is in. Each platform shows them translated, and anything the
 * user typed (a renamed or added location) as-is. Changing one of these strings would orphan
 * every item already stored under the old one.
 */
object DefaultStorageLocations {
    const val FRIDGE = "Fridge"
    const val FREEZER = "Freezer"
    const val PANTRY = "Pantry"

    val ALL = listOf(FRIDGE, FREEZER, PANTRY)
}

@Serializable
data class AppSettings(
    val notifications: NotificationSettings = NotificationSettings(),
    val expiringSoonDays: Int = 3,
    val storageLocations: List<String> = DefaultStorageLocations.ALL,
    val defaultStorageLocation: String = DefaultStorageLocations.PANTRY,
    /** When on, a new item starts out in the storage location the previous new item was saved
     *  to (see [lastUsedStorageLocation]) rather than in [defaultStorageLocation], so a batch
     *  of scans that all go into the freezer only needs the location picked once. */
    val rememberLastStorageLocation: Boolean = true,
    /** Storage location of the most recently added item. Only consulted while
     *  [rememberLastStorageLocation] is on; kept up to date by the state holder on every new
     *  item save, and cleared when its location is deleted in Settings. */
    val lastUsedStorageLocation: String? = null,
    val theme: AppTheme = AppTheme.SYSTEM,
    val sortOption: SortOption = SortOption.EXPIRY_DATE,
    /** Allergens the user wants to be warned about when a scanned product lists them. Empty by
     *  default: this is a safety feature for the people who need it, not a nag for everyone. */
    val allergens: Set<String> = emptySet(),
    /** Shops to remind the user of their shopping list near. See [StorePlace]. */
    val stores: List<StorePlace> = emptyList(),
    /**
     * Whether those reminders are armed.
     *
     * Off by default, and deliberately separate from [stores] being non-empty: this is the
     * switch that gates asking for background location, which is the most intrusive permission
     * the app requests. Nothing about it should happen without the user turning it on first.
     */
    val storeRemindersEnabled: Boolean = false
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

/** Days between summaries, 1..7: 7 for a weekly summary, otherwise [NotificationSettings.digestIntervalDays]. */
val NotificationSettings.digestEveryDays: Int
    get() = if (digestFrequency == DigestFrequency.WEEKLY) 7 else digestIntervalDays.coerceIn(1, 7)

/** Sets how often the summary fires. 7 means weekly, on [NotificationSettings.digestWeekday]. */
fun NotificationSettings.withDigestEveryDays(days: Int): NotificationSettings {
    val clamped = days.coerceIn(1, 7)
    return copy(
        digestFrequency = if (clamped == 7) DigestFrequency.WEEKLY else DigestFrequency.DAILY,
        digestIntervalDays = if (clamped == 7) digestIntervalDays else clamped
    )
}

/**
 * Whether the summary fires on the local calendar day [epochDay] (days since 1970-01-01), whose
 * ISO weekday (1 = Monday .. 7 = Sunday) is [isoWeekday]. Every N days is anchored to the epoch
 * rather than to "now", so rescheduling - which iOS does on every pantry change - can't keep
 * pushing the next summary N days out and never fire it.
 */
fun NotificationSettings.isDigestDay(epochDay: Long, isoWeekday: Int): Boolean = when (val every = digestEveryDays) {
    1 -> true
    7 -> isoWeekday == digestWeekday.coerceIn(1, 7)
    else -> epochDay.mod(every.toLong()) == 0L
}
