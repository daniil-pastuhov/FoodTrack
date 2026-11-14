package com.foodtracker.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderTick
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.ShoppingCandidate
import com.foodtracker.recipe.defaultShoppingSelection
import com.foodtracker.recipe.shoppingCandidates

/** What a result screen needs to offer "Add to shopping list": the names on the list now, and how
 *  to add some. Without [onAdd] (previews) nothing is offered. */
data class RecipeShoppingActions(
    val listedNames: List<String> = emptyList(),
    val onAdd: ((List<String>) -> Unit)? = null
)

/**
 * "Add to shopping list" under a recipe's ingredients, or under a menu's title for every day of it:
 * shown only when [recipes] has an ingredient the cook doesn't have. Opens [RecipeShoppingDialog].
 */
@Composable
internal fun AddToShoppingButton(recipes: List<Recipe>, shopping: RecipeShoppingActions, modifier: Modifier = Modifier) {
    val onAdd = shopping.onAdd ?: return
    val candidates = remember(recipes, shopping.listedNames) { shoppingCandidates(recipes, shopping.listedNames) }
    if (candidates.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    LarderButton(
        text = stringResource(R.string.recipe_shopping_add),
        onClick = { open = true },
        modifier = modifier,
        style = LarderButtonStyle.Outline,
        leadingIcon = LarderIcons.ListPlus
    )
    if (open) {
        RecipeShoppingDialog(
            candidates = candidates,
            onConfirm = { names ->
                open = false
                onAdd(names)
            },
            onDismiss = { open = false }
        )
    }
}

/** The ingredients to buy, every one ticked at first (the owner's rule) except those already on
 *  the list, which are shown but can't be ticked. The confirm button counts the ticks. */
@Composable
internal fun RecipeShoppingDialog(candidates: List<ShoppingCandidate>, onConfirm: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var ticked by remember(candidates) { mutableStateOf(defaultShoppingSelection(candidates)) }
    val toBuy = candidates.count { !it.alreadyListed }
    val count = ticked.size
    LarderDialog(
        title = stringResource(R.string.recipe_shopping_title, toBuy),
        text = stringResource(R.string.recipe_shopping_message),
        confirmLabel = pluralStringResource(R.plurals.recipe_shopping_confirm, count, count),
        confirmEnabled = count > 0,
        onConfirm = { onConfirm(candidates.filter { it.key in ticked }.map { it.name }) },
        dismissLabel = stringResource(R.string.recipe_cancel),
        onDismissRequest = onDismiss,
        icon = LarderIcons.ListPlus
    ) {
        Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            candidates.forEach { candidate ->
                ShoppingRow(candidate, candidate.key in ticked) { on ->
                    ticked = if (on) ticked + candidate.key else ticked - candidate.key
                }
            }
        }
    }
}

@Composable
private fun ShoppingRow(candidate: ShoppingCandidate, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val enabled = !candidate.alreadyListed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderTick(checked = checked, onCheckedChange = onCheckedChange, contentDescription = candidate.name, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(vertical = LarderSpace.s1)) {
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!enabled) {
                Text(
                    text = stringResource(R.string.recipe_shopping_already),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** The quiet line under a recipe's ingredients when some aren't from the pantry: true for staples
 *  and extras alike, so no row is labelled wrongly. */
@Composable
internal fun NotFromPantryHint(recipe: Recipe, modifier: Modifier = Modifier) {
    if (recipe.ingredients.all { it.fromPantry }) return
    Text(
        text = stringResource(R.string.recipe_not_from_pantry_hint),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
