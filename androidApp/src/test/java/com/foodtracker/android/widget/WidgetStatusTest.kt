package com.foodtracker.android.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.foodtracker.android.R
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a widget row says about an item, and which control it gets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate(2026, 11, 5)
    private val window = ExpiryWindow(today, expiringSoonDays = 3)

    private fun label(daysRemaining: Int): String {
        val item = PantryItem(
            id = "1",
            name = "Bread",
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + daysRemaining),
            addedDate = today
        )
        return widgetStatusLabel(context, item, item.expiryStatus(today, window.expiringSoonDays), window)
    }

    @Test
    fun `wording is Larder's, not the old expiry text`() {
        assertEquals("1 day over", label(-1))
        assertEquals("5 days over", label(-5))
        assertEquals("Today", label(0))
        assertEquals("Tomorrow", label(1))
        assertEquals("In 2 days", label(2))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian uses the design's wording`() {
        assertEquals("Просрочено 1 день назад", label(-1))
        assertEquals("Истекает сегодня", label(0))
        assertEquals("Истекает завтра", label(1))
        assertEquals("Через 2 дня", label(2))
    }

    @Test
    fun `each status needing attention has its own tint`() {
        val tints = listOf(ExpiryStatus.EXPIRED, ExpiryStatus.EXPIRING_TODAY, ExpiryStatus.EXPIRING_SOON)
            .map { widgetChipStyle(it).background }
        tints.forEach { assertNotNull(it) }
        assertEquals(3, tints.toSet().size)
    }

    @Test
    fun `fresh has no tint`() {
        assertNull(widgetChipStyle(ExpiryStatus.FRESH).background)
    }

    @Test
    fun `a passed date has a different icon from an upcoming one`() {
        assertNotEquals(
            widgetChipStyle(ExpiryStatus.EXPIRED).icon,
            widgetChipStyle(ExpiryStatus.EXPIRING_SOON).icon
        )
        assertEquals(R.drawable.ic_widget_status_clock, widgetChipStyle(ExpiryStatus.EXPIRING_TODAY).icon)
    }

    @Test
    fun `a two-cell widget gets the tick, a wide one the button`() {
        assertEquals(ExpiryRowAction.TICK, expiryRowAction(180.dp))
        assertEquals(ExpiryRowAction.TICK, expiryRowAction(249.dp))
        assertEquals(ExpiryRowAction.BUTTON, expiryRowAction(250.dp))
        assertEquals(ExpiryRowAction.BUTTON, expiryRowAction(358.dp))
    }
}
