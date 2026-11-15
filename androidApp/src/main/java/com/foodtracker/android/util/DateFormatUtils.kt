package com.foodtracker.android.util

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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

/** Day and month with no year, in the device's own order and wording (e.g. "18 Nov"). */
@Composable
fun formatDayMonth(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale)
    }
    return remember(date, formatter) { date.toJavaLocalDate().format(formatter) }
}
