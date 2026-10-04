package com.foodtracker.android.notification

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.data.local.FoodTrackerDatabase
import com.foodtracker.android.data.local.toDomain
import com.foodtracker.android.data.local.toEntity
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationGatingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun item(id: String, daysOut: Int, notificationsEnabled: Boolean = true): PantryItem {
        val today = DateUtils.today()
        return PantryItem(
            id = id,
            name = "Item $id",
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + daysOut),
            addedDate = today,
            notificationsEnabled = notificationsEnabled
        )
    }

    private fun saveNotifications(notifications: NotificationSettings) =
        AndroidSettingsRepository(context).saveSettings(AppSettings(notifications = notifications))

    /** Puts a reminder alarm in place for [item] under the same request code a real one uses.
     *  Goes through the snooze path with a null item so it uses the fallback copy: this module's
     *  Robolectric setup doesn't load app resources, which the first-time copy needs. */
    private fun armPendingReminder(item: PantryItem) =
        NotificationHelper.snoozeUntilTomorrow(context, item.id, null, "title", "body")

    private fun scheduledAlarmCount() = shadowOf(alarmManager).scheduledAlarms.size

    @Before
    fun clearAlarms() {
        shadowOf(alarmManager).scheduledAlarms.toList().forEach { alarm -> alarm.operation?.let(alarmManager::cancel) }
    }

    @Test
    fun individualReminderNeedsMasterIndividualAndItemSwitchesAllOn() {
        val on = item("a", daysOut = 5)
        assertTrue(NotificationHelper.wantsIndividualReminder(NotificationSettings(), on))
        assertFalse(NotificationHelper.wantsIndividualReminder(NotificationSettings(enabled = false), on))
        assertFalse(NotificationHelper.wantsIndividualReminder(NotificationSettings(individualEnabled = false), on))
        assertFalse(
            NotificationHelper.wantsIndividualReminder(NotificationSettings(), on.copy(notificationsEnabled = false))
        )
    }

    @Test
    fun turningAnItemOffCancelsItsPendingReminder() {
        saveNotifications(NotificationSettings())
        val milk = item("milk", daysOut = 5)
        armPendingReminder(milk)
        assertEquals(1, scheduledAlarmCount())

        NotificationHelper.scheduleNotification(context, milk.copy(notificationsEnabled = false))
        assertEquals(0, scheduledAlarmCount())
    }

    @Test
    fun turningIndividualAlertsOffCancelsEveryPendingReminder() {
        saveNotifications(NotificationSettings())
        val items = listOf(item("a", daysOut = 3), item("b", daysOut = 6))
        items.forEach(::armPendingReminder)
        assertEquals(2, scheduledAlarmCount())

        saveNotifications(NotificationSettings(individualEnabled = false))
        NotificationHelper.rescheduleAll(context, items)
        assertEquals(0, scheduledAlarmCount())
    }

    @Test
    fun snoozeIsSkippedForASilencedItemButNotForADeletedOne() {
        saveNotifications(NotificationSettings())
        NotificationHelper.snoozeUntilTomorrow(context, "a", item("a", 3, notificationsEnabled = false), "t", "b")
        assertEquals(0, scheduledAlarmCount())

        NotificationHelper.snoozeUntilTomorrow(context, "gone", null, "t", "b")
        assertEquals(1, scheduledAlarmCount())
    }

    @Test
    fun digestIsScheduledOnlyWhileTheSummaryIsOn() {
        NotificationHelper.scheduleDigest(context, NotificationSettings(digestEnabled = true))
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)

        NotificationHelper.scheduleDigest(context, NotificationSettings(digestEnabled = false))
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)

        NotificationHelper.scheduleDigest(context, NotificationSettings(enabled = false, digestEnabled = true))
        assertNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test
    fun digestLeavesOutSilencedExpiredAndDistantItemsMostUrgentFirst() {
        val today = DateUtils.today()
        val items = listOf(
            item("later", daysOut = 6),
            item("silenced", daysOut = 1, notificationsEnabled = false),
            item("soon", daysOut = 1),
            item("expired", daysOut = -1),
            item("distant", daysOut = 8)
        )
        assertEquals(
            listOf("soon", "later"),
            DigestNotificationReceiver.digestItems(items, today).map { it.id }
        )
    }

    @Test
    fun itemSwitchSurvivesARoomRoundTrip() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, FoodTrackerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val silenced = item("a", daysOut = 3, notificationsEnabled = false)
        db.pantryDao().upsert(silenced.toEntity())

        assertEquals(silenced, db.pantryDao().getById("a")!!.toDomain())
        db.close()
    }
}
