package com.foodtracker.android.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a per-item reminder offers, and which wording wins when it fires. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate(2026, 11, 5)

    private fun item(expiry: LocalDate) =
        PantryItem(id = "1", name = "Bread", expiryDate = expiry, addedDate = today)

    private fun reminder() = ExpiryNotificationReceiver.buildReminder(context, "1", "Title", "Body")

    @Test
    fun `offers Used it, Tomorrow and Remove, in that order`() {
        assertEquals(listOf("Used it", "Tomorrow", "Remove"), reminder().actions.map { it.title.toString() })
    }

    @Test
    fun `each button sends its own action`() {
        val pendingIntents = reminder().actions.map { it.actionIntent }
        assertEquals(3, pendingIntents.toSet().size)
    }

    @Test
    fun `posts on the calm channel`() {
        assertEquals(NotificationHelper.CHANNEL_ID, reminder().channelId)
    }

    @Test
    fun `an alarm set by an older version still fires with the current wording`() {
        val copy = NotificationHelper.reminderCopyAtFiring(
            context,
            item(LocalDate(2026, 11, 6)),
            today,
            extraTitle = "Product Expiring Soon! ⚠️",
            extraBody = "Bread expires in 1 day. Make sure to consume it!"
        )

        assertEquals("Best used soon" to "Bread reaches its date tomorrow.", copy)
    }

    @Test
    fun `the wording follows a date changed since the alarm was set`() {
        val copy = NotificationHelper.reminderCopyAtFiring(context, item(today), today, "Best used soon", "stale")

        assertEquals("Best used today" to "Bread reaches its date today.", copy)
    }

    @Test
    fun `an item that can no longer be read keeps the alarm's own wording`() {
        assertEquals(
            "Saved title" to "Saved body",
            NotificationHelper.reminderCopyAtFiring(context, null, today, "Saved title", "Saved body")
        )
    }

    @Test
    fun `with nothing to go on it uses the calm fallback`() {
        assertEquals(
            "Reminder from your pantry" to "Something in your pantry reaches its date soon.",
            NotificationHelper.reminderCopyAtFiring(context, null, today, null, null)
        )
    }
}
