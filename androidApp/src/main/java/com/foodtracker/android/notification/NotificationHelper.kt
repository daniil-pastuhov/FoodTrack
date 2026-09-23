package com.foodtracker.android.notification

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.foodtracker.android.R
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.notification.ExpiryNotificationCalculator
import com.foodtracker.util.DateUtils
import java.util.Calendar

object NotificationHelper {

    const val CHANNEL_ID = "foodtracker_expiry_channel"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                importance
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                enableVibration(true)
                enableLights(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun scheduleNotification(context: Context, item: PantryItem) {
        val notificationSettings = AndroidSettingsRepository(context).getSettings().notifications
        if (!notificationSettings.enabled) return

        val today = DateUtils.today()
        val payload = ExpiryNotificationCalculator.calculateNotification(
            item,
            today,
            leadDays = notificationSettings.leadDays,
            hour = notificationSettings.hour,
            minute = notificationSettings.minute
        ) ?: return

        val nameWithBrand = if (!payload.brand.isNullOrBlank()) {
            "${payload.itemName} (${payload.brand})"
        } else {
            payload.itemName
        }
        val title: String
        val body: String
        if (payload.expiresToday) {
            title = context.getString(R.string.notif_title_today)
            body = context.getString(R.string.notif_body_today_format, nameWithBrand)
        } else {
            title = context.getString(R.string.notif_title_soon)
            body = context.resources.getQuantityString(
                R.plurals.notif_body_soon_format,
                payload.daysRemaining,
                nameWithBrand,
                payload.daysRemaining
            )
        }

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
     */
    fun snoozeUntilTomorrow(context: Context, itemId: String, title: String?, body: String?) {
        val notificationSettings = AndroidSettingsRepository(context).getSettings().notifications
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

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

    /** Used only if tomorrow's reminder slot has somehow already passed. */
    private const val SNOOZE_FALLBACK_MILLIS = 60 * 60 * 1000L

    private const val DIGEST_REQUEST_CODE = -1000

    /**
     * Schedules (or, if notifications are disabled, cancels) the once-daily digest that
     * summarizes how many items expire within the coming week in a single notification.
     * Fires at the same reminder time as per-item alerts and reschedules itself for the next
     * day each time it fires — a one-shot-and-reschedule alarm, like [scheduleNotification],
     * rather than `setRepeating`, which isn't reliably exact under Doze.
     */
    fun scheduleDigest(context: Context, notificationSettings: NotificationSettings) {
        if (!notificationSettings.enabled) {
            cancelDigest(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, notificationSettings.hour)
            set(Calendar.MINUTE, notificationSettings.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, DigestNotificationReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, DIGEST_REQUEST_CODE, intent, flags)

        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
