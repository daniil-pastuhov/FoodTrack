package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import android.net.Uri
import com.foodtracker.android.recipe.RecipePictureFiles
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderArt
import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderSwipeAction
import com.foodtracker.android.ui.design.LarderSwipeRow
import com.foodtracker.android.ui.design.LarderSwipeStyle
import com.foodtracker.android.ui.design.LarderThumb
import com.foodtracker.android.ui.design.LarderTick
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.design.larderGroupItem
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.formatDayMonth
import com.foodtracker.recipe.DEFAULT_MENU_DAYS
import com.foodtracker.recipe.Menu
import com.foodtracker.recipe.Recipe
import com.foodtracker.recipe.RecipeSource
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.SavedRecipe
import com.foodtracker.recipe.SavedRecipeKind
import com.foodtracker.recipe.SavedSelection
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
    if (saved.kind == SavedRecipeKind.MENU) saved.menu?.let { menuTitle(resources, it) } ?: menuTitle(resources, DEFAULT_MENU_DAYS) else saved.title

/** "Menu for 2 days": the day count read from the menu itself, so one saved before menu sets
 *  existed says three. */
internal fun menuTitle(resources: Resources, menu: Menu): String = menuTitle(resources, menu.days.size.coerceAtLeast(1))

internal fun menuTitle(resources: Resources, days: Int): String = resources.getQuantityString(R.plurals.recipe_menu_title, days, days)

/** "15 min · Written on this device", or "9 dishes" for a menu, after the day; only the phone's
 *  model's recipes say where they were written. The day itself is formatted by the caller. */
internal fun savedRecipeDetails(resources: Resources, saved: SavedRecipe): List<String> = listOfNotNull(
    if (saved.kind == SavedRecipeKind.MENU) {
        resources.getQuantityString(R.plurals.recipe_saved_dishes, saved.dishCount, saved.dishCount)
    } else {
        saved.recipe?.minutes?.takeIf { it > 0 }?.let { recipeDurationLabel(resources, it) }
    },
    resources.getString(R.string.recipe_saved_from_phone).takeIf { saved.source == RecipeSource.DEVICE }
)

/** The selected ids, kept across rotation and process death as a plain list. */
private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/** Everything written in the app, newest first, on this phone only. Swipe a row left to delete
 *  it, with Undo; the recipe's own page has Delete as well. Several go at once through selection:
 *  a long press on a row, or Select in the top bar, then Delete, with one Undo for all of them
 *  ([onDeleteSelected]). */
@Composable
fun SavedRecipesScreen(
    stateHolder: RecipeStateHolder,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteSelected: (Set<String>) -> Unit
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    // Recipes and menus alike, newest first.
    val saved = state.saved
    val savedIds = remember(saved) { saved.map { it.id } }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet()) }
    // Only ids still listed: one deleted elsewhere (its own page, an Undo elsewhere) drops out.
    val selected = if (state.savedLoaded) SavedSelection.pruned(picked, savedIds) else picked
    LaunchedEffect(savedIds, state.savedLoaded) {
        if (!state.savedLoaded) return@LaunchedEffect
        picked = SavedSelection.pruned(picked, savedIds)
        if (savedIds.isEmpty()) selecting = false
    }
    val exitSelection = {
        selecting = false
        picked = emptySet()
    }
    BackHandler(enabled = selecting, onBack = exitSelection)
    SavedRecipesScaffold(
        saved = saved,
        loading = !state.savedLoaded,
        today = DateUtils.today(),
        selection = if (selecting) selected else null,
        onBack = onBack,
        onDelete = onDelete,
        onStartSelection = { id ->
            selecting = true
            picked = setOfNotNull(id)
        },
        onToggle = { picked = SavedSelection.toggled(selected, it) },
        onToggleAll = { picked = SavedSelection.toggledAll(selected, savedIds) },
        onExitSelection = exitSelection,
        onDeleteSelected = {
            onDeleteSelected(selected)
            exitSelection()
        },
        isUnread = state::isUnread
    )
}

/** The screen without its state. [selection] is null outside selection, else the selected ids
 *  (possibly none). */
@Composable
private fun SavedRecipesScaffold(
    saved: List<SavedRecipe>,
    loading: Boolean,
    today: LocalDate,
    selection: Set<String>?,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    onStartSelection: (String?) -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onExitSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    isUnread: (String) -> Boolean = { false }
) {
    Scaffold(
        topBar = {
            if (selection != null) {
                val all = SavedSelection.allSelected(selection, saved.map { it.id })
                LarderTopBar(
                    title = stringResource(R.string.selection_count_format, selection.size),
                    onBack = onExitSelection,
                    backDescription = stringResource(R.string.exit_selection_content_description),
                    navigationIcon = LarderIcons.X
                ) {
                    LarderButton(
                        text = stringResource(if (all) R.string.deselect_all_button else R.string.select_all_button),
                        onClick = onToggleAll,
                        style = LarderButtonStyle.Text,
                        maxLines = 1
                    )
                }
            } else {
                LarderTopBar(title = stringResource(R.string.recipe_saved_action), onBack = onBack) {
                    // The way in without a long press, for discoverability and TalkBack.
                    if (saved.isNotEmpty()) {
                        LarderButton(
                            text = stringResource(R.string.select_items_button),
                            onClick = { onStartSelection(null) },
                            style = LarderButtonStyle.Text,
                            maxLines = 1
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (selection != null) SelectionDeleteBar(enabled = selection.isNotEmpty(), onDelete = onDeleteSelected)
        },
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        SavedRecipesList(
            saved = saved,
            loading = loading,
            padding = padding,
            today = today,
            onDelete = onDelete,
            isUnread = isUnread,
            selection = selection,
            onToggle = onToggle,
            onStartSelection = { onStartSelection(it) }
        )
    }
}

/** Delete for what is selected, at the bottom in thumb reach as on Home; off with nothing selected. */
@Composable
private fun SelectionDeleteBar(enabled: Boolean, onDelete: () -> Unit) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        LarderDivider()
        Box(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3)
        ) {
            LarderButton(
                text = stringResource(R.string.delete),
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                style = LarderButtonStyle.DangerText,
                enabled = enabled,
                leadingIcon = LarderIcons.Trash2
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRecipesList(
    saved: List<SavedRecipe>,
    loading: Boolean,
    padding: PaddingValues,
    today: LocalDate,
    onDelete: (String) -> Unit,
    isUnread: (String) -> Boolean = { false },
    selection: Set<String>? = null,
    onToggle: (String) -> Unit = {},
    onStartSelection: (String) -> Unit = {},
    /** Previews only: drawn thumbnails by saved id, since they load no files. */
    previewThumbnails: Map<String, Painter> = emptyMap()
) {
    val context = LocalContext.current
    val resources = context.resources
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
    val selectLabel = stringResource(R.string.select_items_button)
    val selecting = selection != null
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
                val selected = selection?.contains(item.id) == true
                val title = savedRecipeTitle(resources, item)
                // Swipe is off while selecting, as on Home.
                LarderSwipeRow(
                    startAction = null,
                    endAction = LarderSwipeAction(LarderIcons.Trash2, deleteLabel, LarderSwipeStyle.Danger) {
                        onDelete(item.id)
                    },
                    enabled = !selecting,
                    modifier = Modifier
                        .animateItem()
                        .larderGroupItem(LarderGroupPosition.of(index, saved.size))
                ) {
                    LarderListItem(
                        title = title,
                        supporting = (listOf(day) + savedRecipeDetails(resources, item)).joinToString(" · "),
                        // The swipe slides this over its reveal, so it can't be see-through.
                        modifier = Modifier
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .then(
                                if (selecting) {
                                    // One checkbox per row for TalkBack: "selected" / "not selected".
                                    Modifier.toggleable(value = selected, role = Role.Checkbox) { onToggle(item.id) }
                                } else {
                                    Modifier.combinedClickable(
                                        onClick = { navigator.navigate(LarderRoute.RecipeResult(item.id)) },
                                        onLongClick = { onStartSelection(item.id) },
                                        onLongClickLabel = selectLabel
                                    )
                                }
                            ),
                        leading = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selecting) {
                                    // The row is the checkbox; the tick only shows it. Its 48dp
                                    // target overhangs 32dp of layout, as on Home.
                                    Box(modifier = Modifier.width(LarderSpace.s8), contentAlignment = Alignment.Center) {
                                        LarderTick(
                                            checked = selected,
                                            onCheckedChange = { onToggle(item.id) },
                                            contentDescription = title,
                                            modifier = Modifier.requiredSize(LarderSize.touch).clearAndSetSemantics {}
                                        )
                                    }
                                }
                                // The dish's picture, kept with the saved recipe, when it has one.
                                val picture = item.recipe?.picture
                                val thumbnail = remember(item.id, picture) {
                                    picture?.let { RecipePictureFiles.file(context, item.id, it) }?.let { Uri.fromFile(it).toString() }
                                }
                                LarderThumb(
                                    imageUrl = thumbnail,
                                    painter = previewThumbnails[item.id],
                                    fallbackIcon = if (item.kind == SavedRecipeKind.MENU) LarderIcons.CalendarRange else LarderIcons.Utensils,
                                    background = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer
                                )
                            }
                        },
                        trailing = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Written while the person was elsewhere and not opened yet.
                                if (isUnread(item.id)) UnreadDot()
                                if (!selecting) {
                                    LarderIcon(
                                        LarderIcons.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** A small dot for a saved recipe not opened yet, said as "New". */
@Composable
private fun UnreadDot() {
    val description = stringResource(R.string.recipe_saved_unread)
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = description }
    )
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
            onDelete = {},
            isUnread = { it == "1" }
        )
    }
}

/** A recipe with pictures has its dish as the row's thumbnail; the others keep their icons. */
@PreviewLightDark
@Composable
internal fun SavedRecipesPicturesPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        val today = LocalDate(2026, 11, 4)
        val day = 86_400_000L
        val noon = today.toEpochDays() * day + day / 2
        SavedRecipesList(
            saved = listOf(
                SavedRecipe.of("1", Recipe(title = "Spinach and yoghurt eggs on sourdough toast", minutes = 15, steps = listOf("a"), source = RecipeSource.SERVER), noon),
                SavedRecipe.of("2", Recipe(title = "Spinach yoghurt toast", minutes = 10, steps = listOf("a")), noon - day),
                SavedRecipe.of("3", Recipe(title = "Slow-roasted salmon with spinach, yoghurt and sourdough crumb", minutes = 70, steps = listOf("a"), source = RecipeSource.SERVER), noon - 7 * day)
            ),
            loading = false,
            padding = PaddingValues(0.dp),
            today = today,
            onDelete = {},
            previewThumbnails = mapOf("1" to SampleDishPainter())
        )
    }
}

@PreviewLightDark
@Composable
internal fun SavedRecipesSelectingPreview() = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        val today = LocalDate(2026, 11, 4)
        val day = 86_400_000L
        val noon = today.toEpochDays() * day + day / 2
        SavedRecipesScaffold(
            saved = listOf(
                SavedRecipe.of("1", Recipe(title = "Spinach yoghurt toast", minutes = 10, steps = listOf("a")), noon),
                SavedRecipe.of("2", Recipe(title = "Slow-roasted salmon with spinach, yoghurt and sourdough crumb", minutes = 70, steps = listOf("a")), noon - 7 * day),
                SavedRecipe.of("3", Recipe(title = "Cheddar omelette", minutes = 15, steps = listOf("a")), noon - 9 * day)
            ),
            loading = false,
            today = today,
            selection = setOf("1", "3"),
            onBack = {},
            onDelete = {},
            onStartSelection = {},
            onToggle = {},
            onToggleAll = {},
            onExitSelection = {},
            onDeleteSelected = {},
            isUnread = { it == "1" }
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
