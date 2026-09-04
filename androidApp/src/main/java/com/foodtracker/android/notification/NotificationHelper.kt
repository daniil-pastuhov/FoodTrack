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
import com.foodtracker.model.ItemStatus
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

        // Compute trigger time: 9:00 AM on expiry date in local time
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

        // If scheduled for today but 9:00 AM has already passed, schedule 5 seconds from now
        if (triggerMillis <= now) {
            triggerMillis = now + 5000L
        }

        val intent = Intent(context, ExpiryNotificationReceiver::class.java).apply {
            putExtra(ExpiryNotificationReceiver.EXTRA_ITEM_ID, payload.id)
            putExtra(ExpiryNotificationReceiver.EXTRA_TITLE, title)
            putExtra(ExpiryNotificationReceiver.EXTRA_BODY, body)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            item.id.hashCode(),
            intent,
            flags
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelNotification(context: Context, itemId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, ExpiryNotificationReceiver::class.java)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }

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
        items.filter { it.status == ItemStatus.ACTIVE }.forEach { scheduleNotification(context, it) }
    }
}
