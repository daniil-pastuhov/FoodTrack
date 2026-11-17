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
    val fromPantry: Boolean = false,
    /** Server recipes: the index of this ingredient's item in the request's `ingredients`, when
     *  it is one (functions/CONTRACT.md "Recipe"). Null in answers from older servers, saved
     *  rows from before it, and recipes the phone wrote. */
    val pantryIndex: Int? = null,
    /** The same in the request's `optionalIngredients`, as sent. */
    val optionalIndex: Int? = null,
    /** Server recipes: the server found this non-pantry ingredient to be only a staple (water,
     *  oil, salt, pepper, sugar, spices). False when it didn't, and wherever nobody said. */
    val staple: Boolean = false,
    /** Server recipes: the index of this ingredient in the request's `staples`, as sent, when it
     *  is one of the person's staples (functions/CONTRACT.md "Recipe"). Never from the pantry. */
    val stapleIndex: Int? = null,
    /** Local only: the person's staple this ingredient is, for the "Your staple" chip and for
     *  leaving it out of "Add to shopping list". Set by the app when the recipe arrives
     *  ([withStapleNames], or by name for the phone's own recipes), overwriting anything a reply
     *  carried; kept in a saved recipe's payload. */
    val stapleName: String? = null
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
    val pastDate: List<PastDateItem> = emptyList(),
    /** Local only: the generation's request id (server recipes saved since pictures and reports
     *  came, 2026-11): what "Report this recipe" names. Null in rows saved before it, which then
     *  offer no report, and in recipes the phone wrote. Set by the app, never read from a reply. */
    val requestId: String? = null,
    /** Local only: the dish's picture kept on this device beside the saved row ([RecipePictureStore]),
     *  null without one. Set by the app when a reply with pictures is saved, never read from a reply. */
    val picture: RecipePicture? = null,
    /** Local only: the step pictures kept with it, in step order, each tied to its step. */
    val stepPictures: List<RecipeStepPicture> = emptyList()
) {
    /** The same recipe with nothing local a reply or a model's text could have carried: no request
     *  id and no pictures. Applied to everything that arrives before the app sets its own. */
    fun withoutLocalFields(): Recipe = if (requestId == null && picture == null && stepPictures.isEmpty()) this else copy(requestId = null, picture = null, stepPictures = emptyList())

    /** Whether it has any picture kept with it: the report sheet's picture reason. */
    val hasPictures: Boolean get() = picture != null || stepPictures.isNotEmpty()
}

/** A picture kept on this device with a saved recipe: [file] is its name in that saved recipe's
 *  own folder ([RECIPE_PICTURE_DISH] or [recipeStepPictureFile]); [width] and [height] in pixels,
 *  as the server said. */
@Serializable
data class RecipePicture(val file: String, val width: Int, val height: Int)

/** A step's picture: [step] is the 0-based index into [Recipe.steps]. */
@Serializable
data class RecipeStepPicture(val step: Int, val picture: RecipePicture)

/** The dish picture's file name in a saved recipe's folder. */
const val RECIPE_PICTURE_DISH = "dish.jpg"

/** Step [step]'s picture file name in a saved recipe's folder. */
fun recipeStepPictureFile(step: Int): String = "step-$step.jpg"

/** Whether [name] is one this app writes ([RECIPE_PICTURE_DISH], [recipeStepPictureFile]): only
 *  such a name is ever opened, so a saved payload can't point outside its folder. */
fun isRecipePictureFile(name: String): Boolean = name == RECIPE_PICTURE_DISH || Regex("step-[0-9]{1,2}\\.jpg").matches(name)

/** Each ingredient's [RecipeIngredient.stapleName]: the staple at its [RecipeIngredient.stapleIndex]
 *  in [staples] (the request's `staples`, as sent), null otherwise. iOS: `RecipeWire.withStapleNames`. */
fun Recipe.withStapleNames(staples: List<String>): Recipe =
    copy(ingredients = ingredients.map { it.copy(stapleName = it.stapleIndex?.let(staples::getOrNull)) })

/** [Recipe.withStapleNames] for every dish of the menu. */
fun Menu.withStapleNames(staples: List<String>): Menu =
    copy(days = days.map { day -> day.copy(courses = day.courses.map { it.copy(recipe = it.recipe.withStapleNames(staples)) }) })

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
    val pastDate: List<PastDateItem> = emptyList(),
    /** Local only: the generation's request id, as for [Recipe.requestId]. */
    val requestId: String? = null
)

/** The [items] (as sent, `expiresOn` `YYYY-MM-DD`) that are past their date on [today], in the
 *  order given. An item without a date, or with one that can't be read, is not. */
fun pastDateOf(items: List<WireIngredient>, today: LocalDate): List<PastDateItem> = items.mapNotNull { item ->
    val date = item.expiresOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
    val over = today.toEpochDays() - date.toEpochDays()
    if (over > 0) PastDateItem(item.name, over.toInt()) else null
}

/** Whether this dish says which request item each pantry ingredient is ([RecipeIngredient.pantryIndex],
 *  [RecipeIngredient.optionalIndex]): answers from servers before those fields don't. */
val Recipe.hasItemIndices: Boolean get() = ingredients.any { it.pantryIndex != null || it.optionalIndex != null }

/**
 * The items past their date on [today] that this server dish uses, exactly, by its ingredients'
 * indices into the request's [ticked] and [optional] items (in the request's order, each once).
 * An index outside the lists is ignored. iOS: `RecipeWire.pastDateByIndex`.
 */
fun Recipe.pastDateByIndex(ticked: List<WireIngredient>, optional: List<WireIngredient>, today: LocalDate): List<PastDateItem> {
    val tickedUsed = ingredients.mapNotNullTo(HashSet()) { it.pantryIndex }
    val optionalUsed = ingredients.mapNotNullTo(HashSet()) { it.optionalIndex }
    val used = ticked.filterIndexed { i, _ -> i in tickedUsed } + optional.filterIndexed { i, _ -> i in optionalUsed }
    return pastDateOf(used, today).distinctBy { it.name.lowercase() }
}

/**
 * A server menu whose dishes carry item indices, with exact past-date warnings: each course gets
 * the past-date items it uses itself ([pastDateByIndex]), and the menu every one any course uses.
 * Null when no dish carries indices (an older server): then [withPastDate] works it out from the
 * days' `uses`.
 */
fun Menu.withPastDateByIndex(ticked: List<WireIngredient>, optional: List<WireIngredient>, today: LocalDate): Menu? {
    if (days.none { day -> day.courses.any { it.recipe.hasItemIndices } }) return null
    val days = days.map { day ->
        day.copy(courses = day.courses.map { it.copy(recipe = it.recipe.copy(pastDate = it.recipe.pastDateByIndex(ticked, optional, today))) })
    }
    val all = days.flatMap { day -> day.courses.flatMap { it.recipe.pastDate } }.distinctBy { it.name.lowercase() }
    return copy(days = days, pastDate = all)
}

/**
 * A server menu with its past-date warnings, for answers without item indices: each day's courses get the past-date items among
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

    /** The generation's request id, for a report; null in rows saved before it was kept. */
    val requestId: String? get() = recipe?.requestId ?: menu?.requestId

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
