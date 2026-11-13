package com.foodtracker.recipe

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * A written recipe and a menu, the same shape on Android, iOS and the server (camelCase when
 * serialised). iOS mirrors these by hand, so a change here is ported there and to `functions/`.
 */

/** Who wrote a recipe. Local to the apps: the server never sends it, the app sets it. */
@Serializable
enum class RecipeSource {
    @SerialName("server") SERVER,
    @SerialName("device") DEVICE
}

@Serializable
data class RecipeIngredient(
    /** "4 slices", "100 g", or empty for "salt, pepper" and the like. */
    val amount: String = "",
    val name: String,
    /** True for something the person has, ticked or not; the rest is to have or to buy. */
    val fromPantry: Boolean = false
)

@Serializable
data class Recipe(
    val title: String,
    val summary: String = "",
    val minutes: Int = 0,
    val servings: Int = DEFAULT_RECIPE_SERVINGS,
    val difficulty: RecipeDifficulty = RecipeDifficulty.EASY,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val tip: String? = null,
    val source: RecipeSource = RecipeSource.DEVICE
)

@Serializable
enum class MenuCourseKind {
    @SerialName("starter") STARTER,
    @SerialName("main") MAIN,
    @SerialName("dessert") DESSERT
}

@Serializable
data class MenuCourse(
    val course: MenuCourseKind,
    val recipe: Recipe
)

@Serializable
data class MenuDay(
    val date: LocalDate,
    /** Names of the dated items this day uses, so the order of the days makes sense. */
    val uses: List<String> = emptyList(),
    val courses: List<MenuCourse> = emptyList()
)

@Serializable
data class Menu(val days: List<MenuDay>)

@Serializable
enum class SavedRecipeKind {
    @SerialName("recipe") RECIPE,
    @SerialName("menu") MENU
}

/** A recipe or a menu kept on this phone. Exactly one of [recipe] and [menu] is set, as [kind]
 *  says. */
data class SavedRecipe(
    val id: String,
    val kind: SavedRecipeKind,
    val title: String,
    val createdAtEpochMillis: Long,
    val source: RecipeSource,
    val recipe: Recipe? = null,
    val menu: Menu? = null
) {
    /** How many dishes this holds: one for a recipe, every course of every day for a menu. */
    val dishCount: Int get() = menu?.days?.sumOf { it.courses.size } ?: 1

    companion object {
        fun of(id: String, recipe: Recipe, createdAtEpochMillis: Long) = SavedRecipe(
            id = id,
            kind = SavedRecipeKind.RECIPE,
            title = recipe.title,
            createdAtEpochMillis = createdAtEpochMillis,
            source = recipe.source,
            recipe = recipe
        )

        fun of(id: String, title: String, menu: Menu, createdAtEpochMillis: Long) = SavedRecipe(
            id = id,
            kind = SavedRecipeKind.MENU,
            title = title,
            createdAtEpochMillis = createdAtEpochMillis,
            source = RecipeSource.SERVER,
            menu = menu
        )
    }
}

/** The JSON a saved recipe's payload and a server reply use. Unknown keys are skipped, so a
 *  newer server or an older row still reads. Nulls are written out, because a missing
 *  `maxMinutes` would read back as the 30-minute default rather than "any time". */
val RecipeJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/** A saved row's payload: the recipe or the menu, as JSON. */
fun SavedRecipe.payloadJson(): String = when (kind) {
    SavedRecipeKind.RECIPE -> RecipeJson.encodeToString(Recipe.serializer(), requireNotNull(recipe))
    SavedRecipeKind.MENU -> RecipeJson.encodeToString(Menu.serializer(), requireNotNull(menu))
}

/** Reads a saved row back, or null when its payload can't be read, so one bad row can't take
 *  the whole list down. */
fun savedRecipeFromPayload(
    id: String,
    kind: SavedRecipeKind,
    title: String,
    createdAtEpochMillis: Long,
    source: RecipeSource,
    payload: String
): SavedRecipe? = runCatching {
    when (kind) {
        SavedRecipeKind.RECIPE -> SavedRecipe(
            id, kind, title, createdAtEpochMillis, source,
            recipe = RecipeJson.decodeFromString(Recipe.serializer(), payload).copy(source = source)
        )
        SavedRecipeKind.MENU -> SavedRecipe(
            id, kind, title, createdAtEpochMillis, source,
            menu = RecipeJson.decodeFromString(Menu.serializer(), payload)
        )
    }
}.getOrNull()
