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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.ShoppingRow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private const val MAX_NAME_SUGGESTIONS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    stateHolder: PantryStateHolder,
    onScanNewEntry: () -> Unit,
    onAddEntryManually: (ShoppingListEntry) -> Unit,
    onCheckOffEntry: (String) -> Unit = { stateHolder.deleteShoppingListEntry(it) },
    onDecrementEntry: (String) -> Unit = { stateHolder.decrementShoppingListQuantity(it) },
    modifier: Modifier = Modifier,
    // See PantryDashboardScreen's parameter of the same name.
    bottomOverlayClearance: Dp = 160.dp
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
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
        // Bottom nav-bar inset is already baked into bottomOverlayClearance (see MainActivity),
        // so the Scaffold must not reserve it a second time as innerPadding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
                // Flattened so drag reorder has a single index space to work with; headers are
                // never wrapped in a ReorderableItem, so they can't be dragged themselves, and a
                // move is only accepted (see reorderableState below) when both ends share a
                // resolved category (a disabled category, or two origins renamed to the same
                // name, fold into the same group here), which keeps drags from crossing into a
                // different group. Groups are ordered by the user's category preference order
                // (see CategorySettingsScreen), not alphabetically.
                // Grouping, category-name resolution and ordering are all derived once per
                // data change in PantryStateHolder. Done here they re-ran on every emission,
                // resolving each entry's category with a linear scan over every preference and
                // re-evaluating each group's order key inside the sort comparator.
                val flatRows = state.shoppingRows
                var localRows by remember { mutableStateOf(flatRows) }
                LaunchedEffect(flatRows) { localRows = flatRows }

                val lazyListState = rememberLazyListState()
                val haptics = LocalHapticFeedback.current
                val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                    val fromRow = localRows.getOrNull(from.index) as? ShoppingRow.Item
                    val toRow = localRows.getOrNull(to.index) as? ShoppingRow.Item
                    // Field comparison: the resolved name is carried on the row. This ran two
                    // linear preference scans per drag-move event, i.e. per frame of a drag.
                    val sameGroup = fromRow != null && toRow != null &&
                        fromRow.categoryDisplayName == toRow.categoryDisplayName
                    if (sameGroup) {
                        localRows = localRows.toMutableList().apply {
                            add(to.index, removeAt(from.index))
                        }
                    }
                }

                fun commitReorder(movedId: String) {
                    val index = localRows.indexOfFirst { it is ShoppingRow.Item && it.entry.id == movedId }
                    if (index == -1) return
                    val beforeId = (localRows.getOrNull(index - 1) as? ShoppingRow.Item)?.entry?.id
                    val afterId = (localRows.getOrNull(index + 1) as? ShoppingRow.Item)?.entry?.id
                    stateHolder.moveShoppingListEntry(movedId, beforeId, afterId)
                }

                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    // Bottom padding clears the floating pill nav bar and scan/add buttons
                    // that overlay this screen (see MainActivity).
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomOverlayClearance),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(localRows, key = { it.key }) { row ->
                        when (row) {
                            is ShoppingRow.Header -> {
                                // Bound to a local: ShoppingRow now lives in :shared, and a
                                // public property from another module can't be smart-cast.
                                val category = row.category
                                if (category != null) {
                                    Text(
                                        text = category,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary,
                                        modifier = Modifier
                                            .animateItem()
                                            .padding(top = 8.dp, bottom = 2.dp)
                                    )
                                } else {
                                    val isFirstRow = localRows.firstOrNull() == row
                                    if (!isFirstRow) {
                                        HorizontalDivider(
                                            modifier = Modifier
                                                .animateItem()
                                                .padding(vertical = 8.dp), thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                }
                            }
                            is ShoppingRow.Item -> {
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
                                        onClick = { onAddEntryManually(entry) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShoppingListRow(
    entry: ShoppingListEntry,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onCheckOff: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        label = "shoppingRowContainerColor"
    )
    // Swipe (either direction, since there's only the one action) checks the entry off the
    // list — mirrors ItemCard's swipe-to-dismiss pattern, replacing the row's old dedicated
    // check-off button. See ItemCard for why the state is a plain `remember` rather than
    // `rememberSwipeToDismissBoxState`: the saveable-backed factory restores a swiped-away
    // value under the lazy item key, so an undone entry came back stuck showing only the
    // swipe background.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionalThreshold
        )
    }
    // Stable identity, since SwipeToDismissBox keys its dismissal effect on this lambda.
    val currentOnCheckOff by rememberUpdatedState(onCheckOff)
    val onDismiss = remember { { _: SwipeToDismissBoxValue -> currentOnCheckOff() } }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        onDismiss = onDismiss,
        backgroundContent = { ShoppingListSwipeBackground() }
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .clickable(onClick = onClick)
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
            }
        }
    }
}

/** The red delete panel revealed behind a [ShoppingListRow] as it's swiped in either direction —
 *  checking an item off removes it from the list, same as [ItemCard]'s swipe-to-delete, so it
 *  gets the same destructive color. Unlike [ItemCard]'s two-directional background, there's only
 *  the one action here, so the same panel fills the whole row regardless of swipe direction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShoppingListSwipeBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.DeleteOutline,
            contentDescription = null,
            tint = Color.White
        )
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
