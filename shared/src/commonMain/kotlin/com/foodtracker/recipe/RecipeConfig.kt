package com.foodtracker.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/** The legacy cooking-time limits: what a server that doesn't say `acceptsMultiChoiceConfig`
 *  takes as `maxMinutes` (see [legacyWireConfig]). */
val RECIPE_MAX_MINUTES_OPTIONS: List<Int> = listOf(15, 30, 60)
const val DEFAULT_RECIPE_MAX_MINUTES = 30

/**
 * The time slider's range and step, in minutes: 10 to 120 in steps of 5. A handle at its end means
 * no bound on that side (both at the ends: "Any"). 10 is about the shortest anything cooked takes
 * and 120 covers nearly every home recipe; past it "at least 120 min" (the lower handle at the top)
 * still asks for a long braise. Steps of 5 keep the legacy 15, 30 and 60 on the scale. The server
 * checks the same numbers (functions/src/contract.ts MINUTES_RANGE). iOS: `RecipeTime`.
 */
const val RECIPE_MINUTES_FLOOR = 10
const val RECIPE_MINUTES_CEILING = 120
const val RECIPE_MINUTES_STEP = 5

/** [minutes] brought onto the slider's scale (nearest step, within the range). */
fun snapRecipeMinutes(minutes: Int): Int {
    val clamped = minutes.coerceIn(RECIPE_MINUTES_FLOOR, RECIPE_MINUTES_CEILING)
    val steps = (clamped - RECIPE_MINUTES_FLOOR + RECIPE_MINUTES_STEP / 2) / RECIPE_MINUTES_STEP
    return RECIPE_MINUTES_FLOOR + steps * RECIPE_MINUTES_STEP
}

/** The meals a recipe can be for, in the order the form shows them ("Any" is none of them). */
val RECIPE_MEAL_CHOICES: List<RecipeMealType> = RecipeMealType.entries - RecipeMealType.ANY

/** The diets, in the order the form shows them ("None" is none of them). */
val RECIPE_DIET_CHOICES: List<RecipeDiet> = RecipeDiet.entries - RecipeDiet.NONE

/**
 * A chip tapped in a multi-choice row whose "Any" / "None" chip is the empty set: that chip
 * ([option] null) clears the others; another one is toggled, and turning the last one off brings
 * back the empty set by itself. iOS: `RecipeChoices.toggled`.
 */
fun <T> Set<T>.toggledChoice(option: T?): Set<T> = when {
    option == null -> emptySet()
    option in this -> this - option
    else -> this + option
}

/**
 * The selected diets less those another selected one already implies: vegan forbids all that
 * vegetarian, pescatarian and dairy-free do, vegetarian all that pescatarian does. The prompts
 * word only these (the strictest combination wins, so "pescatarian and vegetarian" reads as
 * vegetarian); every selected diet is still sent and checked. The server has the same table
 * (functions/src/adapters/prompt.ts `promptDiets`). iOS: `RecipeChoices.promptDiets`.
 */
fun promptDiets(diets: Set<RecipeDiet>): List<RecipeDiet> = RECIPE_DIET_CHOICES.filter { diet ->
    diet in diets && when (diet) {
        RecipeDiet.VEGETARIAN -> RecipeDiet.VEGAN !in diets
        RecipeDiet.PESCATARIAN -> RecipeDiet.VEGAN !in diets && RecipeDiet.VEGETARIAN !in diets
        RecipeDiet.DAIRY_FREE -> RecipeDiet.VEGAN !in diets
        else -> true
    }
}

/** The selected meals in the form's order. */
fun orderedMeals(meals: Set<RecipeMealType>): List<RecipeMealType> = RECIPE_MEAL_CHOICES.filter { it in meals }

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

/**
 * The form's settings. Stored as JSON (Android `AndroidRecipeConfigRepository`), read through
 * [recipeConfigFromStoredJson] so a config an older version saved, with one `mealType`, one `diet`
 * and a `maxMinutes` of 15, 30 or 60, still reads. Sent to the server as a [WireConfig].
 */
@Serializable
data class RecipeConfig(
    val difficulty: RecipeDifficulty = RecipeDifficulty.EASY,
    val detail: RecipeDetail = RecipeDetail.STANDARD,
    val servings: Int = DEFAULT_RECIPE_SERVINGS,
    /** The time slider's lower handle: a guide ("about this long or more"), never a limit. Null
     *  with the handle at [RECIPE_MINUTES_FLOOR]. */
    val minMinutes: Int? = null,
    /** The upper handle: a limit. Null with the handle at [RECIPE_MINUTES_CEILING]. */
    val maxMinutes: Int? = DEFAULT_RECIPE_MAX_MINUTES,
    /** The dish suits any one of these; empty is "Any". Never holds [RecipeMealType.ANY]. */
    val mealTypes: Set<RecipeMealType> = emptySet(),
    /** Every one of these holds at once; empty is "None". Never holds [RecipeDiet.NONE]. */
    val diets: Set<RecipeDiet> = emptySet(),
    /** One of [RECIPE_LANGUAGES], or null to follow the app's language. */
    val language: String? = null
) {
    /** The same config with anything out of range brought back in, for a value read from storage
     *  or a hand-edited preference: minutes on the slider's scale (a bound at its end is none),
     *  the lower bound no higher than the upper, "Any" and "None" out of the sets. */
    fun normalized(): RecipeConfig {
        val max = maxMinutes?.let(::snapRecipeMinutes)?.takeIf { it < RECIPE_MINUTES_CEILING }
        val min = minMinutes?.let(::snapRecipeMinutes)?.takeIf { it > RECIPE_MINUTES_FLOOR }
            ?.let { if (max != null) minOf(it, max) else it }
        return copy(
            servings = servings.coerceIn(MIN_RECIPE_SERVINGS, MAX_RECIPE_SERVINGS),
            minMinutes = min,
            maxMinutes = max,
            mealTypes = mealTypes - RecipeMealType.ANY,
            diets = diets - RecipeDiet.NONE,
            language = language?.takeIf { it in RECIPE_LANGUAGES }
        )
    }

    /** The language recipes are written in: [language], or the app's own when it is null. */
    fun resolvedLanguage(appLanguage: String): String = language ?: appRecipeLanguage(appLanguage)
}

/**
 * A stored config, whatever version wrote it. An older one has `mealType` and `diet` (one value
 * each) instead of `mealTypes` and `diets`: they become one-element sets ("any" and "none" the
 * empty set), and its `maxMinutes` (15, 30, 60 or null) is the upper bound. Unreadable JSON gives
 * the defaults. iOS: `UserDefaultsRecipeConfigStore`.
 */
fun recipeConfigFromStoredJson(json: String): RecipeConfig {
    val stored = runCatching { RecipeJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return RecipeConfig()
    fun legacy(key: String, none: String): JsonArray {
        val value = (stored[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return JsonArray(listOfNotNull(value?.takeIf { it != none }?.let(::JsonPrimitive)))
    }
    val upgraded = stored.toMutableMap()
    if ("mealTypes" !in stored) upgraded["mealTypes"] = legacy("mealType", "any")
    if ("diets" !in stored) upgraded["diets"] = legacy("diet", "none")
    upgraded.remove("mealType")
    upgraded.remove("diet")
    val decoded = runCatching { RecipeJson.decodeFromJsonElement(RecipeConfig.serializer(), JsonObject(upgraded)) }.getOrNull()
        // A set this version can't read (a value from a newer one): the rest kept, the sets empty.
        ?: runCatching { RecipeJson.decodeFromJsonElement(RecipeConfig.serializer(), JsonObject(upgraded - "mealTypes" - "diets")) }.getOrNull()
        ?: RecipeConfig()
    return decoded.normalized()
}
