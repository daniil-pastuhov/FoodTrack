package com.foodtracker.android.notification

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Moving per-item reminders to a calmer channel on the first start after the update. */
@RunWith(RobolectricTestRunner::class)
// The plain Application: FoodTrackerApp would create the channels before each test could set up.
@Config(sdk = [34], application = Application::class)
class ReminderChannelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun clearChannels() {
        listOf(NotificationHelper.CHANNEL_ID, NotificationHelper.LEGACY_CHANNEL_ID).forEach {
            manager.deleteNotificationChannel(it)
        }
    }

    private fun legacyChannel(importance: Int) = manager.createNotificationChannel(
        NotificationChannel(NotificationHelper.LEGACY_CHANNEL_ID, "old", importance)
    )

    @Test
    fun `the channel is new, not the old id`() {
        assertNotEquals(NotificationHelper.LEGACY_CHANNEL_ID, NotificationHelper.CHANNEL_ID)
    }

    @Test
    fun `a fresh install gets a default channel that does not vibrate`() {
        NotificationHelper.createNotificationChannel(context)

        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertFalse(channel.shouldVibrate())
        assertEquals("Reminders for each item", channel.name)
    }

    @Test
    fun `the old loud channel is deleted and its loudness is not carried over`() {
        legacyChannel(NotificationManager.IMPORTANCE_HIGH)

        NotificationHelper.createNotificationChannel(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.LEGACY_CHANNEL_ID))
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_ID).importance
        )
    }

    @Test
    fun `someone who turned the old channel off stays off`() {
        legacyChannel(NotificationManager.IMPORTANCE_NONE)

        NotificationHelper.createNotificationChannel(context)

        assertNull(manager.getNotificationChannel(NotificationHelper.LEGACY_CHANNEL_ID))
        assertEquals(
            NotificationManager.IMPORTANCE_NONE,
            manager.getNotificationChannel(NotificationHelper.CHANNEL_ID).importance
        )
    }

    @Test
    fun `a quieter choice than default is not raised or lowered, only a block carries`() {
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            NotificationHelper.itemChannelImportance(NotificationManager.IMPORTANCE_LOW)
        )
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, NotificationHelper.itemChannelImportance(null))
    }
}
