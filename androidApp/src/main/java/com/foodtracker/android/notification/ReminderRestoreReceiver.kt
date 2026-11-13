package com.foodtracker.android.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foodtracker.repository.PantryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Sets every expiry reminder again after the device restarts or the app is updated. Android
 * drops alarms on a restart, so without this no reminder would fire until the app was opened
 * (see [NotificationHelper.restoreReminders]).
 */
class ReminderRestoreReceiver : BroadcastReceiver(), KoinComponent {

    private val repository: PantryRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationHelper.restoreReminders(context, repository)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
