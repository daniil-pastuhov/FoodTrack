package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderChip
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSegmented
import com.foodtracker.android.ui.design.LarderStepper
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.util.recipeDietLabel
import com.foodtracker.android.util.recipeMealLabel
import com.foodtracker.recipe.MAX_RECIPE_SERVINGS
import com.foodtracker.recipe.MIN_RECIPE_SERVINGS
import com.foodtracker.recipe.RECIPE_MAX_MINUTES_OPTIONS
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeDetail
import com.foodtracker.recipe.RecipeDiet
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeMealType
import com.foodtracker.recipe.recipeLanguageEndonym

/*
 * Wording and form controls shared by the Recipe tab, its result and Recipe settings.
 */

fun recipeDifficultyLabel(resources: Resources, difficulty: RecipeDifficulty): String = resources.getString(
    when (difficulty) {
        RecipeDifficulty.EASY -> R.string.recipe_difficulty_easy
        RecipeDifficulty.MEDIUM -> R.string.recipe_difficulty_medium
        RecipeDifficulty.AMBITIOUS -> R.string.recipe_difficulty_ambitious
    }
)

fun recipeDetailLabel(resources: Resources, detail: RecipeDetail): String = resources.getString(
    when (detail) {
        RecipeDetail.BRIEF -> R.string.recipe_detail_brief
        RecipeDetail.STANDARD -> R.string.recipe_detail_standard
        RecipeDetail.STEP_BY_STEP -> R.string.recipe_detail_step_by_step
    }
)

/** "15 min", "1 h", "1 h 10 min". */
fun recipeDurationLabel(resources: Resources, minutes: Int): String = when {
    minutes < 60 -> resources.getString(R.string.recipe_time_minutes, minutes)
    minutes % 60 == 0 -> resources.getString(R.string.recipe_duration_hours, minutes / 60)
    else -> resources.getString(R.string.recipe_duration_hours_minutes, minutes / 60, minutes % 60)
}

/** The time chip's label: "15 min", "30 min", "1 hour" or "Any". */
fun recipeTimeChipLabel(resources: Resources, maxMinutes: Int?): String = when (maxMinutes) {
    null -> resources.getString(R.string.recipe_time_any)
    60 -> resources.getString(R.string.recipe_time_hour)
    else -> resources.getString(R.string.recipe_time_minutes, maxMinutes)
}

/** The language picker's label: a language's own name, or "Same as the app (English)". */
fun recipeLanguageLabel(resources: Resources, language: String?, appLanguage: String): String =
    if (language == null) {
        resources.getString(R.string.recipe_language_same_as_app, recipeLanguageEndonym(appLanguage))
    } else {
        recipeLanguageEndonym(language)
    }

/** "English · Standard detail · Any meal · No diet", under More options. */
fun recipeOptionsSummary(resources: Resources, config: RecipeConfig, appLanguage: String): String = listOf(
    recipeLanguageEndonym(config.language ?: appLanguage),
    resources.getString(
        when (config.detail) {
            RecipeDetail.BRIEF -> R.string.recipe_detail_summary_brief
            RecipeDetail.STANDARD -> R.string.recipe_detail_summary_standard
            RecipeDetail.STEP_BY_STEP -> R.string.recipe_detail_summary_step_by_step
        }
    ),
    if (config.mealType == RecipeMealType.ANY) {
        resources.getString(R.string.recipe_meal_summary_any)
    } else {
        recipeMealLabel(resources, config.mealType)
    },
    if (config.diet == RecipeDiet.NONE) {
        resources.getString(R.string.recipe_diet_summary_none)
    } else {
        recipeDietLabel(resources, config.diet)
    }
).joinToString(" · ")

/** A label over a form control, in the design's `r-label` style. */
@Composable
internal fun RecipeControlLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** A card of form controls: the design's `r-card`, a group with its own padding. */
@Composable
internal fun RecipeFormCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: LarderIcons? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LarderGroup(modifier = modifier) {
        Column(
            modifier = Modifier.padding(LarderSpace.s4),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
        ) {
            if (title != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    icon?.let { LarderIcon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            content()
        }
    }
}

@Composable
internal fun DifficultyControl(value: RecipeDifficulty, onChange: (RecipeDifficulty) -> Unit) {
    val resources = LocalContext.current.resources
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        RecipeControlLabel(stringResource(R.string.recipe_difficulty))
        LarderSegmented(
            options = RecipeDifficulty.entries,
            selected = value,
            onSelect = onChange,
            label = { recipeDifficultyLabel(resources, it) },
            fill = true
        )
    }
}

@Composable
internal fun DetailControl(value: RecipeDetail, onChange: (RecipeDetail) -> Unit) {
    val resources = LocalContext.current.resources
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        RecipeControlLabel(stringResource(R.string.recipe_detail))
        LarderSegmented(
            options = RecipeDetail.entries,
            selected = value,
            onSelect = onChange,
            label = { recipeDetailLabel(resources, it) },
            fill = true
        )
    }
}

@Composable
internal fun ServingsControl(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RecipeControlLabel(stringResource(R.string.recipe_servings), Modifier.weight(1f))
        LarderStepper(
            value = value,
            onValueChange = onChange,
            min = MIN_RECIPE_SERVINGS,
            max = MAX_RECIPE_SERVINGS,
            decreaseDescription = stringResource(R.string.recipe_servings_fewer),
            increaseDescription = stringResource(R.string.recipe_servings_more)
        )
    }
}

@Composable
internal fun TimeControl(value: Int?, onChange: (Int?) -> Unit) {
    val resources = LocalContext.current.resources
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        RecipeControlLabel(stringResource(R.string.recipe_time))
        ChipRow(options = RECIPE_MAX_MINUTES_OPTIONS + listOf<Int?>(null), selected = value, onSelect = onChange) {
            recipeTimeChipLabel(resources, it)
        }
    }
}

@Composable
internal fun <T> ChipRow(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        options.forEach { option ->
            LarderChip(label = label(option), selected = option == selected, onClick = { onSelect(option) })
        }
    }
}
