package com.foodtracker.android.util

import androidx.compose.runtime.Composable
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
    return date.toJavaLocalDate().format(DateTimeFormatter.ofLocalizedDate(style).withLocale(locale))
}

@Composable
fun formatMonthYear(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val monthName = date.toJavaLocalDate().month.getDisplayName(TextStyle.FULL, locale)
        .replaceFirstChar { it.titlecase(locale) }
    return "$monthName ${date.year}"
}
