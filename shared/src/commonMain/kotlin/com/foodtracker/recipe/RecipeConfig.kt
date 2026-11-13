package com.foodtracker.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The Recipe tab's configuration, the same shape on Android, iOS and the server. Serialised in
 * camelCase with the enum values below, so a stored or sent config means the same thing
 * everywhere. iOS mirrors this by hand (iOS does not consume `shared`), so a change here has to
 * be ported to `iosApp/FoodTracker/Models/` and to `functions/` as well.
 */

@Serializable
enum class RecipeDifficulty {
    @SerialName("easy") EASY,
    @SerialName("medium") MEDIUM,
    @SerialName("ambitious") AMBITIOUS
}

@Serializable
enum class RecipeDetail {
    @SerialName("brief") BRIEF,
    @SerialName("standard") STANDARD,
    @SerialName("stepByStep") STEP_BY_STEP
}

@Serializable
enum class RecipeMealType {
    @SerialName("any") ANY,
    @SerialName("breakfast") BREAKFAST,
    @SerialName("lunch") LUNCH,
    @SerialName("dinner") DINNER,
    @SerialName("snack") SNACK,
    @SerialName("dessert") DESSERT
}

@Serializable
enum class RecipeDiet {
    @SerialName("none") NONE,
    @SerialName("vegetarian") VEGETARIAN,
    @SerialName("vegan") VEGAN,
    @SerialName("pescatarian") PESCATARIAN,
    @SerialName("glutenFree") GLUTEN_FREE,
    @SerialName("dairyFree") DAIRY_FREE
}

const val MIN_RECIPE_SERVINGS = 1
const val MAX_RECIPE_SERVINGS = 12
const val DEFAULT_RECIPE_SERVINGS = 2

/** The cooking-time limits the form offers, in minutes. Null, "Any", is offered as well. */
val RECIPE_MAX_MINUTES_OPTIONS: List<Int> = listOf(15, 30, 60)
const val DEFAULT_RECIPE_MAX_MINUTES = 30

/**
 * The languages a recipe can be written in, as language codes. The app itself is in the first
 * three; the rest are offered because a recipe can be in a language the app isn't.
 */
val RECIPE_LANGUAGES: List<String> = listOf("en", "nb", "ru", "de", "es", "fr", "it", "pl", "sv", "uk")

/** Each language's name in itself, as the picker shows it whatever the app's language. */
fun recipeLanguageEndonym(code: String): String = when (code) {
    "en" -> "English"
    "nb" -> "Norsk bokmål"
    "ru" -> "Русский"
    "de" -> "Deutsch"
    "es" -> "Español"
    "fr" -> "Français"
    "it" -> "Italiano"
    "pl" -> "Polski"
    "sv" -> "Svenska"
    "uk" -> "Українська"
    else -> code
}

/** The code of the app language [appLanguage] (a locale's language, such as "no" or "nb-NO")
 *  among [RECIPE_LANGUAGES], with English for anything else. */
fun appRecipeLanguage(appLanguage: String): String {
    val base = appLanguage.lowercase().substringBefore('-').substringBefore('_')
    return when (base) {
        "no", "nn", "nb" -> "nb"
        in RECIPE_LANGUAGES -> base
        else -> "en"
    }
}

@Serializable
data class RecipeConfig(
    val difficulty: RecipeDifficulty = RecipeDifficulty.EASY,
    val detail: RecipeDetail = RecipeDetail.STANDARD,
    val servings: Int = DEFAULT_RECIPE_SERVINGS,
    /** 15, 30 or 60; null means any length of time. */
    val maxMinutes: Int? = DEFAULT_RECIPE_MAX_MINUTES,
    val mealType: RecipeMealType = RecipeMealType.ANY,
    val diet: RecipeDiet = RecipeDiet.NONE,
    /** One of [RECIPE_LANGUAGES], or null to follow the app's language. */
    val language: String? = null
) {
    /** The same config with anything out of range brought back in, for a value read from storage
     *  or a hand-edited preference. */
    fun normalized(): RecipeConfig = copy(
        servings = servings.coerceIn(MIN_RECIPE_SERVINGS, MAX_RECIPE_SERVINGS),
        maxMinutes = when (maxMinutes) {
            null, in RECIPE_MAX_MINUTES_OPTIONS -> maxMinutes
            else -> DEFAULT_RECIPE_MAX_MINUTES
        },
        language = language?.takeIf { it in RECIPE_LANGUAGES }
    )

    /** The language recipes are written in: [language], or the app's own when it is null. */
    fun resolvedLanguage(appLanguage: String): String = language ?: appRecipeLanguage(appLanguage)
}
