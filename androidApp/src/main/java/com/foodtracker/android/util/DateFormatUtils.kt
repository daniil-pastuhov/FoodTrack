package com.foodtracker.android.util

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Renders [date] using the device's locale date order/format (e.g. DD/MM/YYYY vs MM/DD/YYYY), never a fixed one. */
@Composable
fun formatLocalDate(date: LocalDate, style: FormatStyle = FormatStyle.MEDIUM): String {
    val locale = LocalConfiguration.current.locales[0]
    // Building the formatter is a CLDR pattern lookup, not a cheap allocation, and it depends
    // only on locale and style — so it's remembered on those rather than rebuilt on every
    // recomposition (which, for the Add/Edit screen, meant on every keystroke in any field).
    val formatter = remember(locale, style) {
        DateTimeFormatter.ofLocalizedDate(style).withLocale(locale)
    }
    return remember(date, formatter) { date.toJavaLocalDate().format(formatter) }
}

@Composable
fun formatMonthYear(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(date, locale) {
        val monthName = date.toJavaLocalDate().month.getDisplayName(TextStyle.FULL, locale)
            .replaceFirstChar { it.titlecase(locale) }
        "$monthName ${date.year}"
    }
}

/** A short "weekday, day month" label in the device's own order and wording (e.g. "Tue, 29 Sep"
 *  or "Tue, Sep 29"), for rows that sit under a multi-day heading. */
@Composable
fun formatWeekdayDay(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    }
    return remember(date, formatter) { date.toJavaLocalDate().format(formatter) }
}

/** Single-letter (or locale-appropriate narrow) weekday names, Monday first, to head the
 *  Monday-start week strip and month grid. */
@Composable
fun narrowWeekdayNames(): List<String> {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) {
        DayOfWeek.entries.map { it.getDisplayName(TextStyle.NARROW_STANDALONE, locale) }
    }
}
