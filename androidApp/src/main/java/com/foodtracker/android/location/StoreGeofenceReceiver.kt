package com.foodtracker.android.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.foodtracker.android.MainActivity
import com.foodtracker.android.R
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.repository.ShoppingListRepository
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Posts the shopping list when the user settles at one of their saved shops.
 *
 * The list itself is complete and well-built; the failure mode this addresses is walking past
 * the shop without opening it.
 */
class StoreGeofenceReceiver : BroadcastReceiver(), KoinComponent {

    private val shoppingListRepository: ShoppingListRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val settings = AndroidSettingsRepository(context).getSettings()
        if (!settings.storeRemindersEnabled) return

        val storeId = event.triggeringGeofences?.firstOrNull()?.requestId
        val store = settings.stores.find { it.id == storeId }

        // Rate limit before doing any work. Geofence transitions retrigger readily — GPS drift,
        // or simply a shop passed every day — and a reminder that fires repeatedly is an
        // uninstall rather than a feature.
        if (!shouldNotify(context)) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entries = shoppingListRepository.getEntriesFlow().first()
                // Nothing to buy, nothing to say. Checked here rather than at registration time
                // because the list changes constantly while the geofences do not.
                if (entries.isEmpty()) return@launch
                recordNotified(context)
                postReminder(context, store?.label, entries)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postReminder(context: Context, storeLabel: String?, entries: List<ShoppingListEntry>) {
        createChannel(context)

        val named = entries.take(NAMED_ITEM_COUNT)
        val body = buildString {
            append(named.joinToString(separator = "\n") { it.productName })
            val remaining = entries.size - named.size
            if (remaining > 0) {
                append("\n")
                append(context.resources.getQuantityString(R.plurals.store_reminder_more_format, remaining, remaining))
            }
        }
        val title = if (storeLabel.isNullOrBlank()) {
            context.resources.getQuantityString(
                R.plurals.store_reminder_title_format,
                entries.size,
                entries.size
            )
        } else {
            context.getString(R.string.store_reminder_title_named_format, storeLabel)
        }

        val tapIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_SHOPPING_LIST, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.larder_primary))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    /**
     * Its own channel rather than the expiry one, so a user can silence store reminders — a
     * location-triggered nudge, and the one most likely to wear out its welcome — without also
     * losing the expiry alerts they installed the app for.
     */
    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.store_reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.store_reminder_channel_desc)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun shouldNotify(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_NOTIFIED, 0L)
        return System.currentTimeMillis() - last >= MIN_INTERVAL_MILLIS
    }

    private fun recordNotified(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_NOTIFIED, System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val CHANNEL_ID = "foodtracker_store_channel"
        private const val NOTIFICATION_ID = -2000
        private const val REQUEST_CODE = -2001
        private const val NAMED_ITEM_COUNT = 5

        private const val PREFS_NAME = "foodtracker_store_reminders"
        private const val KEY_LAST_NOTIFIED = "last_notified_millis"

        /** Shortest gap between two store reminders, whatever the geofences do in between. */
        private const val MIN_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
    }
}
