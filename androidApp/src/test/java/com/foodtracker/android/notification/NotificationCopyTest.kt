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

    /** The languages added after en, ru and nb: each day gets its own translated wording, with
     *  the item and the count in it. Their exact wording is the translation's; this catches a
     *  missing or English string and a dropped placeholder. */
    @Test
    fun `every app language has its own wording each side of the date`() {
        for (language in listOf("es", "de", "fr", "sv", "da")) {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(configuration)
            for (days in listOf(-3, -1, 0, 1, 3)) {
                val (title, body) = NotificationHelper.notificationCopy(localized, "Bread", "Acme", days)
                val english = copy(days, "Acme")
                org.junit.Assert.assertNotEquals("$language $days", english.first, title)
                org.junit.Assert.assertNotEquals("$language $days", english.second, body)
                org.junit.Assert.assertTrue("$language $days: $body", "Bread" in body && "Acme" in body)
                if (days == -3 || days == 3) org.junit.Assert.assertTrue("$language $days: $body", "3" in body)
            }
        }
    }

    /** The words that tell each day's sentence apart, per language, read from the strings: the
     *  today body says that language's today and not its tomorrow, and vice versa; the past body
     *  says it is past the date, the countdown says it is still ahead. */
    private class DayWords(val today: String, val tomorrow: String, val past: String, val ahead: String)

    private val dayWords = mapOf(
        "en" to DayWords(today = "today", tomorrow = "tomorrow", past = "past its date", ahead = "reaches its date in"),
        "ru" to DayWords(today = "сегодня", tomorrow = "завтра", past = "прошёл", ahead = "через"),
        "nb" to DayWords(today = "i dag", tomorrow = "i morgen", past = "over datoen", ahead = "når datoen sin om"),
        "es" to DayWords(today = "hoy", tomorrow = "mañana", past = "pasó su fecha hace", ahead = "llega a su fecha en"),
        "de" to DayWords(today = "heute", tomorrow = "morgen", past = "überschritten", ahead = "erreicht in"),
        "fr" to DayWords(today = "aujourd'hui", tomorrow = "demain", past = "a dépassé sa date", ahead = "arrive à sa date dans"),
        "sv" to DayWords(today = "i dag", tomorrow = "i morgon", past = "över datum", ahead = "är om"),
        "da" to DayWords(today = "i dag", tomorrow = "i morgen", past = "over sin dato", ahead = "når sin dato om")
    )

    @Test
    fun `every app language says the right day in each body`() {
        for ((language, words) in dayWords) {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(configuration)
            fun body(days: Int) = NotificationHelper.notificationCopy(localized, "Bread", null, days).second
            val today = body(0)
            val tomorrow = body(1)
            for (n in listOf(1, 3, 5)) {
                val ahead = if (n == 1) tomorrow else body(n)
                val past = body(-n)
                val all = listOf(today, tomorrow, ahead, past).distinct()
                if (n != 1) org.junit.Assert.assertEquals("$language $n: four different sentences $all", 4, all.size)
                org.junit.Assert.assertNotEquals("$language: past $n differs from in $n", ahead, past)
                org.junit.Assert.assertTrue("$language -$n: $past", words.past in past)
                org.junit.Assert.assertFalse("$language -$n: $past", words.ahead in past || words.today in past || words.tomorrow in past)
                if (n != 1) {
                    org.junit.Assert.assertTrue("$language $n: $ahead", words.ahead in ahead && "$n" in ahead)
                    org.junit.Assert.assertFalse("$language $n: $ahead", words.past in ahead || words.today in ahead || words.tomorrow in ahead)
                }
                org.junit.Assert.assertTrue("$language -$n: $past", "$n" in past)
            }
            org.junit.Assert.assertTrue("$language today: $today", words.today in today)
            org.junit.Assert.assertFalse("$language today: $today", words.tomorrow in today || words.past in today || words.ahead in today)
            org.junit.Assert.assertTrue("$language tomorrow: $tomorrow", words.tomorrow in tomorrow)
            org.junit.Assert.assertFalse("$language tomorrow: $tomorrow", words.today in tomorrow || words.past in tomorrow || words.ahead in tomorrow)
        }
    }
}
