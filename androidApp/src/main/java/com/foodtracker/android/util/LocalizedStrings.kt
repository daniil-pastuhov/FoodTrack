package com.foodtracker.android.util

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foodtracker.android.R
import kotlin.math.roundToInt

private const val DAYS_PER_MONTH = 30.44
private const val DAYS_PER_YEAR = 365.25
private const val MONTHLY_DISPLAY_THRESHOLD_DAYS = 60

@Composable
fun daysRemainingLabel(days: Int): String {
    return daysRemainingLabel(LocalContext.current.resources, days)
}

fun daysRemainingLabel(resources: Resources, days: Int): String {
    return when {
        days < 0 -> pastDurationLabel(resources, -days)
        days == 0 -> resources.getString(R.string.expires_today)
        days == 1 -> resources.getString(R.string.expires_tomorrow)
        else -> futureDurationLabel(resources, days)
    }
}

fun daysRemainingLabel(context: Context, days: Int): String = daysRemainingLabel(context.resources, days)

private fun futureDurationLabel(resources: Resources, days: Int): String {
    if (days < MONTHLY_DISPLAY_THRESHOLD_DAYS) {
        return resources.getQuantityString(R.plurals.expires_in_days, days, days)
    }
    val months = (days / DAYS_PER_MONTH).roundToInt()
    if (months < 12) {
        return resources.getQuantityString(R.plurals.expires_in_months, months, months)
    }
    val years = (days / DAYS_PER_YEAR).roundToInt().coerceAtLeast(1)
    return resources.getQuantityString(R.plurals.expires_in_years, years, years)
}

private fun pastDurationLabel(resources: Resources, daysAgo: Int): String {
    if (daysAgo < MONTHLY_DISPLAY_THRESHOLD_DAYS) {
        return resources.getQuantityString(R.plurals.expired_days_ago, daysAgo, daysAgo)
    }
    val months = (daysAgo / DAYS_PER_MONTH).roundToInt()
    if (months < 12) {
        return resources.getQuantityString(R.plurals.expired_months_ago, months, months)
    }
    val years = (daysAgo / DAYS_PER_YEAR).roundToInt().coerceAtLeast(1)
    return resources.getQuantityString(R.plurals.expired_years_ago, years, years)
}
