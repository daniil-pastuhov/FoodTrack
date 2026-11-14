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
    val source: RecipeSource = RecipeSource.DEVICE,
    /** The items this dish was written from that were past their date when it was written, and
     *  by how many days: shown in red above the ingredients and in its shared text. Local to the
     *  apps, worked out from the request ([pastDateOf]), never from the model's text; the server
     *  never sends it, and a row saved before it existed reads as none. */
    val pastDate: List<PastDateItem> = emptyList()
)

/** An item past its date, as it was when a recipe was written: [daysOver] days after its date. */
@Serializable
data class PastDateItem(val name: String, val daysOver: Int)

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
data class Menu(
    val days: List<MenuDay>,
    /** Every item past its date the menu was written from (see [Recipe.pastDate]): the warning
     *  under the menu's title. Each course's own recipe carries its day's share. */
    val pastDate: List<PastDateItem> = emptyList()
)

/** The [items] (as sent, `expiresOn` `YYYY-MM-DD`) that are past their date on [today], in the
 *  order given. An item without a date, or with one that can't be read, is not. */
fun pastDateOf(items: List<WireIngredient>, today: LocalDate): List<PastDateItem> = items.mapNotNull { item ->
    val date = item.expiresOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
    val over = today.toEpochDays() - date.toEpochDays()
    if (over > 0) PastDateItem(item.name, over.toInt()) else null
}

/**
 * A server menu with its past-date warnings: each day's courses get the past-date items among
 * that day's `uses` (the server's own reading of which items the day uses, by the names sent),
 * and the menu all of [ticked] plus the [optional] ones a day uses. The ticked ones are listed for
 * the whole menu even when no day names them, since they were asked to be used.
 */
fun Menu.withPastDate(ticked: List<PastDateItem>, optional: List<PastDateItem>): Menu {
    val all = (ticked + optional).distinctBy { it.name.lowercase() }
    val days = days.map { day ->
        val used = day.uses.mapTo(HashSet()) { it.lowercase() }
        val items = all.filter { it.name.lowercase() in used }
        day.copy(courses = day.courses.map { it.copy(recipe = it.recipe.copy(pastDate = items)) })
    }
    val usedAnywhere = days.flatMap { day -> day.courses.flatMap { it.recipe.pastDate } }
    return copy(days = days, pastDate = (ticked + usedAnywhere).distinctBy { it.name.lowercase() })
}

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
