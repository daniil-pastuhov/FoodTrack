package com.foodtracker.recipe

import kotlinx.serialization.json.jsonObject

/** At most this many items go to a model, as the server accepts: the phone's model has a small
 *  context window (under 4,000 tokens), and a recipe can't use thirty things anyway. */
const val MAX_RECIPE_INGREDIENTS = 30

/** At most this many staples go to the phone's model. */
const val MAX_PROMPT_STAPLES = 20

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
    "da" -> "Danish"
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
 *
 * [optional] ("Send all products") is the rest of the pantry, listed after the items to use up
 * as ones the recipe may use as well; together with [items] at most [MAX_RECIPE_INGREDIENTS],
 * the soonest first, since the model's context is small.
 *
 * [staples] ([RecipeUiState.stapleNames]) are the basics the person always has: listed last, at
 * most [MAX_PROMPT_STAPLES], as ones the recipe may use and never has to, and never "from the
 * pantry". They don't count towards the [MAX_RECIPE_INGREDIENTS] items.
 */
fun buildOnDeviceRecipePrompt(
    items: List<RecipePromptItem>,
    config: RecipeConfig,
    languageCode: String,
    allergens: Collection<String>,
    optional: List<RecipePromptItem> = emptyList(),
    staples: List<String> = emptyList()
): String {
    val normalized = config.normalized()
    fun line(item: RecipePromptItem) = "- ${cutName(item.name.trim())} (${dayPhrase(item.daysRemaining)})"
    val main = items.sortedBy { it.daysRemaining }.take(MAX_RECIPE_INGREDIENTS)
    val lines = main.joinToString("\n", transform = ::line)
    val extra = optional.sortedBy { it.daysRemaining }.take(MAX_RECIPE_INGREDIENTS - main.size)
    val basics = staples.take(MAX_PROMPT_STAPLES).map { cutName(it.trim()) }
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
        // The upper bound is a limit, the lower one only a guide.
        normalized.maxMinutes?.let { add("Total time: $it minutes or less") }
        normalized.minMinutes?.let { add("Aim for about $it minutes or more; less is fine if the dish needs less") }
        val meals = orderedMeals(normalized.mealTypes).map { it.name.lowercase() }
        when (meals.size) {
            0 -> Unit
            1 -> add("Meal: " + meals.single())
            else -> add("Meal: suits any one of " + meals.joinToString(", "))
        }
        // Every selected diet holds; one another implies is not repeated.
        val diets = promptDiets(normalized.diets).map { diet ->
            when (diet) {
                RecipeDiet.NONE -> "none"
                RecipeDiet.VEGETARIAN -> "vegetarian"
                RecipeDiet.VEGAN -> "vegan"
                RecipeDiet.PESCATARIAN -> "pescatarian"
                RecipeDiet.GLUTEN_FREE -> "gluten-free"
                RecipeDiet.DAIRY_FREE -> "dairy-free"
            }
        }
        when (diets.size) {
            0 -> Unit
            1 -> add("Diet: " + diets.single())
            else -> add("Diet: all of " + diets.joinToString(", "))
        }
        // Never cut: an allergen shortened to a different word would not protect anyone.
        val clean = cleanAllergens(allergens)
        if (clean.isNotEmpty()) add("Never use anything containing: " + clean.joinToString(", "))
    }
    val language = recipeLanguageEnglishName(languageCode)
    // The meal it is saved under (Saved recipes' tabs): asked of the model only when the config
    // leaves a choice; with one meal asked for, the app sets it itself ([recipeMealOf]).
    val askedMeals = orderedMeals(normalized.mealTypes)
    val mealChoices = (if (askedMeals.isEmpty()) RECIPE_MEAL_CHOICES else askedMeals).map { it.name.lowercase() }
    val asksMeal = askedMeals.size != 1
    return buildString {
        append("Write one home-cooking recipe that uses these items from my kitchen, ")
        append("the ones closest to their date first.")
        if (main.any { it.daysRemaining < 0 }) {
            // The owner's rule: an item past its date is the cook's choice and is used, never
            // dropped for its age; the app warns about it itself.
            append(" Items past their date are ones I have checked and chosen to use: do not leave them out for their date.")
        }
        append("\n")
        append(lines)
        if (extra.isNotEmpty()) {
            append("\n\nI also have these at home. Use any of them only where it helps the dish; none is needed:\n")
            append(extra.joinToString("\n", transform = ::line))
        }
        if (basics.isNotEmpty()) {
            append("\n\nI always have these basics too. Use any of them if the dish needs them; none is needed, and they are not items to use up:\n")
            append(basics.joinToString("\n") { "- $it" })
        }
        append("\n\nKeep to this:\n")
        append(rules.joinToString("\n") { "- $it" })
        append("\n\nWrite every text value in $language. Keep the JSON keys in English.\n")
        append("Reply with JSON only, no other text, in exactly this shape:\n")
        append(
            """{"title":"","summary":"one sentence","minutes":0,"servings":${normalized.servings},""" +
                """"difficulty":"${normalized.difficulty.name.lowercase()}",""" +
                (if (asksMeal) """"meal":"","""  else "") +
                """"ingredients":[{"amount":"100 g","name":"","fromPantry":true}],"steps":[""],"tip":""}"""
        )
        if (asksMeal) append("\nSet meal to the one of ${mealChoices.joinToString(", ")} that the dish suits best.")
        append("\nSet fromPantry to true only for items from the ")
        append(
            when {
                basics.isEmpty() -> if (extra.isEmpty()) "list above" else "lists above"
                extra.isEmpty() -> "first list above"
                else -> "first two lists above"
            }
        )
        append(". Leave tip empty if there is none.")
    }
}

/**
 * Reads a model's reply as a [Recipe], or null when it isn't one: no JSON, no title, no steps or
 * no ingredients. Tolerates what small models do: a code fence or a sentence around the JSON,
 * blank entries, an empty tip. An ingredient also counts as from the pantry when its name
 * matches one of [pantryNames], since a model sometimes forgets the flag. One that is not from the
 * pantry gets the staple among [stapleNames] it is, by name ([stapleNameOf]).
 */
fun parseRecipeReply(
    text: String,
    source: RecipeSource,
    config: RecipeConfig,
    pantryNames: Collection<String>,
    stapleNames: Collection<String> = emptyList()
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
            val fromPantry = it.fromPantry || matchesPantry(it.name)
            RecipeIngredient(
                amount = it.amount.trim(),
                name = it.name.trim(),
                fromPantry = fromPantry,
                stapleName = if (fromPantry) null else stapleNameOf(it.name.trim(), stapleNames)
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
        source = source,
        meal = recipeMealOf(config.normalized().mealTypes, recipe.meal)
    )
}
