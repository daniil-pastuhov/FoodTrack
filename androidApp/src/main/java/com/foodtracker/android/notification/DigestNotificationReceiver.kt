package com.foodtracker.android.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.foodtracker.android.MainActivity
import com.foodtracker.android.R
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.android.util.daysRemainingLabel
import com.foodtracker.model.PantryItem
import com.foodtracker.repository.PantryRepository
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fires once a day at the configured reminder time and posts a single notification naming the
 * items that are actually in trouble — a lighter-weight cadence than one alarm per item, useful
 * once several items cluster around the same expiry window. Always reschedules itself for the
 * next day first (see [NotificationHelper.scheduleDigest]), so a quiet day with nothing to
 * report doesn't silently end the daily cadence.
 */
class DigestNotificationReceiver : BroadcastReceiver(), KoinComponent {

    private val repository: PantryRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val notificationSettings = AndroidSettingsRepository(context).getSettings().notifications
        NotificationHelper.scheduleDigest(context, notificationSettings)
        if (!notificationSettings.enabled) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val today = DateUtils.today()
                val expiringThisWeek = repository.getItemsFlow().first()
                    .filter { it.daysRemaining(today) in 0..DIGEST_WINDOW_DAYS }
                    .sortedBy { it.daysRemaining(today) }
                if (expiringThisWeek.isNotEmpty()) {
                    postDigestNotification(context, expiringThisWeek)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Posts the digest, naming the [NAMED_ITEM_COUNT] most urgent items rather than only
     * counting them: "3 items expiring this week" tells the user nothing they can act on
     * without opening the app, which is the thing the notification exists to save them.
     */
    private fun postDigestNotification(context: Context, items: List<PantryItem>) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingTapIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val named = items.take(NAMED_ITEM_COUNT)
        val body = buildString {
            append(named.joinToString(separator = "\n") { item ->
                context.getString(
                    R.string.notif_digest_item_format,
                    item.name,
                    daysRemainingLabel(context, item.daysRemaining(DateUtils.today()))
                )
            })
            val remaining = items.size - named.size
            if (remaining > 0) {
                append("\n")
                append(context.resources.getQuantityString(
                    R.plurals.notif_digest_more_format,
                    remaining,
                    remaining
                ))
            }
        }
        val title = context.resources.getQuantityString(
            R.plurals.notif_digest_title_format,
            items.size,
            items.size
        )

        // Opens the app and lets it raise the share sheet: a BroadcastReceiver can't show a
        // chooser itself. MainActivity resolves the ids against its own live pantry state, so an
        // item eaten between this firing and the tap simply drops out of the prompt.
        val recipeIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_RECIPE_FOR_IDS, ArrayList(items.map { it.id }))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingRecipeIntent = PendingIntent.getActivity(
            context,
            RECIPE_REQUEST_CODE,
            recipeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingTapIntent)
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notif_action_recipe_ideas),
                pendingRecipeIntent
            )
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(DIGEST_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    companion object {
        const val DIGEST_NOTIFICATION_ID = -1000

        /** How far ahead the digest looks. */
        private const val DIGEST_WINDOW_DAYS = 7

        /** How many items the body names before collapsing the rest into "+N more". Three keeps
         *  the collapsed notification readable; the rest are still in the recipe prompt. */
        private const val NAMED_ITEM_COUNT = 3

        /** Distinct from the tap intent's request code, or `getActivity` hands back the same
         *  cached PendingIntent for both and the action loses its extras. */
        private const val RECIPE_REQUEST_CODE = -1001
    }
}
