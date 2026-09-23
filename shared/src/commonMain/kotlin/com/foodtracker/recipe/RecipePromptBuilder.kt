package com.foodtracker.recipe

import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate

/**
 * Cap on the "other items" section, in characters.
 *
 * On Android the prompt rides `Intent.EXTRA_TEXT`, which shares the whole process's ~1MB Binder
 * transaction buffer (`TransactionTooLargeException` beyond that), so a large pantry must never
 * be dumped in uncapped. iOS share sheets have no hard equivalent but degrade badly on very
 * large strings, so the same cap applies on both — this keeps the section a small fraction of
 * the buffer no matter how many extra items are offered.
 */
const val MAX_OTHER_ITEMS_CHARS = 20_000

/**
 * The localized text [buildRecipePrompt] needs, supplied by the caller.
 *
 * Passed in rather than looked up, because `shared` has no access to either platform's string
 * catalog — the same reason `PantryStateHolder` takes `uncategorizedLabel` as a constructor
 * parameter. The two substituting entries are lambdas rather than format strings so that common
 * code never has to implement positional-argument formatting: each platform applies its own
 * (`Context.getString` / `String(format:)`).
 */
data class RecipePromptStrings(
    val intro: String,
    /** "%1$s (%2$s)" — the item's name and how long it has left. */
    val itemFormat: (name: String, expiry: String) -> String,
    val otherItemsIntro: String,
    /** "(+%1$d more items not shown)" */
    val otherItemsOmitted: (count: Int) -> String,
    /** Renders "expires in 3 days" / "expired 2 days ago" for a day count. */
    val daysRemainingLabel: (days: Int) -> String
)

/**
 * Builds a prompt asking an AI assistant for recipe ideas using [items], in whatever language
 * [strings] was populated with.
 *
 * [otherItems] is an optional list of additional pantry items (e.g. everything not explicitly
 * selected) offered to the AI as extra ingredients it could also use. Only names are listed —
 * their expiry dates aren't relevant to a "here's what else I have" aside — and the list is
 * silently truncated at [MAX_OTHER_ITEMS_CHARS]; see that constant's doc for why.
 */
fun buildRecipePrompt(
    strings: RecipePromptStrings,
    today: LocalDate,
    items: List<PantryItem>,
    otherItems: List<PantryItem> = emptyList()
): String {
    val lines = items
        .sortedBy { it.daysRemaining(today) }
        .joinToString(separator = "\n") { item ->
            "- " + strings.itemFormat(item.name, strings.daysRemainingLabel(item.daysRemaining(today)))
        }

    val prompt = StringBuilder(strings.intro)
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
                .append(strings.otherItemsIntro)
                .append(" ")
                .append(otherNames)
            val omittedCount = otherItems.size - includedCount
            if (omittedCount > 0) {
                prompt.append(" ").append(strings.otherItemsOmitted(omittedCount))
            }
        }
    }
    return prompt.toString()
}
