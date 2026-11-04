package com.foodtracker.android.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.ReminderStyle
import com.foodtracker.model.withReminderStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** When the settings screen shows the "notifications are blocked" warning, and which system
 *  screen its button opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockedNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val eachItem = NotificationSettings().withReminderStyle(ReminderStyle.EACH_ITEM)
    private val summary = NotificationSettings().withReminderStyle(ReminderStyle.SUMMARY)

    @Before
    fun setUp() {
        shadowOf(manager).setNotificationsEnabled(true)
        // Created directly, so each test can set a channel's importance without depending on
        // NotificationHelper.createNotificationChannel.
        listOf(NotificationHelper.CHANNEL_ID, NotificationHelper.DIGEST_CHANNEL_ID).forEach { id ->
            manager.createNotificationChannel(NotificationChannel(id, id, NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun blockChannel(id: String) {
        manager.deleteNotificationChannel(id)
        manager.createNotificationChannel(NotificationChannel(id, id, NotificationManager.IMPORTANCE_NONE))
    }

    @Test
    fun nothingBlockedShowsNoWarning() {
        assertNull(NotificationHelper.blockedNotificationsSettingsIntent(context, eachItem))
    }

    @Test
    fun appWideBlockOpensAppNotificationSettings() {
        shadowOf(manager).setNotificationsEnabled(false)
        val intent = NotificationHelper.blockedNotificationsSettingsIntent(context, eachItem)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent?.action)
        assertEquals(context.packageName, intent?.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun remindersSwitchedOffInTheAppShowNoWarningEvenWhenBlocked() {
        shadowOf(manager).setNotificationsEnabled(false)
        assertNull(NotificationHelper.blockedNotificationsSettingsIntent(context, eachItem.copy(enabled = false)))
    }

    @Test
    fun blockedChannelOfTheChosenStyleOpensThatChannel() {
        blockChannel(NotificationHelper.DIGEST_CHANNEL_ID)
        val intent = NotificationHelper.blockedNotificationsSettingsIntent(context, summary)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent?.action)
        assertEquals(NotificationHelper.DIGEST_CHANNEL_ID, intent?.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun blockedChannelOfAnUnusedStyleIsIgnored() {
        blockChannel(NotificationHelper.DIGEST_CHANNEL_ID)
        assertNull(NotificationHelper.blockedNotificationsSettingsIntent(context, eachItem))
    }

    @Test
    fun bothStylesReportTheBlockedItemChannel() {
        blockChannel(NotificationHelper.CHANNEL_ID)
        val both = NotificationSettings().withReminderStyle(ReminderStyle.BOTH)
        val intent = NotificationHelper.blockedNotificationsSettingsIntent(context, both)
        assertEquals(NotificationHelper.CHANNEL_ID, intent?.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }
}
