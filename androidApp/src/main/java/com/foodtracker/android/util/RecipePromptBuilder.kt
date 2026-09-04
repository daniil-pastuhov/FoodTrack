package com.foodtracker.android.util

import android.content.Context
import com.foodtracker.android.R
import com.foodtracker.model.PantryItem
import com.foodtracker.util.DateUtils

/** Builds a device-language prompt asking an AI assistant for recipe ideas using the given items. */
fun buildRecipePrompt(context: Context, items: List<PantryItem>): String {
    val today = DateUtils.today()
    val lines = items
        .sortedBy { it.daysRemaining(today) }
        .joinToString(separator = "\n") { item ->
            val expiry = daysRemainingLabel(context, item.daysRemaining(today))
            "- " + context.getString(R.string.recipe_prompt_item_format, item.name, expiry)
        }
    return context.getString(R.string.recipe_prompt_intro) + "\n\n" + lines
}
