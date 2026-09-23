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

        // The notification's action buttons re-enter here with distinct action strings (rather
        // than separate receivers) so they can reuse the same Koin-injected PantryStateHolder.
        //
        // These cover what a user actually does at the moment the reminder fires. Until now the
        // only choices were "Remove" or open the app, which fits none of the common outcomes:
        // the food was eaten, it's still fine, or it's not a today problem.
        when (intent.action) {
            // deleteItem() already re-adds essential items to the shopping list, matching the
            // dashboard's own delete behavior.
            ACTION_REMOVE_ITEM -> {
                handleAction(context, itemId) { stateHolder.deleteItem(itemId) }
                return
            }
            // "Used it" is a consumption, not a deletion: decrementQuantity leaves a
            // multi-unit item in place with one fewer, and only re-stocks an essential once the
            // last one is gone. Deleting outright would silently lose the other units.
            ACTION_USED_ITEM -> {
                handleAction(context, itemId) { stateHolder.decrementQuantity(itemId) }
                return
            }
            // A printed date is a guess, and the user is looking at the actual food. Trust them.
            ACTION_STILL_GOOD -> {
                handleAction(context, itemId) {
                    // Reschedule from the new date: this notification has already fired, so
                    // without it the item would sit in the pantry past its extended date and
                    // never warn again.
                    stateHolder.extendExpiry(itemId, STILL_GOOD_DAYS) { updated ->
                        NotificationHelper.scheduleNotification(context, updated)
                    }
                }
                return
            }
            // Re-fires tomorrow at the same reminder time rather than dismissing: an item put
            // off today is exactly the one worth asking about again.
            ACTION_SNOOZE -> {
                NotificationManagerCompat.from(context).cancel(itemId.hashCode())
                NotificationHelper.snoozeUntilTomorrow(
                    context = context,
                    itemId = itemId,
                    title = intent.getStringExtra(EXTRA_TITLE),
                    body = intent.getStringExtra(EXTRA_BODY)
                )
                return
            }
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

        // Each action needs its own request code, or PendingIntent.getBroadcast returns the
        // same cached intent for all of them and every button performs whichever was built
        // first - the extras differ but FLAG_UPDATE_CURRENT only refreshes those, not the
        // action string the receiver branches on.
        fun actionIntent(action: String, requestOffset: Int): PendingIntent {
            val actionIntent = Intent(context, ExpiryNotificationReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_ITEM_ID, itemId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            return PendingIntent.getBroadcast(
                context,
                itemId.hashCode() + requestOffset,
                actionIntent,
                flags
            )
        }

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingTapIntent)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notif_action_used),
                actionIntent(ACTION_USED_ITEM, REQUEST_OFFSET_USED)
            )
            .addAction(
                R.drawable.ic_notification,
                context.resources.getQuantityString(
                    R.plurals.notif_action_still_good,
                    STILL_GOOD_DAYS,
                    STILL_GOOD_DAYS
                ),
                actionIntent(ACTION_STILL_GOOD, REQUEST_OFFSET_STILL_GOOD)
            )
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notif_action_snooze),
                actionIntent(ACTION_SNOOZE, REQUEST_OFFSET_SNOOZE)
            )
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

    /** Runs [action] off the broadcast's short main-thread window, then dismisses the
     *  notification. Every action ends the same way - the user has answered, so leaving the
     *  reminder on screen would invite answering it twice. */
    private fun handleAction(context: Context, itemId: String, action: suspend () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                action()
            } finally {
                NotificationManagerCompat.from(context).cancel(itemId.hashCode())
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        const val ACTION_REMOVE_ITEM = "com.foodtracker.action.REMOVE_ITEM"
        const val ACTION_USED_ITEM = "com.foodtracker.action.USED_ITEM"
        const val ACTION_STILL_GOOD = "com.foodtracker.action.STILL_GOOD"
        const val ACTION_SNOOZE = "com.foodtracker.action.SNOOZE"

        /** How far "Still good" pushes the expiry date out. Small on purpose: this is a nudge
         *  from someone looking at the food, not a reassessment of its shelf life. */
        const val STILL_GOOD_DAYS = 3

        // Distinct per action so each button gets its own PendingIntent (see actionIntent).
        private const val REQUEST_OFFSET_USED = 1
        private const val REQUEST_OFFSET_STILL_GOOD = 2
        private const val REQUEST_OFFSET_SNOOZE = 3
    }
}
