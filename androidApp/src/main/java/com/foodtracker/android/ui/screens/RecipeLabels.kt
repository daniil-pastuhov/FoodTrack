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
import com.foodtracker.recipe.MenuItemsHint
import com.foodtracker.recipe.MIN_RECIPE_SERVINGS
import com.foodtracker.recipe.RECIPE_DIET_CHOICES
import com.foodtracker.recipe.RECIPE_MINUTES_CEILING
import com.foodtracker.recipe.RECIPE_MINUTES_FLOOR
import com.foodtracker.recipe.RECIPE_MINUTES_STEP
import com.foodtracker.recipe.orderedMeals
import com.foodtracker.recipe.snapRecipeMinutes
import com.foodtracker.recipe.toggledChoice
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.roundToInt
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeDetail
import com.foodtracker.recipe.RecipeDiet
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeMealType
import com.foodtracker.recipe.recipeLanguageEndonym

/*
 * Wording and form controls shared by the Recipe tab, its result and Recipe settings.
 */

/**
 * Text from the person or the model (an item or allergen name) set inside one of our lines,
 * wrapped in a first-strong isolate (U+2068 ... U+2069): a Hebrew or Arabic name keeps its own
 * direction without reordering the sentence around it. iOS uses the same isolate
 * (`L10n.isolated`), since SwiftUI has no BidiFormatter.
 */
internal fun isolated(embedded: String): String = "⁨$embedded⁩"

/** Names joined with ", ", each isolated. */
internal fun isolatedList(names: List<String>): String = names.joinToString(", ") { isolated(it) }

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

/** The language picker's label: a language's own name, or "Same as the app (English)". */
fun recipeLanguageLabel(resources: Resources, language: String?, appLanguage: String): String =
    if (language == null) {
        resources.getString(R.string.recipe_language_same_as_app, recipeLanguageEndonym(appLanguage))
    } else {
        recipeLanguageEndonym(language)
    }

/** The menu form's "few items" hint ([com.foodtracker.recipe.menuItemsHint]): "Only 2 items for
 *  12 dishes. Most of this menu will need shopping." iOS: `L10n.recipeMenuFewItems`. */
fun recipeMenuFewItems(resources: Resources, hint: MenuItemsHint): String =
    resources.getQuantityString(R.plurals.recipe_menu_few_items, hint.items, hint.items, hint.dishes)

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
    if (config.mealTypes.isEmpty()) {
        resources.getString(R.string.recipe_meal_summary_any)
    } else {
        orderedMeals(config.mealTypes).joinToString(", ") { recipeMealLabel(resources, it) }
    },
    if (config.diets.isEmpty()) {
        resources.getString(R.string.recipe_diet_summary_none)
    } else {
        RECIPE_DIET_CHOICES.filter { it in config.diets }.joinToString(", ") { recipeDietLabel(resources, it) }
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

/**
 * Total cooking time as a range slider with two handles, from [RECIPE_MINUTES_FLOOR] to
 * [RECIPE_MINUTES_CEILING] in steps of [RECIPE_MINUTES_STEP]. A handle at its end is no bound
 * (null), so both at the ends is "Any". The current range is written beside the label, and each
 * handle tells TalkBack which one it is and its value; TalkBack's volume-key adjust (and a
 * keyboard's arrows) moves a handle one step, so dragging is never needed. Material's RangeSlider
 * mirrors itself in right-to-left layouts.
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun TimeControl(minMinutes: Int?, maxMinutes: Int?, onChange: (min: Int?, max: Int?) -> Unit) {
    val resources = LocalContext.current.resources
    val floor = RECIPE_MINUTES_FLOOR.toFloat()
    val ceiling = RECIPE_MINUTES_CEILING.toFloat()
    // Follows the stored value, and the handles while they move; stored when they are let go.
    var range by remember(minMinutes, maxMinutes) {
        mutableStateOf((minMinutes?.toFloat() ?: floor)..(maxMinutes?.toFloat() ?: ceiling))
    }
    fun bounds(r: ClosedFloatingPointRange<Float>): Pair<Int?, Int?> {
        val low = snapRecipeMinutes(r.start.roundToInt())
        val high = snapRecipeMinutes(r.endInclusive.roundToInt())
        return low.takeIf { it > RECIPE_MINUTES_FLOOR } to high.takeIf { it < RECIPE_MINUTES_CEILING }
    }
    val (low, high) = bounds(range)
    val shortest = stringResource(R.string.recipe_time_shortest)
    val longest = stringResource(R.string.recipe_time_longest)
    val lowSpoken = low?.let { resources.getQuantityString(R.plurals.recipe_time_minutes_spoken, it, it) }
        ?: stringResource(R.string.recipe_time_no_minimum)
    val highSpoken = high?.let { resources.getQuantityString(R.plurals.recipe_time_minutes_spoken, it, it) }
        ?: stringResource(R.string.recipe_time_no_limit)
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecipeControlLabel(stringResource(R.string.recipe_time), Modifier.weight(1f))
            Text(
                text = recipeTimeRangeLabel(resources, low, high),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        val colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
            inactiveTickColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
        val startInteraction = remember { MutableInteractionSource() }
        val endInteraction = remember { MutableInteractionSource() }
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = {
                val (min, max) = bounds(range)
                if (min != minMinutes || max != maxMinutes) onChange(min, max)
            },
            valueRange = floor..ceiling,
            steps = (RECIPE_MINUTES_CEILING - RECIPE_MINUTES_FLOOR) / RECIPE_MINUTES_STEP - 1,
            colors = colors,
            startInteractionSource = startInteraction,
            endInteractionSource = endInteraction,
            startThumb = {
                SliderDefaults.Thumb(
                    interactionSource = startInteraction,
                    colors = colors,
                    modifier = Modifier.semantics { contentDescription = "$shortest: $lowSpoken" }
                )
            },
            endThumb = {
                SliderDefaults.Thumb(
                    interactionSource = endInteraction,
                    colors = colors,
                    modifier = Modifier.semantics { contentDescription = "$longest: $highSpoken" }
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (low != null) {
            Text(
                text = stringResource(R.string.recipe_time_guide_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The time range as text: "Any", "Up to 30 min", "At least 40 min", "30 min", "20–45 min". */
fun recipeTimeRangeLabel(resources: Resources, minMinutes: Int?, maxMinutes: Int?): String = when {
    minMinutes == null && maxMinutes == null -> resources.getString(R.string.recipe_time_any)
    minMinutes == null -> resources.getString(R.string.recipe_time_up_to, maxMinutes)
    maxMinutes == null -> resources.getString(R.string.recipe_time_at_least, minMinutes)
    minMinutes == maxMinutes -> resources.getString(R.string.recipe_time_minutes, minMinutes)
    else -> resources.getString(R.string.recipe_time_range, minMinutes, maxMinutes)
}

/**
 * A multi-choice chip row whose first chip, [emptyLabel] ("Any", "None"), is the empty selection:
 * it clears the others, another chip toggles itself and turns the first one off, and turning the
 * last one off turns the first one back on ([toggledChoice]).
 */
@Composable
internal fun <T> MultiChipRow(
    options: List<T>,
    selected: Set<T>,
    emptyLabel: String,
    onChange: (Set<T>) -> Unit,
    label: (T) -> String
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        LarderChip(label = emptyLabel, selected = selected.isEmpty(), onClick = { onChange(selected.toggledChoice(null)) })
        options.forEach { option ->
            LarderChip(label = label(option), selected = option in selected, onClick = { onChange(selected.toggledChoice(option)) })
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
