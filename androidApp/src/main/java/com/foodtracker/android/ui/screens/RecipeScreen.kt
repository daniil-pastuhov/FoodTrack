package com.foodtracker.android.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderSpinner
import com.foodtracker.android.ui.design.LedeHeader
import com.foodtracker.android.ui.design.PantryRow as PantryRowView
import com.foodtracker.android.ui.design.PinnedAction
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.design.categoryIcon
import com.foodtracker.android.ui.design.expiryStatusLabel
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.buildRecipePrompt
import com.foodtracker.android.util.copyRecipePrompt
import com.foodtracker.android.util.shareRecipePrompt
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.recipe.OnDeviceAvailability
import com.foodtracker.recipe.RECIPE_SHOWN_ITEMS
import com.foodtracker.recipe.OnDeviceRoute
import com.foodtracker.recipe.RecipeConfig
import com.foodtracker.recipe.RecipeEvent
import com.foodtracker.recipe.RecipeSelection
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.recipe.RecipeUiState
import com.foodtracker.viewmodel.PantryRow
import kotlinx.datetime.LocalDate

/** What the tab's pinned action does, worked out from the state. With no paid route yet, the
 *  phone's own model leads where it is ready, and the shared prompt everywhere else. */
internal enum class RecipePrimary { OnDevice, Share }

internal fun recipePrimary(state: RecipeUiState): RecipePrimary =
    if (state.onDeviceRoute == OnDeviceRoute.READY) RecipePrimary.OnDevice else RecipePrimary.Share

/**
 * The Recipe tab: the soonest items ticked, a short form, and the free routes. The primary
 * action is pinned where Scan sits on Home; the root measures it through [onPinnedActionHeight]
 * so content, the pill and snackbars clear it.
 */
@Composable
fun RecipeScreen(
    stateHolder: RecipeStateHolder,
    bottomOverlayClearance: Dp,
    pinnedActionBottom: Dp,
    onPinnedActionHeight: (Int) -> Unit
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalLarderNavigator.current
    val snackbars = LocalLarderSnackbars.current
    val failedMessage = stringResource(R.string.recipe_failed_phone)
    val tryAgain = stringResource(R.string.recipe_try_again)
    val copiedMessage = stringResource(R.string.recipe_prompt_copied)

    // The model can finish downloading, or the app's language change, while the tab is away.
    LifecycleResumeEffect(stateHolder) {
        stateHolder.refresh()
        onPauseOrDispose { }
    }

    val event = state.event
    LaunchedEffect(event) {
        when (event) {
            is RecipeEvent.Written -> {
                stateHolder.consumeEvent()
                navigator.navigate(LarderRoute.RecipeResult(event.savedId))
            }
            RecipeEvent.Failed -> {
                stateHolder.consumeEvent()
                snackbars.show(failedMessage, tryAgain) { stateHolder.generateOnDevice() }
            }
            null -> Unit
        }
    }

    fun prompt(): String = buildRecipePrompt(
        context,
        state.selectedRows.map { it.item },
        state.config,
        state.allergens
    )

    RecipeTabContent(
        state = state,
        bottomOverlayClearance = bottomOverlayClearance,
        pinnedActionBottom = pinnedActionBottom,
        onPinnedActionHeight = onPinnedActionHeight,
        actions = RecipeTabActions(
            onToggle = stateHolder::toggleItem,
            onConfigChange = stateHolder::updateConfig,
            onGenerateOnDevice = stateHolder::generateOnDevice,
            onStop = stateHolder::stopWriting,
            onShare = { shareRecipePrompt(context, prompt()) },
            onCopy = {
                copyRecipePrompt(context, prompt())
                // Android 13 and up confirm a copy themselves.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbars.show(copiedMessage, null, null)
            },
            onOpenSaved = { navigator.navigate(LarderRoute.SavedRecipes) },
            onOpenSettings = { navigator.navigate(LarderRoute.RecipeSettings) },
            onScan = { navigator.navigate(LarderRoute.Scanner()) }
        )
    )
}

internal class RecipeTabActions(
    val onToggle: (String) -> Unit = {},
    val onConfigChange: ((RecipeConfig) -> RecipeConfig) -> Unit = {},
    val onGenerateOnDevice: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onOpenSaved: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onScan: () -> Unit = {}
)

@Composable
internal fun RecipeTabContent(
    state: RecipeUiState,
    bottomOverlayClearance: Dp,
    pinnedActionBottom: Dp,
    onPinnedActionHeight: (Int) -> Unit,
    actions: RecipeTabActions
) {
    val expanded = isExpanded()
    var showingOthers by remember { mutableStateOf(false) }
    val showForm = !state.isLoading && state.rows.isNotEmpty() && !state.isWriting

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LedeHeader(
                lede = stringResource(R.string.recipe_lede),
                actions = {
                    LarderIconButton(
                        icon = LarderIcons.Bookmark,
                        contentDescription = stringResource(R.string.recipe_saved_action),
                        onClick = actions.onOpenSaved
                    )
                    LarderIconButton(
                        icon = LarderIcons.SlidersHorizontal,
                        contentDescription = stringResource(R.string.recipe_settings_action),
                        onClick = actions.onOpenSettings
                    )
                }
            )
            when {
                state.isLoading -> Unit
                state.rows.isEmpty() -> EmptyState(
                    title = stringResource(R.string.recipe_empty_title),
                    message = stringResource(R.string.recipe_empty_body),
                    modifier = Modifier.padding(bottom = bottomOverlayClearance),
                    action = {
                        LarderButton(
                            text = stringResource(R.string.scan_content_description),
                            onClick = actions.onScan,
                            leadingIcon = LarderIcons.ScanBarcode
                        )
                    }
                )
                state.isWriting -> WritingOnPhone(onStop = actions.onStop, bottom = bottomOverlayClearance)
                else -> RecipeForm(
                    state = state,
                    expanded = expanded,
                    bottom = bottomOverlayClearance,
                    actions = actions,
                    onAddOthers = { showingOthers = true }
                )
            }
        }

        if (showForm) {
            val primary = recipePrimary(state)
            val nothingTicked = state.selectedIds.isEmpty()
            DisposableEffect(Unit) { onDispose { onPinnedActionHeight(0) } }
            PinnedAction(
                text = stringResource(
                    if (primary == RecipePrimary.OnDevice) R.string.recipe_generate_on_phone else R.string.recipe_share_prompt
                ),
                icon = if (primary == RecipePrimary.OnDevice) LarderIcons.Smartphone else LarderIcons.Share2,
                onClick = if (primary == RecipePrimary.OnDevice) actions.onGenerateOnDevice else actions.onShare,
                cost = if (primary == RecipePrimary.OnDevice) stringResource(R.string.recipe_cost_free) else null,
                enabled = !nothingTicked,
                modifier = Modifier
                    .align(if (expanded) Alignment.BottomEnd else Alignment.BottomCenter)
                    .padding(
                        start = LarderSpace.s4,
                        end = if (expanded) LarderSpace.s6 else LarderSpace.s4,
                        bottom = pinnedActionBottom
                    )
                    .then(if (expanded) Modifier.widthIn(max = 440.dp).fillMaxWidth(0.5f) else Modifier.fillMaxWidth())
                    .onSizeChanged { onPinnedActionHeight(it.height) }
            )
        }
    }

    if (showingOthers) {
        OtherItemsSheet(
            rows = state.rows.drop(RECIPE_SHOWN_ITEMS),
            selectedIds = state.selectedIds,
            onToggle = actions.onToggle,
            onDismiss = { showingOthers = false }
        )
    }
}

@Composable
private fun RecipeForm(
    state: RecipeUiState,
    expanded: Boolean,
    bottom: Dp,
    actions: RecipeTabActions,
    onAddOthers: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = LarderSpace.s4, end = LarderSpace.s4, bottom = bottom),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
        ) {
            if (state.onDeviceRoute == OnDeviceRoute.PREPARING) {
                LarderCallout(
                    kind = LarderCalloutKind.Info,
                    icon = LarderIcons.Download,
                    title = stringResource(R.string.recipe_preparing_title),
                    message = stringResource(R.string.recipe_preparing_body)
                )
            }
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s6)) {
                    Column(modifier = Modifier.weight(1f)) {
                        CookWith(state, actions.onToggle, onAddOthers)
                    }
                    // Lined up with the first row, under the "Cook with" label.
                    Column(
                        modifier = Modifier.weight(1f).padding(top = LarderSpace.s8),
                        verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)
                    ) {
                        QuickForm(state, actions)
                        FreeActions(state, actions)
                    }
                }
            } else {
                CookWith(state, actions.onToggle, onAddOthers)
                QuickForm(state, actions)
                FreeActions(state, actions)
            }
        }
    }
}

@Composable
private fun CookWith(state: RecipeUiState, onToggle: (String) -> Unit, onAddOthers: () -> Unit) {
    SectionHeader(text = stringResource(R.string.recipe_cook_with), first = true)
    LarderGroup {
        state.shownRows.forEachIndexed { index, row ->
            if (index > 0) LarderDivider()
            RecipeItemRow(row = row, selected = row.id in state.selectedIds, onToggle = { onToggle(row.id) })
        }
        if (state.otherCount > 0) {
            LarderDivider()
            LarderListItem(
                title = stringResource(R.string.recipe_add_other),
                supporting = pluralStringResource(R.plurals.recipe_add_other_count, state.otherCount, state.otherCount),
                leading = { LarderIcon(LarderIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailing = { LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = onAddOthers
            )
        }
    }
}

/** A pantry item with a tick: the whole row toggles it, as in Home's multi-select. */
@Composable
private fun RecipeItemRow(row: PantryRow, selected: Boolean, onToggle: () -> Unit) {
    val item = row.item
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    // The row carries days left, not the date it was derived on; this is that date.
    val today = remember(item.expiryDate, row.daysRemaining) {
        LocalDate.fromEpochDays(item.expiryDate.toEpochDays() - row.daysRemaining)
    }
    val statusText = remember(resources, locale, row.status, row.daysRemaining, item.expiryDate, today) {
        expiryStatusLabel(resources, locale, row.status, row.daysRemaining, item.expiryDate, today)
    }
    PantryRowView(
        name = item.name,
        status = row.status,
        statusText = statusText,
        place = null,
        quantity = item.quantity,
        onClick = onToggle,
        imageUrl = item.imageUrl?.takeIf { it.isNotBlank() },
        fallbackIcon = categoryIcon(row.categoryDisplayName ?: item.category),
        selecting = true,
        selected = selected
    )
}

@Composable
private fun QuickForm(state: RecipeUiState, actions: RecipeTabActions) {
    val resources = LocalContext.current.resources
    val config = state.config
    RecipeFormCard {
        DifficultyControl(config.difficulty) { value -> actions.onConfigChange { it.copy(difficulty = value) } }
        ServingsControl(config.servings) { value -> actions.onConfigChange { it.copy(servings = value) } }
        TimeControl(config.maxMinutes) { value -> actions.onConfigChange { it.copy(maxMinutes = value) } }
    }
    LarderGroup {
        LarderListItem(
            title = stringResource(R.string.recipe_more_options),
            supporting = recipeOptionsSummary(resources, config, state.appLanguage),
            leading = { LarderIcon(LarderIcons.SlidersHorizontal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailing = { LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            onClick = actions.onOpenSettings
        )
    }
}

/** What sits under the form: the free routes that aren't the pinned action, or why the pinned
 *  action is off. */
@Composable
private fun ColumnScope.FreeActions(state: RecipeUiState, actions: RecipeTabActions) {
    when {
        state.selectedIds.isEmpty() -> RecipeHint(stringResource(R.string.recipe_tick_hint))
        recipePrimary(state) == RecipePrimary.OnDevice -> Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
        ) {
            LarderButton(
                text = stringResource(R.string.recipe_share_prompt),
                onClick = actions.onShare,
                style = LarderButtonStyle.Text,
                leadingIcon = LarderIcons.Share2
            )
            LarderButton(
                text = stringResource(R.string.recipe_copy_prompt),
                onClick = actions.onCopy,
                style = LarderButtonStyle.Text,
                leadingIcon = LarderIcons.Copy
            )
        }
        else -> {
            LarderButton(
                text = stringResource(R.string.recipe_copy_prompt),
                onClick = actions.onCopy,
                modifier = Modifier.fillMaxWidth(),
                style = LarderButtonStyle.Outline,
                leadingIcon = LarderIcons.Copy
            )
            RecipeHint(stringResource(R.string.recipe_prompt_hint))
        }
    }
}

@Composable
private fun RecipeHint(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun WritingOnPhone(onStop: () -> Unit, bottom: Dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = LarderSpace.s6, end = LarderSpace.s6, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LarderSpinner()
        Text(
            text = stringResource(R.string.recipe_writing_phone_title),
            modifier = Modifier.padding(top = LarderSpace.s2),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.recipe_writing_phone_body),
            modifier = Modifier.widthIn(max = 340.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        LarderButton(
            text = stringResource(R.string.recipe_stop),
            onClick = onStop,
            style = LarderButtonStyle.Outline
        )
    }
}

/** Everything past the first few, to tick or untick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherItemsSheet(
    rows: List<PantryRow>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val toggle by rememberUpdatedState(onToggle)
    LarderSheet(onDismissRequest = onDismiss, title = stringResource(R.string.recipe_add_other)) {
        LarderGroup {
            rows.forEachIndexed { index, row ->
                if (index > 0) LarderDivider()
                RecipeItemRow(row = row, selected = row.id in selectedIds, onToggle = { toggle(row.id) })
            }
        }
        LarderButton(
            text = stringResource(R.string.recipe_done),
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = LarderSpace.s4)
        )
    }
}

// --- Previews ---------------------------------------------------------------------------------

private val previewToday = LocalDate(2026, 11, 4)

private fun previewRow(id: String, name: String, days: Int, category: String, quantity: Int = 1) = PantryRow(
    item = PantryItem(
        id = id,
        name = name,
        expiryDate = LocalDate.fromEpochDays(previewToday.toEpochDays() + days),
        addedDate = previewToday,
        quantity = quantity,
        category = category
    ),
    daysRemaining = days,
    status = ExpiryStatus.compute(days, 3),
    categoryDisplayName = category,
    sortName = name.lowercase()
)

private val previewRows = listOf(
    previewRow("1", "Sourdough bread", -1, "Bakery"),
    previewRow("2", "Baby spinach", 0, "Vegetables"),
    previewRow("3", "Greek yoghurt", 1, "Dairy", 2),
    previewRow("4", "Semi-skimmed milk", 2, "Dairy"),
    previewRow("5", "Salmon fillet", 3, "Meat & fish", 2),
    previewRow("6", "Strawberries", 4, "Fruit"),
    previewRow("7", "Free-range eggs", 15, "Dairy", 6)
)

private fun previewState(
    availability: OnDeviceAvailability = OnDeviceAvailability.UNSUPPORTED,
    selection: RecipeSelection = RecipeSelection.Default,
    rows: List<PantryRow> = previewRows,
    writing: Boolean = false
) = RecipeUiState(
    isLoading = false,
    rows = rows,
    selection = selection,
    onDeviceAvailability = availability,
    onDeviceSupportsLanguage = true,
    isWriting = writing
)

@Composable
private fun RecipeTabPreview(state: RecipeUiState) = LarderTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        RecipeTabContent(state, 120.dp, 96.dp, {}, RecipeTabActions())
    }
}

@PreviewLightDark
@Composable
internal fun RecipeTabPromptOnlyPreview() = RecipeTabPreview(previewState())

@PreviewLightDark
@Composable
internal fun RecipeTabPhoneCanWritePreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE))

@Preview
@Composable
internal fun RecipeTabPhonePreparingPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.DOWNLOADING))

@Preview
@Composable
internal fun RecipeTabNothingTickedPreview() = RecipeTabPreview(previewState(selection = RecipeSelection.Chosen(emptySet())))

@Preview
@Composable
internal fun RecipeTabWritingPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE, writing = true))

@Preview
@Composable
internal fun RecipeTabEmptyPreview() = RecipeTabPreview(previewState(rows = emptyList()))

@Preview(locale = "ru")
@Composable
internal fun RecipeTabRussianPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE))

@Preview(widthDp = 820, heightDp = 1180)
@Composable
internal fun RecipeTabTabletPreview() = RecipeTabPreview(previewState(OnDeviceAvailability.AVAILABLE))
