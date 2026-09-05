package com.foodtracker.android.util

import android.content.Context
import com.foodtracker.android.R
import com.foodtracker.model.PantryItem
import com.foodtracker.util.DateUtils

/**
 * Cap on the "other items" section, in characters. [Intent.EXTRA_TEXT] rides the Binder
 * transaction buffer shared by the whole process (~1MB, [android.os.TransactionTooLargeException]
 * beyond that), so a large pantry must never be dumped in uncapped — this keeps that section a
 * small fraction of the buffer no matter how many extra items are optionally included.
 */
private const val MAX_OTHER_ITEMS_CHARS = 20_000

private fun itemLine(context: Context, item: PantryItem, today: kotlinx.datetime.LocalDate): String {
    val expiry = daysRemainingLabel(context, item.daysRemaining(today))
    return "- " + context.getString(R.string.recipe_prompt_item_format, item.name, expiry)
}

/**
 * Builds a device-language prompt asking an AI assistant for recipe ideas using [items].
 *
 * [otherItems] is an optional list of additional pantry items (e.g. everything not explicitly
 * selected) offered to the AI as extra ingredients it could also use. Only names are listed —
 * their expiry dates aren't relevant to a "here's what else I have" aside — and the list is
 * silently truncated at [MAX_OTHER_ITEMS_CHARS], see the constant's doc for why.
 */
fun buildRecipePrompt(
    context: Context,
    items: List<PantryItem>,
    otherItems: List<PantryItem> = emptyList()
): String {
    val today = DateUtils.today()
    val lines = items
        .sortedBy { it.daysRemaining(today) }
        .joinToString(separator = "\n") { itemLine(context, it, today) }

    val prompt = StringBuilder(context.getString(R.string.recipe_prompt_intro))
        .append("\n\n")
        .append(lines)

    if (otherItems.isNotEmpty()) {
        val names = otherItems.map { it.name }.sorted()
        val otherNames = StringBuilder()
        var includedCount = 0
        for (name in names) {
            val lengthIfAdded = otherNames.length + (if (otherNames.isEmpty()) 0 else 2) + name.length
            if (lengthIfAdded > MAX_OTHER_ITEMS_CHARS) break
            if (otherNames.isNotEmpty()) otherNames.append(", ")
            otherNames.append(name)
            includedCount++
        }
        if (includedCount > 0) {
            prompt.append("\n\n")
                .append(context.getString(R.string.recipe_prompt_other_items_intro))
                .append(" ")
                .append(otherNames)
            val omittedCount = otherItems.size - includedCount
            if (omittedCount > 0) {
                prompt.append(" ")
                    .append(context.getString(R.string.recipe_prompt_other_items_omitted, omittedCount))
            }
        }
    }
    return prompt.toString()
}
