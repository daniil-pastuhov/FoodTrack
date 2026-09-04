package com.foodtracker.android.util

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foodtracker.android.R

@Composable
fun daysRemainingLabel(days: Int): String {
    return daysRemainingLabel(LocalContext.current.resources, days)
}

fun daysRemainingLabel(resources: Resources, days: Int): String {
    return when {
        days < 0 -> resources.getQuantityString(R.plurals.expired_days_ago, -days, -days)
        days == 0 -> resources.getString(R.string.expires_today)
        days == 1 -> resources.getString(R.string.expires_tomorrow)
        else -> resources.getQuantityString(R.plurals.expires_in_days, days, days)
    }
}

fun daysRemainingLabel(context: Context, days: Int): String = daysRemainingLabel(context.resources, days)
