package com.foodtracker.android.widget

import android.content.Context
import com.foodtracker.android.ui.design.expiryStatusLabel
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem

/** The same words the app's status chip shows for [item]. */
internal fun widgetStatusLabel(
    context: Context,
    item: PantryItem,
    status: ExpiryStatus,
    window: ExpiryWindow
): String = expiryStatusLabel(
    resources = context.resources,
    locale = context.resources.configuration.locales[0],
    status = status,
    daysRemaining = item.daysRemaining(window.today),
    expiryDate = item.expiryDate,
    today = window.today
)
