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
    val daysRemainingLabel: (days: Int) -> String,
    /** Heads the list of what the recipe should keep to: "Please keep to this:". */
    val preferencesIntro: String,
    val difficulty: (RecipeDifficulty) -> String,
    val detail: (RecipeDetail) -> String,
    /** "For %1$d people" */
    val servings: (Int) -> String,
    /** "Ready in %1$d minutes or less" */
    val maxMinutes: (Int) -> String,
    /** Never asked for [RecipeMealType.ANY], which adds no line. */
    val mealType: (RecipeMealType) -> String,
    /** Never asked for [RecipeDiet.NONE], which adds no line. */
    val diet: (RecipeDiet) -> String,
    /** "Nothing that contains: %1$s", given the allergens already joined. */
    val allergens: (String) -> String,
    /** "Write the recipes in %1$s", given a code from [RECIPE_LANGUAGES]. */
    val language: (String) -> String
)

/**
 * Builds a prompt asking an AI assistant for recipe ideas using [items], in whatever language
 * [strings] was populated with.
 *
 * [config] adds what the Recipe tab's form says (difficulty, detail, servings, time, meal, diet,
 * and the language when it isn't the app's), and [allergens], from app Settings, are always
 * excluded. With no [config] the prompt is the plain list, as the digest's share once sent.
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
    otherItems: List<PantryItem> = emptyList(),
    config: RecipeConfig? = null,
    allergens: Collection<String> = emptyList()
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

    val preferences = preferenceLines(strings, config, allergens)
    if (preferences.isNotEmpty()) {
        prompt.append("\n\n")
            .append(strings.preferencesIntro)
            .append("\n")
            .append(preferences.joinToString(separator = "\n") { "- $it" })
    }
    return prompt.toString()
}

/** One line per thing the recipe has to keep to, in a fixed order. Allergens come last and
 *  apply even without a [config]. */
internal fun preferenceLines(
    strings: RecipePromptStrings,
    config: RecipeConfig?,
    allergens: Collection<String>
): List<String> {
    val lines = mutableListOf<String>()
    if (config != null) {
        val normalized = config.normalized()
        lines += strings.difficulty(normalized.difficulty)
        lines += strings.detail(normalized.detail)
        lines += strings.servings(normalized.servings)
        normalized.maxMinutes?.let { lines += strings.maxMinutes(it) }
        if (normalized.mealType != RecipeMealType.ANY) lines += strings.mealType(normalized.mealType)
        if (normalized.diet != RecipeDiet.NONE) lines += strings.diet(normalized.diet)
        normalized.language?.let { lines += strings.language(it) }
    }
    val cleanAllergens = cleanAllergens(allergens)
    if (cleanAllergens.isNotEmpty()) lines += strings.allergens(cleanAllergens.joinToString(", "))
    return lines
}

/** Trimmed, blank ones dropped, duplicates (ignoring case) dropped, sorted, so the same
 *  Settings give the same prompt. */
fun cleanAllergens(allergens: Collection<String>): List<String> =
    allergens.map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
