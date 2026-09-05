package com.foodtracker.android.widget

import android.content.Context
import com.foodtracker.android.R
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate

/** Same "expires in/expired N days/months/years" text the dashboard shows, reusing the
 *  existing plural resources rather than duplicating the wording for the widget. */
fun expiryLabel(context: Context, item: PantryItem, today: LocalDate): String {
    val days = item.daysRemaining(today)
    return when {
        days < 0 -> context.resources.getQuantityString(R.plurals.expired_days_ago, -days, -days)
        days == 0 -> context.getString(R.string.expires_today)
        days == 1 -> context.getString(R.string.expires_tomorrow)
        else -> context.resources.getQuantityString(R.plurals.expires_in_days, days, days)
    }
}
