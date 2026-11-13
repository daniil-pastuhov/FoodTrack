package com.foodtracker.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import com.foodtracker.android.R
import com.foodtracker.model.PantryItem
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeDetail
import com.foodtracker.recipe.RecipeDiet
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeMealType
import com.foodtracker.recipe.RecipePromptStrings
import com.foodtracker.util.DateUtils
import java.util.Locale
import com.foodtracker.recipe.buildRecipePrompt as buildSharedRecipePrompt

/** Reads the prompt's wording from this device's string catalog, so `shared`'s builder can stay
 *  free of any platform string lookup. */
fun recipePromptStrings(context: Context): RecipePromptStrings {
    val resources = context.resources
    return RecipePromptStrings(
        intro = context.getString(R.string.recipe_prompt_intro),
        itemFormat = { name, expiry ->
            context.getString(R.string.recipe_prompt_item_format, name, expiry)
        },
        otherItemsIntro = context.getString(R.string.recipe_prompt_other_items_intro),
        otherItemsOmitted = { count ->
            context.getString(R.string.recipe_prompt_other_items_omitted, count)
        },
        daysRemainingLabel = { days -> daysRemainingLabel(context, days) },
        preferencesIntro = context.getString(R.string.recipe_prompt_preferences_intro),
        difficulty = { difficulty ->
            context.getString(
                when (difficulty) {
                    RecipeDifficulty.EASY -> R.string.recipe_prompt_difficulty_easy
                    RecipeDifficulty.MEDIUM -> R.string.recipe_prompt_difficulty_medium
                    RecipeDifficulty.AMBITIOUS -> R.string.recipe_prompt_difficulty_ambitious
                }
            )
        },
        detail = { detail ->
            context.getString(
                when (detail) {
                    RecipeDetail.BRIEF -> R.string.recipe_prompt_detail_brief
                    RecipeDetail.STANDARD -> R.string.recipe_prompt_detail_standard
                    RecipeDetail.STEP_BY_STEP -> R.string.recipe_prompt_detail_step_by_step
                }
            )
        },
        servings = { context.getString(R.string.recipe_prompt_servings, it) },
        maxMinutes = { context.getString(R.string.recipe_prompt_max_minutes, it) },
        mealType = { context.getString(R.string.recipe_prompt_meal, recipeMealLabel(resources, it)) },
        diet = { context.getString(R.string.recipe_prompt_diet, recipeDietLabel(resources, it)) },
        allergens = { context.getString(R.string.recipe_prompt_allergens, it) },
        language = { code -> context.getString(R.string.recipe_prompt_language, languageDisplayName(code)) }
    )
}

/** [code]'s name in the app's own language ("German", "немецкий"), as the prompt's sentence
 *  needs it. */
fun languageDisplayName(code: String, locale: Locale = Locale.getDefault()): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).ifEmpty { code }

fun recipeMealLabel(resources: Resources, meal: RecipeMealType): String = resources.getString(
    when (meal) {
        RecipeMealType.ANY -> R.string.recipe_meal_any
        RecipeMealType.BREAKFAST -> R.string.recipe_meal_breakfast
        RecipeMealType.LUNCH -> R.string.recipe_meal_lunch
        RecipeMealType.DINNER -> R.string.recipe_meal_dinner
        RecipeMealType.SNACK -> R.string.recipe_meal_snack
        RecipeMealType.DESSERT -> R.string.recipe_meal_dessert
    }
)

fun recipeDietLabel(resources: Resources, diet: RecipeDiet): String = resources.getString(
    when (diet) {
        RecipeDiet.NONE -> R.string.recipe_diet_none
        RecipeDiet.VEGETARIAN -> R.string.recipe_diet_vegetarian
        RecipeDiet.VEGAN -> R.string.recipe_diet_vegan
        RecipeDiet.PESCATARIAN -> R.string.recipe_diet_pescatarian
        RecipeDiet.GLUTEN_FREE -> R.string.recipe_diet_gluten_free
        RecipeDiet.DAIRY_FREE -> R.string.recipe_diet_dairy_free
    }
)

/**
 * Builds a device-language prompt asking an AI assistant for recipe ideas using [items], keeping
 * to the Recipe tab's [config] and leaving out [allergens].
 *
 * The prompt itself is assembled by `shared`'s [buildSharedRecipePrompt]; this only supplies the
 * localized wording.
 */
fun buildRecipePrompt(
    context: Context,
    items: List<PantryItem>,
    config: RecipeConfig,
    allergens: Collection<String>
): String = buildSharedRecipePrompt(
    strings = recipePromptStrings(context),
    today = DateUtils.today(),
    items = items,
    config = config,
    allergens = allergens
)

/** Hands [prompt] to the system share sheet, for an AI app the person already uses. */
fun shareRecipePrompt(context: Context, prompt: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, prompt)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}

/** Puts [prompt] on the clipboard. */
fun copyRecipePrompt(context: Context, prompt: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.recipe_prompt_clip_label), prompt))
}
