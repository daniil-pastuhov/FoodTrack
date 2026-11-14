package com.foodtracker.recipe

import kotlinx.serialization.json.jsonObject

/** At most this many items go to a model, as the server accepts: the phone's model has a small
 *  context window (under 4,000 tokens), and a recipe can't use thirty things anyway. */
const val MAX_RECIPE_INGREDIENTS = 30

/** Item names are cut to this length before they reach a model. */
const val MAX_RECIPE_INGREDIENT_NAME = 40

/** Each language's English name, for instructions written to a model in English. */
fun recipeLanguageEnglishName(code: String): String = when (code) {
    "en" -> "English"
    "nb" -> "Norwegian Bokmål"
    "ru" -> "Russian"
    "de" -> "German"
    "es" -> "Spanish"
    "fr" -> "French"
    "it" -> "Italian"
    "pl" -> "Polish"
    "sv" -> "Swedish"
    "uk" -> "Ukrainian"
    else -> "English"
}

/** At most [MAX_RECIPE_INGREDIENT_NAME] UTF-16 units, never splitting a character outside the
 *  Basic Multilingual Plane (an emoji, a rare CJK ideograph) in half. Any script passes through. */
private fun cutName(name: String): String {
    if (name.length <= MAX_RECIPE_INGREDIENT_NAME) return name
    val end = if (name[MAX_RECIPE_INGREDIENT_NAME - 1].isHighSurrogate()) MAX_RECIPE_INGREDIENT_NAME - 1 else MAX_RECIPE_INGREDIENT_NAME
    return name.substring(0, end)
}

/** One item as a model sees it. */
data class RecipePromptItem(val name: String, val daysRemaining: Int)

private fun dayPhrase(days: Int): String = when {
    days < -1 -> "${-days} days past its date"
    days == -1 -> "1 day past its date"
    days == 0 -> "its date is today"
    days == 1 -> "its date is tomorrow"
    else -> "$days days left"
}

/**
 * The instructions for the phone's own model. Written in English whatever the recipe's
 * language, because small models follow English instructions best, and the reply is asked for
 * as JSON in [Recipe]'s shape so it can be shown and saved like any other recipe. Nothing in it
 * is shown to the person.
 */
fun buildOnDeviceRecipePrompt(
    items: List<RecipePromptItem>,
    config: RecipeConfig,
    languageCode: String,
    allergens: Collection<String>
): String {
    val normalized = config.normalized()
    val lines = items
        .sortedBy { it.daysRemaining }
        .take(MAX_RECIPE_INGREDIENTS)
        .joinToString("\n") { "- ${cutName(it.name.trim())} (${dayPhrase(it.daysRemaining)})" }
    val rules = buildList {
        add(
            "Difficulty: " + when (normalized.difficulty) {
                RecipeDifficulty.EASY -> "easy, for a beginner"
                RecipeDifficulty.MEDIUM -> "medium"
                RecipeDifficulty.AMBITIOUS -> "ambitious, for a keen cook"
            }
        )
        add(
            "Method: " + when (normalized.detail) {
                RecipeDetail.BRIEF -> "brief, 3 to 5 short steps"
                RecipeDetail.STANDARD -> "standard, 4 to 8 steps"
                RecipeDetail.STEP_BY_STEP -> "step by step, every step spelled out for a beginner"
            }
        )
        add("Servings: ${normalized.servings}")
        normalized.maxMinutes?.let { add("Total time: $it minutes or less") }
        when (normalized.mealType) {
            RecipeMealType.ANY -> Unit
            else -> add("Meal: " + normalized.mealType.name.lowercase())
        }
        when (normalized.diet) {
            RecipeDiet.NONE -> Unit
            RecipeDiet.VEGETARIAN -> add("Diet: vegetarian")
            RecipeDiet.VEGAN -> add("Diet: vegan")
            RecipeDiet.PESCATARIAN -> add("Diet: pescatarian")
            RecipeDiet.GLUTEN_FREE -> add("Diet: gluten-free")
            RecipeDiet.DAIRY_FREE -> add("Diet: dairy-free")
        }
        // Never cut: an allergen shortened to a different word would not protect anyone.
        val clean = cleanAllergens(allergens)
        if (clean.isNotEmpty()) add("Never use anything containing: " + clean.joinToString(", "))
    }
    val language = recipeLanguageEnglishName(languageCode)
    return buildString {
        append("Write one home-cooking recipe that uses these items from my kitchen, ")
        append("the ones closest to their date first. Leave out anything that would be unsafe to eat.\n")
        append(lines)
        append("\n\nKeep to this:\n")
        append(rules.joinToString("\n") { "- $it" })
        append("\n\nWrite every text value in $language. Keep the JSON keys in English.\n")
        append("Reply with JSON only, no other text, in exactly this shape:\n")
        append(
            """{"title":"","summary":"one sentence","minutes":0,"servings":${normalized.servings},""" +
                """"difficulty":"${normalized.difficulty.name.lowercase()}",""" +
                """"ingredients":[{"amount":"100 g","name":"","fromPantry":true}],"steps":[""],"tip":""}"""
        )
        append("\nSet fromPantry to true only for items from the list above. Leave tip empty if there is none.")
    }
}

/**
 * Reads a model's reply as a [Recipe], or null when it isn't one: no JSON, no title, no steps or
 * no ingredients. Tolerates what small models do: a code fence or a sentence around the JSON,
 * blank entries, an empty tip. An ingredient also counts as from the pantry when its name
 * matches one of [pantryNames], since a model sometimes forgets the flag.
 */
fun parseRecipeReply(
    text: String,
    source: RecipeSource,
    config: RecipeConfig,
    pantryNames: Collection<String>
): Recipe? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val json = text.substring(start, end + 1)
    val parsed = runCatching {
        // Checked as an object first, so a stray array or string reads as "not a recipe".
        RecipeJson.parseToJsonElement(json).jsonObject
    }.getOrNull() ?: return null
    val recipe = runCatching { RecipeJson.decodeFromJsonElement(Recipe.serializer(), parsed) }
        .getOrNull() ?: return null
    val names = pantryNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    fun matchesPantry(name: String): Boolean {
        val lower = name.trim().lowercase()
        return names.any { lower.contains(it) || it.contains(lower) }
    }
    val ingredients = recipe.ingredients
        .filter { it.name.isNotBlank() }
        .map {
            RecipeIngredient(
                amount = it.amount.trim(),
                name = it.name.trim(),
                fromPantry = it.fromPantry || matchesPantry(it.name)
            )
        }
    val steps = recipe.steps.map { it.trim() }.filter { it.isNotEmpty() }
    val title = recipe.title.trim()
    if (title.isEmpty() || steps.isEmpty() || ingredients.isEmpty()) return null
    return Recipe(
        title = title,
        summary = recipe.summary.trim(),
        minutes = recipe.minutes.coerceAtLeast(0),
        servings = recipe.servings.takeIf { it in MIN_RECIPE_SERVINGS..MAX_RECIPE_SERVINGS } ?: config.normalized().servings,
        difficulty = recipe.difficulty,
        ingredients = ingredients,
        steps = steps,
        tip = recipe.tip?.trim()?.takeIf { it.isNotEmpty() },
        source = source
    )
}
