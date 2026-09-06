package com.foodtracker.android.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.viewmodel.PantryStateHolder
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val MAX_NAME_SUGGESTIONS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    stateHolder: PantryStateHolder,
    onScanEntry: (ShoppingListEntry) -> Unit,
    onScanNewEntry: () -> Unit,
    onCheckOffEntry: (String) -> Unit = { stateHolder.deleteShoppingListEntry(it) },
    onDecrementEntry: (String) -> Unit = { stateHolder.decrementShoppingListQuantity(it) },
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsState()
    var newEntryText by remember { mutableStateOf("") }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun submitNewEntry() {
        if (newEntryText.isNotBlank()) {
            stateHolder.addManualShoppingListEntry(newEntryText)
            newEntryText = ""
        }
    }

    // Nothing else to look at on an empty list, so put the cursor straight into the input.
    LaunchedEffect(state.shoppingList.isEmpty()) {
        if (state.shoppingList.isEmpty()) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val suggestions = remember(newEntryText, state.knownProductNames) {
        if (newEntryText.isBlank()) {
            emptyList()
        } else {
            state.knownProductNames.filter {
                it.contains(newEntryText, ignoreCase = true) && !it.equals(newEntryText, ignoreCase = true)
            }.take(MAX_NAME_SUGGESTIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.shopping_list_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                OutlinedTextField(
                    value = newEntryText,
                    onValueChange = { newEntryText = it },
                    placeholder = { Text(stringResource(R.string.shopping_list_add_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitNewEntry() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(inputFocusRequester)
                )
                // A blank field opens the scanner (the fast path most of the time); once the
                // user starts typing, the same button submits that text instead.
                IconButton(onClick = { if (newEntryText.isBlank()) onScanNewEntry() else submitNewEntry() }) {
                    Icon(
                        imageVector = if (newEntryText.isBlank()) Icons.Default.QrCodeScanner else Icons.Default.Add,
                        contentDescription = stringResource(
                            if (newEntryText.isBlank()) R.string.shopping_list_scan_content_description
                            else R.string.shopping_list_add_button
                        ),
                        tint = BrandPrimary
                    )
                }
            }

            AnimatedVisibility(
                visible = suggestions.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    items(suggestions, key = { it }) { suggestion ->
                        SuggestionChip(
                            onClick = {
                                stateHolder.addManualShoppingListEntry(suggestion)
                                newEntryText = ""
                            },
                            label = { Text(suggestion) },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = state.shoppingList.isEmpty(),
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.fillMaxSize(),
                label = "shoppingListContent"
            ) { isEmpty ->
            if (isEmpty) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.ShoppingCart,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.4f),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        Text(
                            text = stringResource(R.string.shopping_list_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Text(
                            text = stringResource(R.string.shopping_list_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            } else {
                val uncategorizedLabel = stringResource(R.string.shopping_list_uncategorized)
                // Flattened so drag reorder has a single index space to work with; headers are
                // never wrapped in a ReorderableItem, so they can't be dragged themselves, and a
                // move is only accepted (see reorderableState below) when both ends share a
                // category, which keeps drags from crossing into a different group.
                val flatRows = remember(state.shoppingList) {
                    val grouped = state.shoppingList.groupBy { it.category }.entries.sortedBy { it.key ?: "￿" }
                    buildList {
                        grouped.forEach { (category, entries) ->
                            add(ShoppingListFlatRow.Header(category))
                            entries.forEach { add(ShoppingListFlatRow.Item(it)) }
                        }
                    }
                }
                var localRows by remember { mutableStateOf(flatRows) }
                LaunchedEffect(flatRows) { localRows = flatRows }

                val lazyListState = rememberLazyListState()
                val haptics = LocalHapticFeedback.current
                val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                    val fromRow = localRows.getOrNull(from.index) as? ShoppingListFlatRow.Item
                    val toRow = localRows.getOrNull(to.index) as? ShoppingListFlatRow.Item
                    if (fromRow != null && toRow != null && fromRow.entry.category == toRow.entry.category) {
                        localRows = localRows.toMutableList().apply {
                            add(to.index, removeAt(from.index))
                        }
                    }
                }

                fun commitReorder(movedId: String) {
                    val index = localRows.indexOfFirst { it is ShoppingListFlatRow.Item && it.entry.id == movedId }
                    if (index == -1) return
                    val beforeId = (localRows.getOrNull(index - 1) as? ShoppingListFlatRow.Item)?.entry?.id
                    val afterId = (localRows.getOrNull(index + 1) as? ShoppingListFlatRow.Item)?.entry?.id
                    stateHolder.moveShoppingListEntry(movedId, beforeId, afterId)
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    // Bottom padding clears the floating pill nav bar and scan/add buttons
                    // that overlay this screen (see MainActivity).
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(localRows, key = { row ->
                        when (row) {
                            is ShoppingListFlatRow.Header -> "header_${row.category ?: "uncategorized"}"
                            is ShoppingListFlatRow.Item -> row.entry.id
                        }
                    }) { row ->
                        when (row) {
                            is ShoppingListFlatRow.Header -> {
                                Text(
                                    text = row.category ?: uncategorizedLabel,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = TextSecondary,
                                    modifier = Modifier
                                        .animateItem()
                                        .padding(top = 8.dp, bottom = 2.dp)
                                )
                            }
                            is ShoppingListFlatRow.Item -> {
                                val entry = row.entry
                                ReorderableItem(reorderableState, key = entry.id) { isDragging ->
                                    ShoppingListRow(
                                        entry = entry,
                                        isDragging = isDragging,
                                        dragHandleModifier = Modifier.draggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                commitReorder(entry.id)
                                            }
                                        ),
                                        onCheckOff = { onCheckOffEntry(entry.id) },
                                        onIncrement = { stateHolder.incrementShoppingListQuantity(entry.id) },
                                        onDecrement = { onDecrementEntry(entry.id) },
                                        onScan = { onScanEntry(entry) },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }
            }
        }
    }
}

/** A row in the flattened, groupable-by-category shopping list rendered by [ShoppingListScreen]:
 *  either a category header (never draggable) or a shopping list item (draggable within its
 *  own category group only). */
private sealed class ShoppingListFlatRow {
    data class Header(val category: String?) : ShoppingListFlatRow()
    data class Item(val entry: ShoppingListEntry) : ShoppingListFlatRow()
}

@Composable
private fun ShoppingListRow(
    entry: ShoppingListEntry,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onCheckOff: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        label = "shoppingRowContainerColor"
    )
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.shopping_list_drag_handle_content_description),
                tint = TextSecondary,
                modifier = dragHandleModifier
                    .padding(end = 8.dp)
                    .size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.productName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val barcode = entry.barcode
                if (!barcode.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.barcode_format, barcode),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
            IconButton(onClick = onScan, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = stringResource(R.string.shopping_list_scan_content_description),
                    tint = BrandPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDecrement, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(R.string.shopping_list_quantity_decrease_content_description),
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedContent(
                targetState = entry.quantity,
                transitionSpec = {
                    if (targetState > initialState) {
                        (fadeIn() + slideInVertically { it }) togetherWith (fadeOut() + slideOutVertically { -it })
                    } else {
                        (fadeIn() + slideInVertically { -it }) togetherWith (fadeOut() + slideOutVertically { it })
                    }
                },
                modifier = Modifier.width(20.dp),
                label = "shoppingRowQuantity"
            ) { quantity ->
                Text(
                    text = quantity.toString(),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            IconButton(onClick = onIncrement, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.shopping_list_quantity_increase_content_description),
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onCheckOff) {
                Icon(
                    imageVector = Icons.Default.CheckCircleOutline,
                    contentDescription = stringResource(R.string.shopping_list_check_off_content_description),
                    tint = BrandPrimary
                )
            }
        }
    }
}

/** Shown after a generic shopping-list scan resolves to one or more similarly-named entries
 *  (rather than an exact barcode match, which skips this dialog) so the user can confirm which
 *  list item they're buying, or fall through to adding a brand-new pantry item instead. */
@Composable
fun ShoppingListMatchDialog(
    candidates: List<ShoppingListEntry>,
    onCandidateSelected: (ShoppingListEntry) -> Unit,
    onNoneOfThese: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shopping_list_did_you_mean_title)) },
        text = {
            Column {
                candidates.forEach { candidate ->
                    TextButton(
                        onClick = { onCandidateSelected(candidate) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${candidate.productName} (${candidate.quantity})",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNoneOfThese) {
                Text(stringResource(R.string.shopping_list_did_you_mean_none_of_these))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.shopping_list_did_you_mean_cancel))
            }
        }
    )
}
