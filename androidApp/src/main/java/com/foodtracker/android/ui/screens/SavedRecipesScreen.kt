package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderArt
import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderSwipeAction
import com.foodtracker.android.ui.design.LarderSwipeRow
import com.foodtracker.android.ui.design.LarderSwipeStyle
import com.foodtracker.android.ui.design.LarderThumb
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.design.larderGroupItem
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.formatDayMonth
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.SavedRecipe
import com.foodtracker.recipe.SavedRecipeKind
import com.foodtracker.util.DateUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The day a recipe was saved, in the phone's time zone. */
internal fun savedRecipeDate(saved: SavedRecipe, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    Instant.fromEpochMilliseconds(saved.createdAtEpochMillis).toLocalDateTime(zone).date

/** A recipe's own title; a menu in the app's words, whatever language it was saved under. */
internal fun savedRecipeTitle(resources: Resources, saved: SavedRecipe): String =
    if (saved.kind == SavedRecipeKind.MENU) resources.getString(R.string.recipe_menu_title) else saved.title

/** "15 min · Written on this phone", or "9 dishes · From your pack" for a menu, after the day;
 *  the day itself is formatted by the caller. */
internal fun savedRecipeDetails(resources: Resources, saved: SavedRecipe): List<String> = listOfNotNull(
    if (saved.kind == SavedRecipeKind.MENU) {
        resources.getQuantityString(R.plurals.recipe_saved_dishes, saved.dishCount, saved.dishCount)
    } else {
        saved.recipe?.minutes?.takeIf { it > 0 }?.let { recipeDurationLabel(resources, it) }
    },
    resources.getString(
        if (saved.source == RecipeSource.DEVICE) R.string.recipe_saved_from_phone else R.string.recipe_saved_from_pack
    )
)

/** Everything written in the app, newest first, on this phone only. Swipe a row left to delete
 *  it, with Undo; the recipe's own page has Delete as well. */
@Composable
fun SavedRecipesScreen(
    stateHolder: RecipeStateHolder,
    onBack: () -> Unit,
    onDelete: (String) -> Unit
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    // Recipes and menus alike, newest first.
    val saved = state.saved
    Scaffold(
        topBar = { LarderTopBar(title = stringResource(R.string.recipe_saved_action), onBack = onBack) },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        SavedRecipesList(saved, !state.savedLoaded, padding, today = DateUtils.today(), onDelete = onDelete)
    }
}

@Composable
private fun SavedRecipesList(
    saved: List<SavedRecipe>,
    loading: Boolean,
    padding: PaddingValues,
    today: LocalDate,
    onDelete: (String) -> Unit
) {
    val resources = LocalContext.current.resources
    val navigator = LocalLarderNavigator.current
    if (saved.isEmpty()) {
        if (!loading) {
            EmptyState(
                title = stringResource(R.string.recipe_saved_empty_title),
                message = stringResource(R.string.recipe_saved_empty_body),
                art = LarderArt.Shelf,
                modifier = Modifier.padding(padding)
            )
        }
        return
    }
    val deleteLabel = stringResource(R.string.delete)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = PaddingValues(
                start = LarderSpace.s4,
                end = LarderSpace.s4,
                top = padding.calculateTopPadding() + LarderSpace.s2,
                bottom = padding.calculateBottomPadding() + LarderSpace.s6
            )
        ) {
            itemsIndexed(saved, key = { _, item -> item.id }) { index, item ->
                val date = savedRecipeDate(item)
                val day = when (date.toEpochDays() - today.toEpochDays()) {
                    0L -> stringResource(R.string.recipe_saved_today)
                    -1L -> stringResource(R.string.recipe_saved_yesterday)
                    else -> formatDayMonth(date)
                }
                LarderSwipeRow(
                    startAction = null,
                    endAction = LarderSwipeAction(LarderIcons.Trash2, deleteLabel, LarderSwipeStyle.Danger) {
                        onDelete(item.id)
                    },
                    modifier = Modifier
                        .animateItem()
                        .larderGroupItem(LarderGroupPosition.of(index, saved.size))
                ) {
                    LarderListItem(
                        title = savedRecipeTitle(resources, item),
                        supporting = (listOf(day) + savedRecipeDetails(resources, item)).joinToString(" · "),
                        // The swipe slides this over its reveal, so it can't be see-through.
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                        leading = { LarderThumb(imageUrl = null, fallbackIcon = if (item.kind == SavedRecipeKind.MENU) LarderIcons.CalendarRange else LarderIcons.Utensils) },
                        trailing = {
                            LarderIcon(
                                LarderIcons.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { navigator.navigate(LarderRoute.RecipeResult(item.id)) }
                    )
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
internal fun SavedRecipesPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        val today = LocalDate(2026, 11, 4)
        val day = 86_400_000L
        val noon = today.toEpochDays() * day + day / 2
        SavedRecipesList(
            saved = listOf(
                SavedRecipe.of("1", Recipe(title = "Spinach yoghurt toast", minutes = 10, steps = listOf("a")), noon),
                SavedRecipe.of("2", Recipe(title = "Slow-roasted salmon with spinach, yoghurt and sourdough crumb", minutes = 70, steps = listOf("a")), noon - 7 * day)
            ),
            loading = false,
            padding = PaddingValues(0.dp),
            today = today,
            onDelete = {}
        )
    }
}

@PreviewLightDark
@Composable
internal fun SavedRecipesEmptyPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        SavedRecipesList(emptyList(), loading = false, padding = PaddingValues(0.dp), today = LocalDate(2026, 11, 4), onDelete = {})
    }
}
