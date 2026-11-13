package com.foodtracker.android.ui.screens

import android.content.Intent
import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeDifficulty
import com.foodtracker.recipe.RecipeIngredient
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.RecipeStateHolder

/** A recipe as plain text, for Share: what another app or a message can hold. */
fun recipeShareText(resources: Resources, recipe: Recipe): String = buildString {
    append(recipe.title)
    if (recipe.summary.isNotBlank()) append("\n\n").append(recipe.summary)
    val facts = listOfNotNull(
        recipe.minutes.takeIf { it > 0 }?.let { recipeDurationLabel(resources, it) },
        resources.getQuantityString(R.plurals.recipe_servings_count, recipe.servings, recipe.servings),
        recipeDifficultyLabel(resources, recipe.difficulty)
    )
    append("\n").append(facts.joinToString(" · "))
    append("\n\n").append(resources.getString(R.string.recipe_ingredients)).append("\n")
    append(recipe.ingredients.joinToString("\n") { "- " + listOf(it.amount, it.name).filter(String::isNotBlank).joinToString(" ") })
    append("\n\n").append(resources.getString(R.string.recipe_method)).append("\n")
    append(recipe.steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n"))
    recipe.tip?.let { append("\n\n").append(resources.getString(R.string.recipe_tip)).append(": ").append(it) }
}

/** A saved recipe, read on a counter at arm's length: large type, numbered steps. Saved already,
 *  so there is no Save button; Delete is here as the visible twin of the saved list's swipe. */
@Composable
fun RecipeResultScreen(
    stateHolder: RecipeStateHolder,
    savedId: String,
    onBack: () -> Unit,
    onDelete: (String) -> Unit
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val recipe = state.saved.firstOrNull { it.id == savedId }?.recipe
    Scaffold(
        topBar = {
            LarderTopBar(
                title = stringResource(R.string.recipe_result_title),
                onBack = onBack,
                actions = {
                    if (recipe != null) {
                        LarderIconButton(
                            icon = LarderIcons.Trash2,
                            contentDescription = stringResource(R.string.delete),
                            onClick = { onDelete(savedId) }
                        )
                        LarderIconButton(
                            icon = LarderIcons.Share2,
                            contentDescription = stringResource(R.string.recipe_share_recipe),
                            onClick = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, recipeShareText(context.resources, recipe))
                                }
                                context.startActivity(Intent.createChooser(send, null))
                            }
                        )
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (recipe == null) {
                // Still loading, or deleted from another screen.
                if (state.savedLoaded) EmptyState(title = stringResource(R.string.recipe_not_found), art = null)
            } else {
                RecipeDocument(recipe)
            }
        }
    }
}

@Composable
internal fun RecipeDocument(recipe: Recipe) {
    val resources = LocalContext.current.resources
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LarderSpace.s4),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = LarderSpace.s2, bottom = LarderSpace.s8)) {
            Text(
                text = recipe.title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (recipe.summary.isNotBlank()) {
                Text(
                    text = recipe.summary,
                    modifier = Modifier.padding(top = LarderSpace.s2),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                modifier = Modifier.padding(top = LarderSpace.s3),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
            ) {
                if (recipe.minutes > 0) RecipeFact(LarderIcons.Timer, recipeDurationLabel(resources, recipe.minutes))
                RecipeFact(
                    LarderIcons.Users,
                    pluralStringResource(R.plurals.recipe_servings_count, recipe.servings, recipe.servings)
                )
                RecipeFact(LarderIcons.Gauge, recipeDifficultyLabel(resources, recipe.difficulty))
            }

            SectionHeader(stringResource(R.string.recipe_ingredients))
            LarderGroup {
                recipe.ingredients.forEachIndexed { index, ingredient ->
                    if (index > 0) LarderDivider()
                    IngredientRow(ingredient)
                }
            }

            SectionHeader(stringResource(R.string.recipe_method))
            Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)) {
                recipe.steps.forEachIndexed { index, step ->
                    Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = step,
                            modifier = Modifier.weight(1f).padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            recipe.tip?.let { tip ->
                LarderCallout(
                    kind = LarderCalloutKind.Info,
                    icon = LarderIcons.Lightbulb,
                    title = stringResource(R.string.recipe_tip),
                    message = tip,
                    modifier = Modifier.padding(top = LarderSpace.s4)
                )
            }

            Row(
                modifier = Modifier.padding(top = LarderSpace.s6),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val fromPhone = recipe.source == RecipeSource.DEVICE
                LarderIcon(
                    icon = if (fromPhone) LarderIcons.Smartphone else LarderIcons.Cloud,
                    contentDescription = null,
                    size = 16.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(if (fromPhone) R.string.recipe_source_phone else R.string.recipe_source_server),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One of the facts under the title: time, servings, difficulty. */
@Composable
private fun RecipeFact(icon: LarderIcons, text: String) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(LarderRadius.sm))
            .padding(horizontal = LarderSpace.s2, vertical = LarderSpace.s1),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(icon, contentDescription = null, size = 16.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Amount in a fixed column so the names line up; items from the pantry are marked. */
@Composable
private fun IngredientRow(ingredient: RecipeIngredient) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = ingredient.amount,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = ingredient.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (ingredient.fromPantry) {
            val colors = LarderTheme.colors
            Row(
                modifier = Modifier
                    .background(colors.successContainer, RoundedCornerShape(LarderRadius.sm))
                    .padding(start = LarderSpace.s1, end = LarderSpace.s2, top = 1.dp, bottom = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LarderIcon(LarderIcons.Check, contentDescription = null, size = 14.dp, tint = colors.onSuccessContainer)
                Text(
                    text = stringResource(R.string.recipe_in_pantry),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSuccessContainer,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

private val previewRecipe = Recipe(
    title = "Spinach and yoghurt eggs on sourdough toast",
    summary = "A warm, quick plate that uses the bread, the spinach and one of the yoghurts today.",
    minutes = 15,
    servings = 2,
    difficulty = RecipeDifficulty.EASY,
    ingredients = listOf(
        RecipeIngredient("4 slices", "Sourdough bread", fromPantry = true),
        RecipeIngredient("100 g", "Baby spinach", fromPantry = true),
        RecipeIngredient("1 clove", "Garlic"),
        RecipeIngredient("", "Salt, pepper, chilli flakes")
    ),
    steps = listOf(
        "Stir the grated garlic and a pinch of salt into the yoghurt.",
        "Toast the bread. Day-old sourdough toasts better than fresh.",
        "Spread the yoghurt on the toast, pile on the spinach and top with the eggs."
    ),
    tip = "Check the bread before using it: one day past a best-before date is usually fine, but not if there is any mould.",
    source = RecipeSource.DEVICE
)

@PreviewLightDark
@Composable
internal fun RecipeDocumentPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) { RecipeDocument(previewRecipe) }
}

@Preview(fontScale = 1.5f)
@Composable
internal fun RecipeDocumentLongPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeDocument(
            previewRecipe.copy(
                title = "Slow-roasted salmon with spinach, yoghurt and a sourdough crumb",
                minutes = 70,
                difficulty = RecipeDifficulty.AMBITIOUS,
                tip = null
            )
        )
    }
}
