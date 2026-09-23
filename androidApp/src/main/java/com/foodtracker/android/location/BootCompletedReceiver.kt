package com.foodtracker.android.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.foodtracker.android.settings.AndroidSettingsRepository

/**
 * Re-registers the store geofences after a reboot.
 *
 * The OS drops every geofence when the device restarts and never restores them, so without this
 * the reminder silently stops working after the user's next restart — the worst kind of
 * failure, since nothing visibly changes.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        StoreGeofenceManager.sync(context, AndroidSettingsRepository(context).getSettings())
    }
}
