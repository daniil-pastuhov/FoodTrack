package com.foodtracker.android.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.foodtracker.android.R
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.isDigestDay
import com.foodtracker.notification.ExpiryNotificationCalculator
import com.foodtracker.repository.PantryRepository
import com.foodtracker.util.DateUtils
import java.util.Calendar
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate

object NotificationHelper {

    /** Per-item expiry reminders, at default importance: a reminder is information, not an alarm.
     *  A channel's importance can't be lowered once it exists, which is why this is a new id. */
    const val CHANNEL_ID = "foodtracker_item_reminders"

    /** The high-importance channel that made every reminder a heads-up with vibration. Deleted
     *  on start; see [itemChannelImportance]. */
    internal const val LEGACY_CHANNEL_ID = "foodtracker_expiry_channel"

    /** The grouped summary. Its own channel, so the user can silence or demote it in system
     *  settings without touching the per-item reminders, and the other way round. */
    const val DIGEST_CHANNEL_ID = "foodtracker_digest_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val itemChannel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                itemChannelImportance(notificationManager.getNotificationChannel(LEGACY_CHANNEL_ID)?.importance)
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
            }
            val digestChannel = NotificationChannel(
                DIGEST_CHANNEL_ID,
                context.getString(R.string.notification_digest_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_digest_channel_desc)
            }
            notificationManager.createNotificationChannels(listOf(itemChannel, digestChannel))
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    /**
     * The importance to create the per-item channel with, given the old channel's. Someone who
     * turned the old channel off in system settings keeps it off; moving to a new id must not
     * start reminding them again. Anyone else gets the default, whatever they had. Only matters
     * on the first start after the update: once the channel exists, this value is ignored.
     */
    internal fun itemChannelImportance(legacyImportance: Int?): Int =
        if (legacyImportance == NotificationManager.IMPORTANCE_NONE) {
            NotificationManager.IMPORTANCE_NONE
        } else {
            NotificationManager.IMPORTANCE_DEFAULT
        }

    /**
     * The system settings screen that would unblock expiry reminders, or null when nothing the
     * user has chosen is blocked. Covers the app-wide switch (and the Android 13+ permission,
     * which reports the same way) and each channel the current [settings] would post to.
     */
    fun blockedNotificationsSettingsIntent(context: Context, settings: NotificationSettings): Intent? {
        if (!settings.enabled) return null
        val manager = NotificationManagerCompat.from(context)
        val pkg = context.packageName
        if (!manager.areNotificationsEnabled()) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val blockedChannel = listOfNotNull(
                CHANNEL_ID.takeIf { settings.individualEnabled },
                DIGEST_CHANNEL_ID.takeIf { settings.digestEnabled }
            ).firstOrNull { manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE }
            if (blockedChannel != null) {
                return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, blockedChannel)
            }
        }
        return null
    }

    /** Whether [item] should get its own reminder under [settings]: notifications on as a
     *  whole, per-item reminders on, and not silenced for this product. */
    internal fun wantsIndividualReminder(settings: NotificationSettings, item: PantryItem): Boolean =
        settings.enabled && settings.individualEnabled && item.notificationsEnabled

    /**
     * The reminder's title and body for an item with [daysRemaining] days left *at the moment
     * it fires* - which is not the same as today when the reminder is scheduled days ahead.
     * Shared by [scheduleNotification] and [snoozeUntilTomorrow] so a snoozed reminder counts
     * down with the item instead of repeating yesterday's wording. Snoozing is also the one way
     * to get here with a negative count: scheduling skips an item that is already past its date.
     */
    internal fun notificationCopy(
        context: Context,
        itemName: String,
        brand: String?,
        daysRemaining: Int
    ): Pair<String, String> {
        val nameWithBrand = if (!brand.isNullOrBlank()) "$itemName ($brand)" else itemName
        return when {
            daysRemaining < 0 -> {
                val daysPast = -daysRemaining
                context.getString(R.string.notif_title_past) to
                    context.resources.getQuantityString(
                        R.plurals.notif_body_past_format,
                        daysPast,
                        nameWithBrand,
                        daysPast
                    )
            }
            daysRemaining == 0 ->
                context.getString(R.string.notif_title_today) to
                    context.getString(R.string.notif_body_today_format, nameWithBrand)
            daysRemaining == 1 ->
                context.getString(R.string.notif_title_soon) to
                    context.getString(R.string.notif_body_tomorrow_format, nameWithBrand)
            else ->
                context.getString(R.string.notif_title_soon) to
                    context.resources.getQuantityString(
                        R.plurals.notif_body_soon_format,
                        daysRemaining,
                        nameWithBrand,
                        daysRemaining
                    )
        }
    }

    /**
     * What a reminder says as it fires. Worded from the item as it stands, not from the extras
     * the alarm was set with: an alarm can sit for months, so an update's new wording would
     * otherwise take that long to reach people, and the date may have changed since. The extras
     * are for an item that can no longer be read, then the generic line.
     */
    internal fun reminderCopyAtFiring(
        context: Context,
        item: PantryItem?,
        today: LocalDate,
        extraTitle: String?,
        extraBody: String?
    ): Pair<String, String> =
        item?.let { notificationCopy(context, it.name, it.brand, it.daysRemaining(today)) }
            ?: Pair(
                extraTitle ?: context.getString(R.string.notif_fallback_title),
                extraBody ?: context.getString(R.string.notif_fallback_body)
            )

    /** [fireIfPast] false skips a reminder whose time has gone by instead of firing it now, for
     *  [restoreReminders], which must not repeat a reminder already shown. */
    fun scheduleNotification(context: Context, item: PantryItem, fireIfPast: Boolean = true) {
        val notificationSettings = AndroidSettingsRepository(context).getSettings().notifications
        if (!wantsIndividualReminder(notificationSettings, item)) {
            // Also clears an alarm scheduled before the reminder was switched off - for the
            // item, or for per-item reminders as a whole - which would otherwise still fire.
            cancelNotification(context, item.id)
            return
        }

        val today = DateUtils.today()
        val payload = ExpiryNotificationCalculator.calculateNotification(
            item,
            today,
            leadDays = notificationSettings.leadDays,
            hour = notificationSettings.hour,
            minute = notificationSettings.minute
        ) ?: return

        val (title, body) = notificationCopy(
            context,
            payload.itemName,
            payload.brand,
            payload.daysRemaining
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, payload.triggerDate.year)
            set(Calendar.MONTH, payload.triggerDate.monthNumber - 1)
            set(Calendar.DAY_OF_MONTH, payload.triggerDate.dayOfMonth)
            set(Calendar.HOUR_OF_DAY, payload.triggerHour)
            set(Calendar.MINUTE, payload.triggerMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var triggerMillis = calendar.timeInMillis
        val now = System.currentTimeMillis()

        if (triggerMillis <= now) {
            if (!fireIfPast) return
            triggerMillis = now + 5000L
        }

        val intent = Intent(context, ExpiryNotificationReceiver::class.java).apply {
            putExtra(ExpiryNotificationReceiver.EXTRA_ITEM_ID, payload.id)
            putExtra(ExpiryNotificationReceiver.EXTRA_TITLE, title)
            putExtra(ExpiryNotificationReceiver.EXTRA_BODY, body)
        }

        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.id.hashCode(),
            intent,
            flags
        )

        try {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerMillis,
                pendingIntent
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Re-fires [itemId]'s reminder tomorrow at the configured reminder time.
     *
     * Deliberately not "in 24 hours": the user picked a time of day to be reminded, and a
     * rolling offset would walk that reminder later every time they snoozed it. If tomorrow's
     * slot has somehow already passed, it falls back to a short delay rather than scheduling
     * into the past, which would fire immediately and defeat the snooze.
     *
     * [item] is the item as it stands now, used to re-word the reminder for tomorrow - an item
     * snoozed on the day before it expires is expiring *today* when the snooze lands. When it
     * can no longer be read (deleted in between), [fallbackTitle]/[fallbackBody] carry the
     * previous wording through rather than dropping the reminder.
     */
    fun snoozeUntilTomorrow(
        context: Context,
        itemId: String,
        item: PantryItem?,
        fallbackTitle: String?,
        fallbackBody: String?
    ) {
        val notificationSettings = AndroidSettingsRepository(context).getSettings().notifications
        // The reminder may have been switched off since it was posted. A deleted item (null)
        // still snoozes on its fallback copy, as before.
        if (item != null && !wantsIndividualReminder(notificationSettings, item)) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val tomorrow = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 1)
        val copy = item?.let { notificationCopy(context, it.name, it.brand, it.daysRemaining(tomorrow)) }
        val title = copy?.first ?: fallbackTitle
        val body = copy?.second ?: fallbackBody

        val calendar = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, notificationSettings.hour)
            set(Calendar.MINUTE, notificationSettings.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = System.currentTimeMillis()
        val triggerMillis = calendar.timeInMillis.takeIf { it > now } ?: (now + SNOOZE_FALLBACK_MILLIS)

        val intent = Intent(context, ExpiryNotificationReceiver::class.java).apply {
            putExtra(ExpiryNotificationReceiver.EXTRA_ITEM_ID, itemId)
            putExtra(ExpiryNotificationReceiver.EXTRA_TITLE, title)
            putExtra(ExpiryNotificationReceiver.EXTRA_BODY, body)
        }
        // Same request code as the original reminder, so a snooze replaces that item's pending
        // alarm rather than leaving two to fire.
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelNotification(context: Context, itemId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ExpiryNotificationReceiver::class.java)

        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            intent,
            flags
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        // Also dismiss any currently displayed notification for this item
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(itemId.hashCode())
    }

    /**
     * Cancels every item's pending alarm and, for still-active items, re-schedules it against
     * the current settings. Used after a notification-settings change (enabled/lead time/time of
     * day) so already-added items pick up the new configuration without per-field diffing.
     */
    fun rescheduleAll(context: Context, items: List<PantryItem>) {
        items.forEach { cancelNotification(context, it.id) }
        items.forEach { scheduleNotification(context, it) }
    }

    /**
     * Puts back every reminder alarm still to come, per item and the digest, from what is
     * stored. Android drops an app's alarms when the device restarts and when the app is
     * force-stopped, and nothing else would set them again until a notification setting
     * changed. Run after a restart or an update (see ReminderRestoreReceiver) and whenever the
     * app opens. A reminder whose time has passed is left alone rather than fired again, and
     * nothing is cancelled, so reminders already on screen stay.
     */
    suspend fun restoreReminders(context: Context, repository: PantryRepository) {
        val settings = AndroidSettingsRepository(context).getSettings().notifications
        repository.getItemsFlow().first().forEach { scheduleNotification(context, it, fireIfPast = false) }
        scheduleDigest(context, settings)
    }

    /** Used only if tomorrow's reminder slot has somehow already passed. */
    private const val SNOOZE_FALLBACK_MILLIS = 60 * 60 * 1000L

    private const val DIGEST_REQUEST_CODE = -1000

    /**
     * Schedules (or, if notifications or the summary are disabled, cancels) the digest that
     * names what expires within the coming week in a single notification. Fires at the same
     * reminder time as per-item alerts - every day, every N days, or weekly on the chosen weekday - and
     * reschedules itself for the next occurrence each time it fires: a one-shot-and-reschedule
     * alarm, like [scheduleNotification], rather than `setRepeating`, which isn't reliably exact
     * under Doze.
     */
    fun scheduleDigest(context: Context, notificationSettings: NotificationSettings) {
        if (!notificationSettings.enabled || !notificationSettings.digestEnabled) {
            cancelDigest(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val calendar = nextDigestTime(Calendar.getInstance(), notificationSettings)

        val intent = Intent(context, DigestNotificationReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, DIGEST_REQUEST_CODE, intent, flags)

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * The next moment, strictly after [now], the digest should fire under [settings]: the first
     * day from today that [isDigestDay] accepts, at the reminder time - today counts only while
     * its slot is still ahead.
     */
    internal fun nextDigestTime(now: Calendar, settings: NotificationSettings): Calendar {
        val nowMillis = now.timeInMillis
        val candidate = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, settings.hour)
            set(Calendar.MINUTE, settings.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Every schedule repeats within 7 days, so 8 candidates (today included) always hit.
        repeat(8) {
            val day = java.time.LocalDate.of(
                candidate.get(Calendar.YEAR), candidate.get(Calendar.MONTH) + 1, candidate.get(Calendar.DAY_OF_MONTH)
            )
            if (candidate.timeInMillis > nowMillis && settings.isDigestDay(day.toEpochDay(), day.dayOfWeek.value)) {
                return candidate
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1)
        }
        return candidate
    }

    fun cancelDigest(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, DigestNotificationReceiver::class.java)
        val flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, DIGEST_REQUEST_CODE, intent, flags)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
