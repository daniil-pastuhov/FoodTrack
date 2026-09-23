package com.foodtracker.android.util

import android.content.Context
import android.content.Intent
import com.foodtracker.android.R
import com.foodtracker.model.PantryItem
import com.foodtracker.recipe.RecipePromptStrings
import com.foodtracker.util.DateUtils
import com.foodtracker.recipe.buildRecipePrompt as buildSharedRecipePrompt

/** Reads the prompt's wording from this device's string catalog, so `shared`'s builder can stay
 *  free of any platform string lookup. */
fun recipePromptStrings(context: Context): RecipePromptStrings = RecipePromptStrings(
    intro = context.getString(R.string.recipe_prompt_intro),
    itemFormat = { name, expiry ->
        context.getString(R.string.recipe_prompt_item_format, name, expiry)
    },
    otherItemsIntro = context.getString(R.string.recipe_prompt_other_items_intro),
    otherItemsOmitted = { count ->
        context.getString(R.string.recipe_prompt_other_items_omitted, count)
    },
    daysRemainingLabel = { days -> daysRemainingLabel(context, days) }
)

/**
 * Builds a device-language prompt asking an AI assistant for recipe ideas using [items].
 *
 * The prompt itself is assembled by `shared`'s [buildSharedRecipePrompt] (so the digest
 * notification and iOS produce the same text); this only supplies the localized wording.
 */
fun buildRecipePrompt(
    context: Context,
    items: List<PantryItem>,
    otherItems: List<PantryItem> = emptyList()
): String = buildSharedRecipePrompt(
    strings = recipePromptStrings(context),
    today = DateUtils.today(),
    items = items,
    otherItems = otherItems
)

/**
 * Builds the prompt for [items] and hands it to the system share sheet.
 *
 * Shared by the dashboard's multi-select action and the digest notification's "Recipe ideas"
 * button, so both produce the same text through the same chooser. Does nothing for an empty
 * [items] — the digest can reach here with ids that no longer resolve (the item was eaten
 * between the notification firing and the button being tapped).
 */
fun shareRecipePrompt(
    context: Context,
    items: List<PantryItem>,
    otherItems: List<PantryItem> = emptyList()
) {
    if (items.isEmpty()) return
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, buildRecipePrompt(context, items, otherItems))
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}
