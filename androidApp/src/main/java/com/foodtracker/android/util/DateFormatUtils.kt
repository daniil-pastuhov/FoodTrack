package com.foodtracker.android.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
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
