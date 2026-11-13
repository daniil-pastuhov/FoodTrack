package com.foodtracker.android.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which wording a per-item reminder gets either side of the expiry date. Resolved against the
 *  real resources, because the plural forms are what can go wrong per locale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun copy(daysRemaining: Int, brand: String? = null) =
        NotificationHelper.notificationCopy(context, "Bread", brand, daysRemaining)

    @Test
    fun `one day past the date has its own wording`() {
        assertEquals("Worth a look" to "Bread is 1 day past its date.", copy(-1))
    }

    @Test
    fun `the expiry day itself still says today`() {
        assertEquals("Best used today" to "Bread reaches its date today.", copy(0))
    }

    @Test
    fun `the day before says tomorrow`() {
        assertEquals("Best used soon" to "Bread reaches its date tomorrow.", copy(1))
    }

    @Test
    fun `the day before keeps the brand`() {
        assertEquals("Bread (Acme) reaches its date tomorrow.", copy(1, "Acme").second)
    }

    @Test
    fun `several days past uses the plural and keeps the brand`() {
        assertEquals("Worth a look" to "Bread (Acme) is 3 days past its date.", copy(-3, "Acme"))
    }

    @Test
    fun `several days ahead uses the plural and keeps the brand`() {
        assertEquals("Best used soon" to "Bread (Acme) reaches its date in 3 days.", copy(3, "Acme"))
    }

    @Test
    fun `the expiry day keeps the brand`() {
        assertEquals("Bread (Acme) reaches its date today.", copy(0, "Acme").second)
    }

    @Test
    fun `no reminder wording raises its voice`() {
        (-3..3).map { copy(it) }.forEach { (title, body) ->
            listOf(title, body).forEach { text ->
                assertFalse(text, text.contains("!") || text.contains("⚠"))
            }
        }
    }

    @Test
    fun `a blank brand is left out`() {
        assertEquals("Bread is 1 day past its date.", copy(-1, " ").second)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian picks each plural form`() {
        assertEquals("Стоит проверить", copy(-1).first)
        assertEquals("Bread: срок годности прошёл 1 день назад.", copy(-1).second)
        assertEquals("Bread: срок годности прошёл 2 дня назад.", copy(-2).second)
        assertEquals("Bread: срок годности прошёл 5 дней назад.", copy(-5).second)
        assertEquals("Bread: срок годности прошёл 21 день назад.", copy(-21).second)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `russian counts down in each plural form`() {
        assertEquals("Лучше использовать сегодня", copy(0).first)
        assertEquals("Bread: срок годности заканчивается сегодня.", copy(0).second)
        assertEquals("Лучше использовать скоро", copy(1).first)
        assertEquals("Bread: срок годности заканчивается завтра.", copy(1).second)
        assertEquals("Bread: срок годности заканчивается через 2 дня.", copy(2).second)
        assertEquals("Bread: срок годности заканчивается через 5 дней.", copy(5).second)
        assertEquals("Bread: срок годности заканчивается через 21 день.", copy(21).second)
    }

    @Test
    @Config(qualifiers = "nb")
    fun `norwegian counts down`() {
        assertEquals("Best å bruke i dag" to "Bread når datoen sin i dag.", copy(0))
        assertEquals("Best å bruke snart" to "Bread når datoen sin i morgen.", copy(1))
        assertEquals("Bread når datoen sin om 2 dager.", copy(2).second)
    }

    @Test
    @Config(qualifiers = "nb")
    fun `norwegian picks each plural form`() {
        assertEquals("Verdt en titt", copy(-1).first)
        assertEquals("Bread er 1 dag over datoen.", copy(-1).second)
        assertEquals("Bread er 2 dager over datoen.", copy(-2).second)
    }
}
