package com.foodtracker.android.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderStepper
import com.foodtracker.android.ui.design.LarderSwipeAction
import com.foodtracker.android.ui.design.LarderSwipeRow
import com.foodtracker.android.ui.design.LarderSwipeStyle
import com.foodtracker.android.ui.design.LarderTick
import com.foodtracker.android.ui.design.larderGroupItem
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.ui.theme.larderShadow
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import com.foodtracker.model.resolveShoppingSectionId
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryDerivation
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.PantryUiState
import com.foodtracker.viewmodel.ShoppingRow
import kotlinx.coroutines.delay
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** From shopping.css: a row is at least this tall, since a name may take two lines. */
private val ShoppingRowMinHeight = 64.dp

/** A full touch target, so the handle is as easy to catch as the stepper beside it. Move to
 *  section, and Move up and down on a section's menu, do the same jobs without a drag. */
private val ShoppingGripWidth = LarderSize.touch

/** From shopping.css: the chevron and grip are a size down from [LarderIcons]'s default. */
private val ShoppingSmallIcon = 18.dp

/** How long a ticked row shows its tick before it leaves. */
private const val TICK_HOLD_MILLIS = 350L

/** Where the row at [index] falls in the group of rows under its section header. */
internal fun shoppingGroupPosition(rows: List<ShoppingRow>, index: Int): LarderGroupPosition {
    val joinsAbove = rows.getOrNull(index - 1) is ShoppingRow.Item
    val joinsBelow = rows.getOrNull(index + 1) is ShoppingRow.Item
    return when {
        joinsAbove && joinsBelow -> LarderGroupPosition.Middle
        joinsAbove -> LarderGroupPosition.Last
        joinsBelow -> LarderGroupPosition.First
        else -> LarderGroupPosition.Single
    }
}

/** A header only needs space above it after a group of rows: folded sections sit close. */
internal fun shoppingHeaderHasGap(rows: List<ShoppingRow>, index: Int): Boolean =
    rows.getOrNull(index - 1) is ShoppingRow.Item

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
    onMoveEntry: (ShoppingListEntry, String) -> Unit,
    onRenameSection: (ShoppingSection) -> Unit,
    onDeleteSection: (ShoppingSection, Int) -> Unit
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

    val canMove = state.shoppingSections.size > 1
    val movableSectionIds = rows.filterIsInstance<ShoppingRow.Header>()
        .map { it.section }
        .filterNot { it.isDefault }
        .map { it.id }
    fun moveSection(sectionId: String, delta: Int) {
        val reordered = movedSectionOrder(movableSectionIds, sectionId, delta) ?: return
        stateHolder.reorderShoppingSections(reordered)
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        // Bottom padding clears the floating pill, which overlays this screen.
        contentPadding = PaddingValues(
            start = LarderSpace.s4,
            end = LarderSpace.s4,
            bottom = bottomOverlayClearance
        )
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
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
                            onDelete = { onDeleteSection(section, row.itemCount) },
                            onMoveUp = if (movedSectionOrder(movableSectionIds, section.id, -1) != null) {
                                { moveSection(section.id, -1) }
                            } else {
                                null
                            },
                            onMoveDown = if (movedSectionOrder(movableSectionIds, section.id, 1) != null) {
                                { moveSection(section.id, 1) }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .animateItem()
                                .padding(top = if (shoppingHeaderHasGap(rows, index)) LarderSpace.s2 else 0.dp)
                        )
                    }
                }
                is ShoppingRow.Item -> {
                    val entry = row.entry
                    ReorderableItem(reorderableState, key = row.key) { isDragging ->
                        ShoppingListRow(
                            entry = entry,
                            position = shoppingGroupPosition(rows, index),
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
                            onLongClick = if (canMove) ({ onMoveEntry(entry, row.sectionId) }) else null,
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

/** A row in its group: the swipe around [ShoppingRowContent], and the moment a tick stays on
 *  screen before the row goes. Both a tick and a swipe end in [onCheckOff]. */
@Composable
private fun ShoppingListRow(
    entry: ShoppingListEntry,
    position: LarderGroupPosition,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onCheckOff: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var ticked by remember { mutableStateOf(false) }
    val currentOnCheckOff by rememberUpdatedState(onCheckOff)
    LaunchedEffect(ticked) {
        if (ticked) {
            delay(TICK_HOLD_MILLIS)
            currentOnCheckOff()
        }
    }
    val swipe = LarderSwipeAction(
        icon = LarderIcons.Check,
        label = null,
        style = LarderSwipeStyle.Quiet,
        onSwipe = onCheckOff
    )
    val dragShape = RoundedCornerShape(LarderRadius.md)
    val frame = if (isDragging) {
        Modifier
            .larderShadow(LarderTheme.shadows.level3, dragShape)
            .clip(dragShape)
    } else {
        Modifier.larderGroupItem(position)
    }
    LarderSwipeRow(
        startAction = swipe,
        endAction = swipe,
        enabled = !isDragging && !ticked,
        modifier = modifier.then(frame)
    ) {
        ShoppingRowContent(
            entry = entry,
            ticked = ticked,
            isDragging = isDragging,
            dragHandleModifier = dragHandleModifier,
            onTick = { ticked = true },
            onIncrement = onIncrement,
            onDecrement = onDecrement,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }
}

/** Tick, name, quantity, handle. Tapping anywhere else opens Add item; a long press offers Move
 *  to section, which is also the way to move a row without dragging it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShoppingRowContent(
    entry: ShoppingListEntry,
    ticked: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onTick: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isDragging) scheme.surfaceContainerHigh else scheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.shopping_section_move_to)
            )
            .heightIn(min = ShoppingRowMinHeight)
            .padding(start = LarderSpace.s1, top = LarderSpace.s1, bottom = LarderSpace.s1)
    ) {
        LarderTick(
            checked = ticked,
            onCheckedChange = { if (!ticked) onTick() },
            contentDescription = stringResource(R.string.widget_action_mark_bought, entry.productName)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = LarderSpace.s1)
        ) {
            Text(
                text = entry.productName,
                style = MaterialTheme.typography.titleMedium,
                color = if (ticked) scheme.onSurfaceVariant else scheme.onSurface,
                textDecoration = if (ticked) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val barcode = entry.barcode
            if (!barcode.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.barcode_format, barcode),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // min 0: at quantity 1 the minus stays on and takes the row off, with Undo, like a tick.
        LarderStepper(
            value = entry.quantity,
            onValueChange = { if (it > entry.quantity) onIncrement() else onDecrement() },
            min = 0,
            decreaseDescription = stringResource(R.string.shopping_list_quantity_decrease_content_description),
            increaseDescription = stringResource(R.string.shopping_list_quantity_increase_content_description),
            outlined = false
        )
        ShoppingGrip(
            description = stringResource(R.string.shopping_list_drag_handle_content_description),
            dragHandleModifier = dragHandleModifier
        )
    }
}

@Composable
private fun ShoppingGrip(description: String, dragHandleModifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        // Consumes the handle's touch-down after the drag detector has seen it, so the row
        // around it doesn't also start its long-press timer and open Move to section mid-drag.
        modifier = Modifier
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            }
            .then(dragHandleModifier)
            .size(width = ShoppingGripWidth, height = LarderSize.touch)
    ) {
        LarderIcon(
            LarderIcons.GripVertical,
            contentDescription = description,
            size = ShoppingSmallIcon,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The toggle takes the free space, so the menu and handle stay pinned right however long the
 *  name is. The main list has neither. */
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
    modifier: Modifier = Modifier,
    /** Null when the section is already at that end. The drag's equivalent without a drag. */
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val chevronRotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "sectionChevron")
    val stateText = stringResource(if (collapsed) R.string.shopping_section_collapsed else R.string.shopping_section_expanded)
    val dragShape = RoundedCornerShape(LarderRadius.md)
    var showMenu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isDragging) {
                    Modifier
                        .larderShadow(LarderTheme.shadows.level3, dragShape)
                        .background(scheme.surfaceContainerHigh, dragShape)
                } else {
                    Modifier
                }
            )
            .heightIn(min = LarderSize.touch)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = LarderSize.touch)
                .clickable(enabled = toggleEnabled, role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = stateText }
        ) {
            LarderIcon(
                LarderIcons.ChevronDown,
                contentDescription = null,
                size = ShoppingSmallIcon,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = itemCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
        }
        if (!isDefault) {
            Box {
                LarderIconButton(
                    icon = LarderIcons.EllipsisVertical,
                    contentDescription = stringResource(R.string.shopping_section_options, label),
                    onClick = { showMenu = true }
                )
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    shape = RoundedCornerShape(LarderRadius.md),
                    containerColor = scheme.surfaceContainerHigh
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.shopping_section_rename),
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onSurface
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRename()
                        }
                    )
                    onMoveUp?.let { move ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.category_settings_move_up),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurface
                                )
                            },
                            onClick = {
                                showMenu = false
                                move()
                            }
                        )
                    }
                    onMoveDown?.let { move ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.category_settings_move_down),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurface
                                )
                            },
                            onClick = {
                                showMenu = false
                                move()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.shopping_section_delete),
                                style = MaterialTheme.typography.bodyLarge,
                                color = scheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
            ShoppingGrip(
                description = stringResource(R.string.shopping_section_drag_handle_content_description),
                dragHandleModifier = dragHandleModifier
            )
        }
    }
}

/** [ids] with [sectionId] moved [delta] places, or null when it's missing or already at that
 *  end, so the menu can leave the item out. */
internal fun movedSectionOrder(ids: List<String>, sectionId: String, delta: Int): List<String>? {
    val from = ids.indexOf(sectionId)
    if (from < 0 || delta == 0) return null
    val to = from + delta
    if (to !in ids.indices) return null
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

private fun previewEntry(name: String, quantity: Int = 1, barcode: String? = null) = ShoppingListEntry(
    id = name,
    productName = name,
    barcode = barcode,
    quantity = quantity,
    addedDate = DateUtils.today()
)

@Composable
private fun PreviewRow(entry: ShoppingListEntry, ticked: Boolean = false, isDragging: Boolean = false) {
    ShoppingRowContent(entry, ticked, isDragging, Modifier, {}, {}, {}, {}, null)
}

@PreviewLightDark
@Composable
private fun ShoppingRowPreview() = LarderPreview {
    LarderGroup {
        PreviewRow(previewEntry("Semi-skimmed milk", quantity = 2))
        PreviewRow(previewEntry("Greek yoghurt", quantity = 2, barcode = "5200435000027"))
        PreviewRow(previewEntry("Sourdough bread"), ticked = true)
        PreviewRow(previewEntry("Молоко ультрапастеризованное безлактозное 3,2%", quantity = 12))
        PreviewRow(previewEntry("Natural yoghurt"), isDragging = true)
    }
}

@PreviewLightDark
@Composable
private fun ShoppingSectionHeaderPreview() = LarderPreview {
    ShoppingSectionHeader("Shopping list", 2, false, false, true, true, Modifier, {}, {}, {})
    ShoppingSectionHeader("Dairy aisle", 2, false, false, false, true, Modifier, {}, {}, {})
    ShoppingSectionHeader("Household", 1, true, false, false, true, Modifier, {}, {}, {})
    ShoppingSectionHeader("Бытовая химия и товары для дома и кухни", 4, false, false, false, true, Modifier, {}, {}, {})
    ShoppingSectionHeader("Dairy aisle", 2, true, true, false, true, Modifier, {}, {}, {})
}
