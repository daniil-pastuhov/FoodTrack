package com.foodtracker.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderChip
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIconSize
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderSwitch
import com.foodtracker.android.ui.design.LarderTextField
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.design.larderGroupItem
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.ui.theme.larderShadow
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.categoryRename
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.suggestableDisplayNames
import com.foodtracker.viewmodel.PantryDerivation
import com.foodtracker.viewmodel.PantryStateHolder
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The rows above the categories in the list: the description and Show all. */
private const val HEADER_ROWS = 2

/** Keeps the column readable on a tablet. */
private val CategoriesMaxWidth = 640.dp

/** Lets the user reorder (drag, or Move up and Move down), rename, or disable each category
 *  detected from a scanned or added product's raw category value (see [CategoryPreference]). A
 *  rename/disable applies everywhere that category is shown - pantry filter chips, item card
 *  badges, and shopping list group headers - without rewriting the raw value stored on any
 *  product, so it also applies to the same product scanned again in the future. */
@Composable
fun CategorySettingsScreen(
    stateHolder: PantryStateHolder,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val navigator = LocalLarderNavigator.current
    var renamingOrigin by remember { mutableStateOf<String?>(null) }
    var shelfLifeOrigin by remember { mutableStateOf<String?>(null) }
    // The row showing Move up and Move down, by origin so it follows the row as it moves.
    var movingOrigin by remember { mutableStateOf<String?>(null) }

    // Grouping, and the merged-origins subtitle for each row, are derived once per preferences
    // change in PantryStateHolder. The subtitle in particular used to be built inside the
    // LazyColumn item body, so every visible row re-scanned, re-sorted and re-joined the whole
    // preference list on every recomposition — including on every frame of a drag.
    val groupedPreferences = state.categoryRows
    var localPreferences by remember { mutableStateOf(groupedPreferences) }
    LaunchedEffect(groupedPreferences) { localPreferences = groupedPreferences }

    val lazyListState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = from.index - HEADER_ROWS
        val toIndex = to.index - HEADER_ROWS

        if (PantryDerivation.canMoveCategoryRow(localPreferences, fromIndex, toIndex)) {
            localPreferences = localPreferences.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }

    // The whole visible order is what gets persisted, not just where the dragged row landed
    // relative to its neighbours — see PantryStateHolder.reorderCategoryPreferences.
    fun commitReorder() {
        stateHolder.reorderCategoryPreferences(localPreferences.map { it.preference.originName })
    }

    fun move(index: Int, by: Int) {
        localPreferences = localPreferences.movingRow(index, by)
        commitReorder()
    }

    renamingOrigin?.let { origin ->
        state.categoryPreferences.firstOrNull { it.originName == origin }?.let { preference ->
            RenameCategoryDialog(
                preference = preference,
                allPreferences = state.categoryPreferences,
                onRename = { newName ->
                    stateHolder.renameCategory(origin, newName)
                    renamingOrigin = null
                },
                onDismiss = { renamingOrigin = null }
            )
        }
    }

    shelfLifeOrigin?.let { origin ->
        state.categoryPreferences.firstOrNull { it.originName == origin }?.let { preference ->
            ShelfLifeDialog(
                categoryName = categoryLabel(preference.displayName),
                initialDays = preference.defaultShelfLifeDays,
                onSave = { days ->
                    stateHolder.setCategoryDefaultShelfLife(origin, days)
                    shelfLifeOrigin = null
                },
                onDismiss = { shelfLifeOrigin = null }
            )
        }
    }

    Scaffold(
        topBar = {
            LarderTopBar(title = stringResource(R.string.category_settings_title), onBack = onNavigateBack)
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (localPreferences.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.category_settings_empty_title),
                message = stringResource(R.string.category_settings_empty),
                modifier = Modifier.padding(innerPadding),
                action = {
                    LarderButton(
                        text = stringResource(R.string.category_settings_scan),
                        onClick = { navigator.navigate(LarderRoute.Scanner()) }
                    )
                }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = PaddingValues(
                        start = LarderSpace.s4,
                        end = LarderSpace.s4,
                        top = innerPadding.calculateTopPadding() + LarderSpace.s1,
                        bottom = innerPadding.calculateBottomPadding() + LarderSpace.s4
                    ),
                    modifier = Modifier.widthIn(max = CategoriesMaxWidth).fillMaxSize()
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.category_settings_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = LarderSpace.s3)
                        )
                    }
                    item {
                        val allEnabled = localPreferences.all { it.preference.enabled }
                        LarderGroup(modifier = Modifier.padding(bottom = LarderSpace.s3)) {
                            LarderListItem(
                                title = stringResource(R.string.category_settings_toggle_all),
                                trailing = {
                                    LarderSwitch(
                                        checked = allEnabled,
                                        onCheckedChange = { stateHolder.setAllCategoriesEnabled(it) },
                                        contentDescription = stringResource(R.string.category_settings_toggle_all)
                                    )
                                }
                            )
                        }
                    }
                    itemsIndexed(localPreferences, key = { _, row -> row.preference.displayName }) { index, row ->
                        val pref = row.preference
                        ReorderableItem(reorderableState, key = pref.displayName) { isDragging ->
                            CategoryPreferenceRow(
                                preference = pref,
                                allOrigins = row.mergedOrigins,
                                isDragging = isDragging,
                                isMoving = movingOrigin == pref.originName,
                                canMoveUp = PantryDerivation.canMoveCategoryRow(localPreferences, index, index - 1),
                                canMoveDown = PantryDerivation.canMoveCategoryRow(localPreferences, index, index + 1),
                                position = LarderGroupPosition.of(index, localPreferences.size),
                                dragHandleModifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        commitReorder()
                                    }
                                ),
                                onHandleTap = {
                                    movingOrigin = pref.originName.takeUnless { it == movingOrigin }
                                },
                                onMoveUp = { move(index, -1) },
                                onMoveDown = { move(index, 1) },
                                onMoveDone = { movingOrigin = null },
                                onToggleEnabled = { stateHolder.setCategoryEnabled(pref.originName, it) },
                                onRenameClick = { renamingOrigin = pref.originName },
                                onShelfLifeClick = { shelfLifeOrigin = pref.originName },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPreferenceRow(
    preference: CategoryPreference,
    allOrigins: String,
    isDragging: Boolean,
    isMoving: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    position: LarderGroupPosition,
    dragHandleModifier: Modifier,
    onHandleTap: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveDone: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onRenameClick: () -> Unit,
    onShelfLifeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val containerColor by animateColorAsState(
        targetValue = when {
            isDragging -> scheme.surfaceContainer
            isMoving -> scheme.primaryContainer
            else -> scheme.surface
        },
        label = "categoryRowContainerColor"
    )
    val name = categoryLabel(preference.displayName)
    val moveUpLabel = stringResource(R.string.category_settings_move_up)
    val moveDownLabel = stringResource(R.string.category_settings_move_down)
    Column(
        modifier = modifier
            .then(
                if (isDragging) {
                    Modifier.larderShadow(LarderTheme.shadows.level2, RoundedCornerShape(LarderRadius.md))
                } else {
                    Modifier
                }
            )
            .larderGroupItem(position)
            .background(containerColor)
            .fillMaxWidth()
            .padding(start = LarderSpace.s1, end = LarderSpace.s3, top = LarderSpace.s2, bottom = LarderSpace.s2)
            // Screen readers get the same two moves as the buttons Tap on the handle brings up.
            .semantics {
                customActions = buildList {
                    if (canMoveUp) add(CustomAccessibilityAction(moveUpLabel) { onMoveUp(); true })
                    if (canMoveDown) add(CustomAccessibilityAction(moveDownLabel) { onMoveDown(); true })
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LarderIconButton(
                icon = LarderIcons.GripVertical,
                contentDescription = stringResource(R.string.category_settings_drag_handle_format, name),
                onClick = onHandleTap,
                modifier = dragHandleModifier
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (preference.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (allOrigins.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.category_settings_original_name_format, allOrigins),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ShelfLifeChip(
                    categoryName = name,
                    days = preference.defaultShelfLifeDays,
                    onRowTinted = isMoving,
                    onClick = onShelfLifeClick
                )
            }
            LarderIconButton(
                icon = LarderIcons.Pencil,
                contentDescription = stringResource(R.string.category_settings_rename_format, name),
                onClick = onRenameClick
            )
            LarderSwitch(
                checked = preference.enabled,
                onCheckedChange = onToggleEnabled,
                contentDescription = stringResource(R.string.category_settings_show_format, name)
            )
        }
        if (isMoving) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1, Alignment.End)
            ) {
                LarderButton(moveUpLabel, onMoveUp, style = LarderButtonStyle.Text, enabled = canMoveUp)
                LarderButton(moveDownLabel, onMoveDown, style = LarderButtonStyle.Text, enabled = canMoveDown)
                LarderButton(
                    stringResource(R.string.category_settings_move_done),
                    onMoveDone,
                    style = LarderButtonStyle.Text
                )
            }
        }
    }
}

/** The category's default shelf life, or the offer to set one. 28dp to look at, 48dp to tap. */
@Composable
private fun ShelfLifeChip(categoryName: String, days: Int?, onRowTinted: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val text = if (days != null) {
        pluralStringResource(R.plurals.category_settings_shelf_life_days_format, days, days)
    } else {
        stringResource(R.string.category_settings_shelf_life_none)
    }
    val (container, content) = when {
        onRowTinted -> scheme.surface to scheme.onSurface
        days != null -> scheme.primaryContainer to scheme.onPrimaryContainer
        else -> scheme.surfaceContainer to scheme.onSurfaceVariant
    }
    val description = stringResource(R.string.category_settings_shelf_life_format, categoryName, text)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(container)
                .heightIn(min = 28.dp)
                .padding(start = LarderSpace.s1, end = LarderSpace.s2),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderIcon(LarderIcons.Clock, contentDescription = null, size = LarderIconSize.Small, tint = content)
            Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}

@Composable
private fun RenameCategoryDialog(
    preference: CategoryPreference,
    allPreferences: List<CategoryPreference>,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val labelOf: (String) -> String = remember(context) { { categoryLabel(context.resources, it) } }
    val focusManager = LocalFocusManager.current
    var text by remember { mutableStateOf(labelOf(preference.displayName)) }
    val suggestions = remember(text, allPreferences) {
        if (text.isBlank()) {
            emptyList()
        } else {
            allPreferences
                .suggestableDisplayNames()
                .map(labelOf)
                .distinct()
                .filter { it.contains(text, ignoreCase = true) && !it.equals(text, ignoreCase = true) }
                .take(5)
        }
    }
    LarderDialog(
        title = stringResource(R.string.category_settings_rename_title),
        confirmLabel = stringResource(R.string.category_settings_rename_confirm),
        onConfirm = {
            // The field shows translated names: see categoryRename.
            val newName = categoryRename(preference.displayName, text, allPreferences.map { it.displayName }, labelOf)
            if (newName != null) onRename(newName) else onDismiss()
        },
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.date_picker_cancel)
    ) {
        LarderTextField(
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.category_settings_rename_field_label),
            modifier = Modifier.fillMaxWidth()
        )
        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
            ) {
                suggestions.forEach { suggestion ->
                    LarderChip(
                        label = suggestion,
                        selected = false,
                        onClick = {
                            text = suggestion
                            focusManager.clearFocus()
                        }
                    )
                }
            }
        }
    }
}

/** [onSave] takes null to remove the default. A value out of range is explained, not corrected:
 *  when leaving the field or on Save, and gone as soon as the value is back in range. */
@Composable
private fun ShelfLifeDialog(
    categoryName: String,
    initialDays: Int?,
    onSave: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialDays?.toString().orEmpty()) }
    var checked by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    var wasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(focused) {
        if (wasFocused && !focused) checked = true
        wasFocused = focused
    }
    val outOfRange = parseShelfLife(text) == ShelfLifeInput.OutOfRange

    LarderDialog(
        title = stringResource(R.string.category_settings_shelf_life_title_format, categoryName),
        text = stringResource(R.string.category_settings_shelf_life_dialog_message),
        confirmLabel = stringResource(R.string.category_settings_shelf_life_save),
        onConfirm = {
            when (val input = parseShelfLife(text)) {
                ShelfLifeInput.Cleared -> onSave(null)
                is ShelfLifeInput.Days -> onSave(input.days)
                ShelfLifeInput.OutOfRange -> checked = true
            }
        },
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.date_picker_cancel)
    ) {
        LarderTextField(
            value = text,
            // Digits only: the value is a day count.
            onValueChange = { input ->
                text = input.filter { it.isDigit() }.take(4)
                if (parseShelfLife(text) != ShelfLifeInput.OutOfRange) checked = false
            },
            label = stringResource(R.string.category_settings_shelf_life_field_label),
            helper = stringResource(R.string.category_settings_shelf_life_range),
            error = stringResource(R.string.category_settings_shelf_life_error).takeIf { checked && outOfRange },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth()
        )
        // In the body rather than the button row, which has no room for a third in Russian.
        LarderButton(
            text = stringResource(R.string.category_settings_shelf_life_clear),
            onClick = { onSave(null) },
            style = LarderButtonStyle.Text
        )
    }
}

private val PreviewCategory = CategoryPreference(
    originName = "Dairy",
    displayName = "Dairy",
    enabled = true,
    sortOrder = 0.0,
    defaultShelfLifeDays = 7
)

@Composable
private fun PreviewRow(
    preference: CategoryPreference,
    position: LarderGroupPosition,
    allOrigins: String = "",
    isDragging: Boolean = false,
    isMoving: Boolean = false
) = CategoryPreferenceRow(
    preference = preference,
    allOrigins = allOrigins,
    isDragging = isDragging,
    isMoving = isMoving,
    canMoveUp = true,
    canMoveDown = true,
    position = position,
    dragHandleModifier = Modifier,
    onHandleTap = {},
    onMoveUp = {},
    onMoveDown = {},
    onMoveDone = {},
    onToggleEnabled = {},
    onRenameClick = {},
    onShelfLifeClick = {}
)

@PreviewLightDark
@Composable
internal fun CategoryRowsPreview() = LarderPreview {
    Column {
        PreviewRow(PreviewCategory, LarderGroupPosition.First)
        PreviewRow(
            PreviewCategory.copy(defaultShelfLifeDays = null),
            LarderGroupPosition.Middle,
            allOrigins = "Breakfast cereals, Plant-based foods"
        )
        PreviewRow(PreviewCategory.copy(enabled = false), LarderGroupPosition.Last)
    }
}

@PreviewLightDark
@Composable
internal fun CategoryRowMovingPreview() = LarderPreview {
    PreviewRow(PreviewCategory, LarderGroupPosition.Single, isMoving = true)
    PreviewRow(PreviewCategory, LarderGroupPosition.Single, isDragging = true)
}

@PreviewLightDark
@Composable
internal fun ShelfLifeDialogPreview() = LarderPreview {
    ShelfLifeDialog("Dairy", 7, {}, {})
}
