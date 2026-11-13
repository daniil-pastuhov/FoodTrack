package com.foodtracker.android.notification

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.settings.AndroidSettingsRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.repository.PantryRepository
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Restoring reminders after a restart, an update or an app open sets only what is still to
 *  come: a reminder already due isn't fired again, and nothing on screen is taken away. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private class Items(private val items: List<PantryItem>) : PantryRepository {
        override fun getItemsFlow(): Flow<List<PantryItem>> = flowOf(items)
        override suspend fun getItem(id: String) = items.firstOrNull { it.id == id }
        override suspend fun findByBarcode(barcode: String) = emptyList<PantryItem>()
        override suspend fun saveItem(item: PantryItem) = Unit
        override suspend fun deleteItem(id: String) = Unit
        override suspend fun clearAll() = Unit
    }

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

    private fun alarms() = shadowOf(alarmManager).scheduledAlarms

    @Before
    fun setUp() {
        alarms().toList().forEach { alarm -> alarm.operation?.let(alarmManager::cancel) }
        // Lead of one day at 09:00 unless a test says otherwise.
        saveNotifications(NotificationSettings())
    }

    private fun saveNotifications(notifications: NotificationSettings) =
        AndroidSettingsRepository(context).saveSettings(AppSettings(notifications = notifications))

    @Test
    fun setsAReminderStillToCome() = runTest {
        NotificationHelper.restoreReminders(context, Items(listOf(item("later", daysOut = 10))))
        assertEquals(1, alarms().size)
        assert(alarms().single().triggerAtTime > System.currentTimeMillis())
    }

    @Test
    fun doesNotFireAgainAReminderWhoseTimeHasPassed() = runTest {
        // Due today: its reminder day was yesterday, so a normal schedule would fire it at once.
        NotificationHelper.restoreReminders(context, Items(listOf(item("today", daysOut = 0))))
        assertEquals(0, alarms().size)
    }

    @Test
    fun doesNotMoveAPassedReminderDayToLaterToday() = runTest {
        // Reminders at 23:59, three days ahead. Due tomorrow, so its reminder day was two days
        // ago: a normal schedule moves it to today at 23:59, still to come. A restore must not,
        // or the reminder already shown comes back every evening until the date.
        saveNotifications(NotificationSettings(leadDays = 3, hour = 23, minute = 59))
        NotificationHelper.restoreReminders(context, Items(listOf(item("tomorrow", daysOut = 1))))
        assertEquals(0, alarms().size)
    }

    @Test
    fun skipsTodaysReminderOnceItsTimeHasGone() = runTest {
        // Reminders at 00:00, a day ahead: due tomorrow, so today's reminder time has gone by.
        saveNotifications(NotificationSettings(leadDays = 1, hour = 0, minute = 0))
        NotificationHelper.restoreReminders(context, Items(listOf(item("tomorrow", daysOut = 1))))
        assertEquals(0, alarms().size)
    }

    @Test
    fun skipsExpiredAndSilencedItems() = runTest {
        val items = listOf(item("gone", daysOut = -2), item("quiet", daysOut = 10, notificationsEnabled = false))
        NotificationHelper.restoreReminders(context, Items(items))
        assertEquals(0, alarms().size)
    }

    @Test
    fun leavesAReminderOnScreenAlone() = runTest {
        val notifications = context.getSystemService(NotificationManager::class.java)
        NotificationHelper.createNotificationChannel(context)
        val shown = item("shown", daysOut = 0)
        notifications.notify(
            shown.id.hashCode(),
            androidx.core.app.NotificationCompat.Builder(context, NotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("t")
                .build()
        )
        NotificationHelper.restoreReminders(context, Items(listOf(shown)))
        assertEquals(1, shadowOf(notifications).allNotifications.size)
    }
}
