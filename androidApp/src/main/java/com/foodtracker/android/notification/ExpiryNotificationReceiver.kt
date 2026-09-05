package com.foodtracker.android.notification

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
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ExpiryNotificationReceiver : BroadcastReceiver(), KoinComponent {

    private val stateHolder: PantryStateHolder by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return

        // The notification's "Remove" action re-enters here with a distinct action string
        // (rather than a separate receiver) so it can reuse the same Koin-injected
        // PantryStateHolder — deleteItem() already re-adds essential items to the shopping
        // list, matching the dashboard's own delete behavior.
        if (intent.action == ACTION_REMOVE_ITEM) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    stateHolder.deleteItem(itemId)
                } finally {
                    NotificationManagerCompat.from(context).cancel(itemId.hashCode())
                    pendingResult.finish()
                }
            }
            return
        }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: context.getString(R.string.notif_fallback_title)
        val body = intent.getStringExtra(EXTRA_BODY) ?: context.getString(R.string.notif_fallback_body)

        // Intent to open app when tapping notification
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val pendingTapIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            flags
        )

        val removeIntent = Intent(context, ExpiryNotificationReceiver::class.java).apply {
            action = ACTION_REMOVE_ITEM
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        val pendingRemoveIntent = PendingIntent.getBroadcast(context, itemId.hashCode(), removeIntent, flags)

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingTapIntent)
            .addAction(R.drawable.ic_notification, context.getString(R.string.notif_action_remove), pendingRemoveIntent)
            .setAutoCancel(true)
            .build()

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(itemId.hashCode(), notification)
        } catch (e: SecurityException) {
            // Thrown if POST_NOTIFICATIONS permission not granted
            e.printStackTrace()
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        const val ACTION_REMOVE_ITEM = "com.foodtracker.action.REMOVE_ITEM"
    }
}
