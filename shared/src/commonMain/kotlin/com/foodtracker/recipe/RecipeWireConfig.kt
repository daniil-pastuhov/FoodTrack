package com.foodtracker.recipe

import kotlinx.serialization.Serializable

/**
 * `generateRecipe`'s `config` (functions/CONTRACT.md "RecipeConfig"), in one of two shapes:
 *
 * - legacy: `maxMinutes` 15 | 30 | 60 | null, one `mealType`, one `diet`, and the new fields null;
 * - multi-choice, only to a server that says `acceptsMultiChoiceConfig`: `minMinutes` and
 *   `maxMinutes` on the slider's scale, `mealTypes` and `diets` as lists, and `mealType` and
 *   `diet` null, so a server from before them (which requires both) refuses the request rather
 *   than reading only part of it.
 *
 * Nulls are written out ([RecipeJson]), and a request kept by an older version (whose config was a
 * [RecipeConfig] with `mealType` and `diet`) reads back as the legacy shape and is sent again
 * exactly as it was. iOS: `WireConfig` in `RecipeWire.swift`.
 */
@Serializable
data class WireConfig(
    val difficulty: RecipeDifficulty = RecipeDifficulty.EASY,
    val detail: RecipeDetail = RecipeDetail.STANDARD,
    val servings: Int = DEFAULT_RECIPE_SERVINGS,
    val maxMinutes: Int? = null,
    val mealType: RecipeMealType? = null,
    val diet: RecipeDiet? = null,
    val minMinutes: Int? = null,
    val mealTypes: List<RecipeMealType>? = null,
    val diets: List<RecipeDiet>? = null,
    /** A code from [RECIPE_LANGUAGES], as the server requires one. */
    val language: String? = null
) {
    /** Whether this is the multi-choice shape. */
    val isMultiChoice: Boolean get() = mealTypes != null || diets != null
}

/**
 * The config to send for [config] written in [language]: the legacy shape whenever it says
 * exactly the same, the multi-choice shape when it doesn't and the server [takesMultiChoice], and
 * otherwise the closest legacy values ([legacyWireConfig]); null when even those can't keep every
 * selected diet, so the server route is refused rather than sent without one.
 */
fun wireConfigFor(config: RecipeConfig, language: String, takesMultiChoice: Boolean): WireConfig? {
    val c = config.normalized()
    val exact = c.minMinutes == null && (c.maxMinutes == null || c.maxMinutes in RECIPE_MAX_MINUTES_OPTIONS) &&
        c.mealTypes.size <= 1 && c.diets.size <= 1
    return when {
        exact || !takesMultiChoice -> legacyWireConfig(c, language)
        else -> WireConfig(
            difficulty = c.difficulty,
            detail = c.detail,
            servings = c.servings,
            maxMinutes = c.maxMinutes,
            minMinutes = c.minMinutes,
            mealTypes = orderedMeals(c.mealTypes),
            diets = RECIPE_DIET_CHOICES.filter { it in c.diets },
            language = language
        )
    }
}

/**
 * The closest legacy config, for a server that doesn't take the multi-choice shape:
 *
 * - time: the lower bound (a guide) is dropped; the upper bound becomes the largest of 15, 30
 *   and 60 not above it (10 becomes 15, the nearest there is); none stays none;
 * - meal: one selected meal is sent as it is; several (or none) become "any";
 * - diet: none or one as it is; several become the one legacy diet that forbids everything they
 *   forbid together and the least besides: the strictest selected one when it implies the rest
 *   ([promptDiets]: pescatarian + vegetarian is vegetarian, vegan + dairy-free is vegan),
 *   otherwise vegan (vegetarian or pescatarian with dairy-free: vegan also leaves out eggs and
 *   honey, never something they allow). Gluten-free with any other diet can't be said with one
 *   legacy diet: null, and the server route refuses the request ([RecipeEvent.DietsNeedServerUpdate]).
 */
fun legacyWireConfig(config: RecipeConfig, language: String): WireConfig? {
    val c = config.normalized()
    val diets = promptDiets(c.diets)
    val diet = when {
        diets.isEmpty() -> RecipeDiet.NONE
        diets.size == 1 -> diets.single()
        RecipeDiet.GLUTEN_FREE in diets -> return null
        else -> RecipeDiet.VEGAN
    }
    val max = c.maxMinutes?.let { limit -> RECIPE_MAX_MINUTES_OPTIONS.lastOrNull { it <= limit } ?: RECIPE_MAX_MINUTES_OPTIONS.first() }
    return WireConfig(
        difficulty = c.difficulty,
        detail = c.detail,
        servings = c.servings,
        maxMinutes = max,
        mealType = c.mealTypes.singleOrNull() ?: RecipeMealType.ANY,
        diet = diet,
        language = language
    )
}
