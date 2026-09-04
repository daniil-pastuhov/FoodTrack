package com.foodtracker.android.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foodtracker.android.R

@Composable
fun daysRemainingLabel(days: Int): String {
    return when {
        days < 0 -> {
            val resources = LocalContext.current.resources
            resources.getQuantityString(R.plurals.expired_days_ago, -days, -days)
        }
        days == 0 -> stringResource(R.string.expires_today)
        days == 1 -> stringResource(R.string.expires_tomorrow)
        else -> {
            val resources = LocalContext.current.resources
            resources.getQuantityString(R.plurals.expires_in_days, days, days)
        }
    }
}
