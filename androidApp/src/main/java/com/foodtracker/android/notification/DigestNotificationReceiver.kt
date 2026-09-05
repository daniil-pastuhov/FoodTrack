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
import com.foodtracker.repository.PantryRepository
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fires once a day at the configured reminder time and posts a single "N items expiring this
 * week" notification when there's anything to report — a lighter-weight cadence than one alarm
 * per item, useful once several items cluster around the same expiry window. Always reschedules
 * itself for the next day first (see [NotificationHelper.scheduleDigest]), so a quiet day with
 * nothing to report doesn't silently end the daily cadence.
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
                val items = repository.getItemsFlow().first()
                val expiringThisWeek = items.count { it.daysRemaining(today) in 0..7 }
                if (expiringThisWeek > 0) {
                    postDigestNotification(context, expiringThisWeek)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postDigestNotification(context: Context, count: Int) {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingTapIntent = PendingIntent.getActivity(
            context,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = context.resources.getQuantityString(R.plurals.notif_digest_body_format, count, count)

        val notification = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(context.getString(R.string.notif_digest_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingTapIntent)
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
    }
}
