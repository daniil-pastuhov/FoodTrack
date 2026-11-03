package com.foodtracker.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import com.foodtracker.model.resolveShoppingSectionId
import com.foodtracker.viewmodel.PantryDerivation
import com.foodtracker.viewmodel.PantryUiState
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.ShoppingRow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What is being dragged right now, if anything. */
private sealed interface DragMode {
    data object None : DragMode

    /** An entry, picked up from [sourceSectionId]. Every other section is folded away for the
     *  duration, so the section headers are all close together and easy to drop onto. */
    data class Entry(val entryId: String, val sourceSectionId: String) : DragMode

    /** A section header. Every section is folded away, so only headers are being reordered. */
    data class Section(val sectionId: String) : DragMode
}

/**
 * The shopping list as collapsible, reorderable sections.
 *
 * Three row sources, highest priority first:
 *  - `dragRows`: the list as it is while a drag is in progress. Built when the drag starts with
 *    the other sections folded away (never persisted - a section's stored `collapsed` flag is
 *    untouched), then rearranged locally on every move.
 *  - `optimisticRows`: the result of the drop, shown until the database echoes it back, so the
 *    row doesn't flick back to its old place for the frame or two that takes.
 *  - `state.shoppingRows`: the persisted list.
 *
 * Headers are `ReorderableItem`s too, which is what lets an entry be moved across them - the
 * section an entry ends up in is simply the nearest header above it.
 */
@Composable
internal fun ShoppingSectionedList(
    state: PantryUiState,
    stateHolder: PantryStateHolder,
    lazyListState: LazyListState,
    bottomOverlayClearance: Dp,
    onCheckOffEntry: (String) -> Unit,
    onDecrementEntry: (String) -> Unit,
    onEntryClick: (ShoppingListEntry) -> Unit,
    onRenameSection: (ShoppingSection) -> Unit,
    onDeleteSection: (ShoppingSection) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val resources = LocalContext.current.resources
    val defaultLabel = stringResource(R.string.shopping_section_default)
    fun labelOf(section: ShoppingSection): String =
        if (section.isDefault) defaultLabel else categoryLabel(resources, section.name)

    // The drag callbacks below are captured once by the drag-handle gesture detector and not
    // refreshed on recomposition, so everything they read has to come through State: a plain
    // `state` captured there was the list as it was when the row first appeared.
    val latestState by rememberUpdatedState(state)
    var dragMode by remember { mutableStateOf<DragMode>(DragMode.None) }
    var dragRows by remember { mutableStateOf<List<ShoppingRow>?>(null) }
    var optimisticRows by remember { mutableStateOf<List<ShoppingRow>?>(null) }
    LaunchedEffect(state.shoppingRows) { optimisticRows = null }
    val rows = dragRows ?: optimisticRows ?: state.shoppingRows

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val current = dragRows ?: return@rememberReorderableLazyListState
        val fromRow = current.getOrNull(from.index) ?: return@rememberReorderableLazyListState
        val toRow = current.getOrNull(to.index) ?: return@rememberReorderableLazyListState
        // The default section's header is pinned first; nothing may go above it.
        if (to.index == 0) return@rememberReorderableLazyListState
        val allowed = when (dragMode) {
            is DragMode.Entry -> fromRow is ShoppingRow.Item
            is DragMode.Section -> fromRow is ShoppingRow.Header && toRow is ShoppingRow.Header
            DragMode.None -> false
        }
        if (allowed) {
            dragRows = current.toMutableList().apply { add(to.index, removeAt(from.index)) }
        }
    }

    /** Swaps in [newRows] while keeping the row [key] at the same spot on screen. Folding
     *  sections away above the dragged row would otherwise yank it (and the list) upward. */
    fun keepInPlace(key: String, newRows: List<ShoppingRow>) {
        val info = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val newIndex = newRows.indexOfFirst { it.key == key }
        if (newIndex >= 0 && newIndex != info.index) lazyListState.requestScrollToItem(newIndex, -info.offset)
    }

    fun startEntryDrag(entryId: String) {
        val ids = latestState.shoppingSections.mapTo(HashSet()) { it.id }
        val sourceSectionId = resolveShoppingSectionId(
            latestState.shoppingList.find { it.id == entryId }?.sectionId,
            ids
        )
        val folded = PantryDerivation.buildShoppingRows(latestState.shoppingList, latestState.shoppingSections) {
            it.id != sourceSectionId
        }
        keepInPlace(entryId, folded)
        dragMode = DragMode.Entry(entryId, sourceSectionId)
        dragRows = folded
    }

    fun finishEntryDrag() {
        val mode = dragMode as? DragMode.Entry
        val current = dragRows
        dragMode = DragMode.None
        dragRows = null
        if (mode == null || current == null) return
        val index = current.indexOfFirst { it.key == mode.entryId }
        val moved = latestState.shoppingList.find { it.id == mode.entryId }
        val targetSectionId = current.subList(0, index.coerceAtLeast(0))
            .lastOrNull { it is ShoppingRow.Header }
            ?.let { (it as ShoppingRow.Header).section.id }
        if (index < 0 || moved == null || targetSectionId == null) return

        val sectionIds = latestState.shoppingSections.mapTo(HashSet()) { it.id }
        val beforeId: String?
        val afterId: String?
        if (targetSectionId == mode.sourceSectionId) {
            // The source section stayed open, so the neighbours on screen are the real ones.
            beforeId = (current.getOrNull(index - 1) as? ShoppingRow.Item)?.entry?.id
            afterId = (current.getOrNull(index + 1) as? ShoppingRow.Item)?.entry?.id
        } else {
            // Dropped into a folded section: there's no position within it to read off the
            // screen, so it goes to the end.
            beforeId = latestState.shoppingList.lastOrNull {
                it.id != moved.id && resolveShoppingSectionId(it.sectionId, sectionIds) == targetSectionId
            }?.id
            afterId = null
        }

        val target = latestState.shoppingSections.find { it.id == targetSectionId }
        val expandTarget = target != null && target.collapsed && targetSectionId != mode.sourceSectionId
        if (expandTarget) stateHolder.setShoppingSectionCollapsed(targetSectionId, false)
        stateHolder.moveShoppingListEntry(moved.id, targetSectionId, beforeId, afterId)

        // The same move applied locally, for the frames until the database echoes it back.
        val movedLocally = moved.copy(sectionId = targetSectionId)
        val others = latestState.shoppingList.filterNot { it.id == moved.id }
        val insertAt = when {
            beforeId != null -> others.indexOfFirst { it.id == beforeId } + 1
            afterId != null -> others.indexOfFirst { it.id == afterId }.coerceAtLeast(0)
            else -> others.size
        }
        val entries = others.toMutableList().apply { add(insertAt, movedLocally) }
        val sections = latestState.shoppingSections.map {
            if (expandTarget && it.id == targetSectionId) it.copy(collapsed = false) else it
        }
        val result = PantryDerivation.buildShoppingRows(entries, sections)
        keepInPlace(moved.id, result)
        optimisticRows = result
    }

    fun startSectionDrag(sectionId: String) {
        val folded = PantryDerivation.buildShoppingRows(latestState.shoppingList, latestState.shoppingSections) { true }
        keepInPlace(ShoppingRow.headerKey(sectionId), folded)
        dragMode = DragMode.Section(sectionId)
        dragRows = folded
    }

    fun finishSectionDrag() {
        val mode = dragMode as? DragMode.Section
        val current = dragRows
        dragMode = DragMode.None
        dragRows = null
        if (mode == null || current == null) return
        val orderedIds = current.filterIsInstance<ShoppingRow.Header>().map { it.section.id }
        stateHolder.reorderShoppingSections(orderedIds)
        val byId = latestState.shoppingSections.associateBy { it.id }
        val reordered = orderedIds.mapIndexedNotNull { index, id ->
            byId[id]?.let { if (it.isDefault) it else it.copy(sortOrder = index.toDouble()) }
        }
        val result = PantryDerivation.buildShoppingRows(latestState.shoppingList, reordered)
        keepInPlace(ShoppingRow.headerKey(mode.sectionId), result)
        optimisticRows = result
    }

    val sectionLabels = state.shoppingSections.map { it.id to labelOf(it) }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        // Bottom padding clears the floating pill nav bar and scan/add buttons that overlay
        // this screen (see MainActivity).
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomOverlayClearance),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ShoppingRow.Header -> {
                    val section = row.section
                    val mode = dragMode
                    val displayCollapsed = when (mode) {
                        DragMode.None -> section.collapsed
                        is DragMode.Entry -> section.id != mode.sourceSectionId
                        is DragMode.Section -> true
                    }
                    ReorderableItem(reorderableState, key = row.key) { isDragging ->
                        ShoppingSectionHeader(
                            label = labelOf(section),
                            itemCount = row.itemCount,
                            collapsed = displayCollapsed,
                            isDragging = isDragging,
                            isDefault = section.isDefault,
                            toggleEnabled = mode == DragMode.None,
                            dragHandleModifier = Modifier.draggableHandle(
                                enabled = !section.isDefault,
                                onDragStarted = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    startSectionDrag(section.id)
                                },
                                onDragStopped = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    finishSectionDrag()
                                }
                            ),
                            onToggle = { stateHolder.setShoppingSectionCollapsed(section.id, !section.collapsed) },
                            onRename = { onRenameSection(section) },
                            onDelete = { onDeleteSection(section) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
                is ShoppingRow.Item -> {
                    val entry = row.entry
                    ReorderableItem(reorderableState, key = row.key) { isDragging ->
                        ShoppingListRow(
                            entry = entry,
                            isDragging = isDragging,
                            dragHandleModifier = Modifier.draggableHandle(
                                onDragStarted = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    startEntryDrag(entry.id)
                                },
                                onDragStopped = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    finishEntryDrag()
                                }
                            ),
                            onCheckOff = { onCheckOffEntry(entry.id) },
                            onIncrement = { stateHolder.incrementShoppingListQuantity(entry.id) },
                            onDecrement = { onDecrementEntry(entry.id) },
                            onClick = { onEntryClick(entry) },
                            moveTargets = sectionLabels.filter { it.first != row.sectionId },
                            onMoveToSection = { stateHolder.moveShoppingListEntryToSection(entry.id, it) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
    }
}

@Composable
private fun ShoppingSectionHeader(
    label: String,
    itemCount: Int,
    collapsed: Boolean,
    isDragging: Boolean,
    isDefault: Boolean,
    toggleEnabled: Boolean,
    dragHandleModifier: Modifier,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chevronRotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "sectionChevron")
    val background by animateColorAsState(
        if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        label = "sectionHeaderBackground"
    )
    val stateText = stringResource(if (collapsed) R.string.shopping_section_collapsed else R.string.shopping_section_expanded)
    var showMenu by remember { mutableStateOf(false) }
    Surface(color = background, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = toggleEnabled, role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = stateText }
                .padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .rotate(chevronRotation)
            )
            Spacer(Modifier.size(6.dp))
            // Label and count share the free space, so the menu and drag handle stay pinned to
            // the right edge however long the name is.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = itemCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }
            if (!isDefault) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.shopping_section_options, label),
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shopping_section_rename)) },
                            onClick = {
                                showMenu = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.shopping_section_delete), color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.shopping_section_drag_handle_content_description),
                    tint = TextSecondary,
                    modifier = dragHandleModifier
                        .padding(horizontal = 8.dp)
                        .size(20.dp)
                )
            }
        }
    }
}

/** Name prompt for creating or renaming a section. */
@Composable
internal fun ShoppingSectionNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(stringResource(R.string.shopping_section_name_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = value.text.isNotBlank()) {
                Text(stringResource(R.string.shopping_section_save), color = BrandPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.share_recipe_cancel_button))
            }
        }
    )
}
