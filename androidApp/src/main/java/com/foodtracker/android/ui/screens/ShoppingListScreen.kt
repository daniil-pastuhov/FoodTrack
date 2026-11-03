package com.foodtracker.android.ui.screens

import android.content.Intent
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import com.foodtracker.model.ShoppingSection
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.android.util.generateQrBitmap
import com.foodtracker.share.ShoppingListShare
import com.foodtracker.viewmodel.ShoppingRow
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.foodtracker.android.util.categoryLabel

private const val MAX_NAME_SUGGESTIONS = 5

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(
    stateHolder: PantryStateHolder,
    onScanNewEntry: () -> Unit,
    onAddEntryManually: (ShoppingListEntry) -> Unit,
    onCheckOffEntry: (String) -> Unit = { stateHolder.deleteShoppingListEntry(it) },
    onDecrementEntry: (String) -> Unit = { stateHolder.decrementShoppingListQuantity(it) },
    /** Reports the outcome of an import so the host can surface it as a snackbar - this screen
     *  has no snackbar host of its own (see MainActivity). */
    onImportComplete: (added: Int, found: Int) -> Unit = { _, _ -> },
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

    var showShareSheet by remember { mutableStateOf(false) }
    var showQrCode by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showNewSectionDialog by remember { mutableStateOf(false) }
    var renamingSection by remember { mutableStateOf<ShoppingSection?>(null) }
    var deletingSection by remember { mutableStateOf<ShoppingSection?>(null) }
    // Hoisted above the empty/non-empty switch so the list keeps its scroll position.
    val lazyListState = rememberLazyListState()
    val shareTitle = stringResource(R.string.shopping_list_title)

    // Built from shoppingList rather than shoppingRows: a collapsed section's entries aren't
    // in the rows, but they are still on the list being shared.
    fun buildShareText(): String =
        ShoppingListShare.toText(
            entries = state.shoppingList,
            title = shareTitle,
            sections = state.shoppingSections,
            sectionLabel = { categoryLabel(context.resources, it.name) }
        )

    fun shareAsText() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildShareText())
        }
        context.startActivity(Intent.createChooser(sendIntent, null))
        showShareSheet = false
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
                actions = {
                    IconButton(onClick = { showNewSectionDialog = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = stringResource(R.string.shopping_section_new)
                        )
                    }
                    IconButton(onClick = { showShareSheet = true }) {
                        Icon(
                            Icons.Default.IosShare,
                            contentDescription = stringResource(R.string.shopping_list_share_content_description)
                        )
                    }
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
                ShoppingSectionedList(
                    state = state,
                    stateHolder = stateHolder,
                    lazyListState = lazyListState,
                    bottomOverlayClearance = bottomOverlayClearance,
                    onCheckOffEntry = onCheckOffEntry,
                    onDecrementEntry = onDecrementEntry,
                    onEntryClick = onAddEntryManually,
                    onRenameSection = { renamingSection = it },
                    onDeleteSection = { deletingSection = it }
                )
            }
            }
        }
    }

    if (showShareSheet) {
        val isEmpty = state.shoppingList.isEmpty()
        AlertDialog(
            onDismissRequest = { showShareSheet = false },
            title = { Text(stringResource(R.string.shopping_list_share_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.shopping_list_share_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // Sharing an empty list would hand someone a title and nothing else, so the
                    // two export actions are disabled rather than hidden - the user still sees
                    // that the feature exists.
                    ShareActionRow(
                        icon = Icons.Default.IosShare,
                        label = stringResource(R.string.shopping_list_share_as_text),
                        enabled = !isEmpty,
                        onClick = ::shareAsText
                    )
                    ShareActionRow(
                        icon = Icons.Default.QrCode2,
                        label = stringResource(R.string.shopping_list_share_as_qr),
                        enabled = !isEmpty,
                        onClick = {
                            showShareSheet = false
                            showQrCode = true
                        }
                    )
                    ShareActionRow(
                        icon = Icons.Default.ContentPaste,
                        label = stringResource(R.string.shopping_list_import),
                        enabled = true,
                        onClick = {
                            showShareSheet = false
                            showImportDialog = true
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showShareSheet = false }) {
                    Text(stringResource(R.string.share_recipe_cancel_button))
                }
            }
        )
    }

    if (showQrCode) {
        val payload = remember(state.shoppingList) {
            ShoppingListShare.toPayload(state.shoppingList, state.shoppingSections)
        }
        val qrBitmap = remember(payload) { generateQrBitmap(payload) }
        AlertDialog(
            onDismissRequest = { showQrCode = false },
            title = { Text(stringResource(R.string.shopping_list_share_as_qr)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.shopping_list_share_qr_content_description),
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.shopping_list_share_qr_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        // A QR code has a hard capacity limit and ZXing refuses rather than
                        // truncating, so a long list simply can't be shared this way. Text has
                        // no such limit, so point at it instead of showing a broken dialog.
                        Text(
                            text = stringResource(R.string.shopping_list_share_qr_too_large),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrCode = false }) {
                    Text(stringResource(R.string.share_recipe_cancel_button))
                }
            }
        )
    }

    if (showNewSectionDialog) {
        ShoppingSectionNameDialog(
            title = stringResource(R.string.shopping_section_new),
            initialName = "",
            onConfirm = { name ->
                stateHolder.createShoppingSection(name)
                showNewSectionDialog = false
            },
            onDismiss = { showNewSectionDialog = false }
        )
    }

    renamingSection?.let { section ->
        ShoppingSectionNameDialog(
            title = stringResource(R.string.shopping_section_rename),
            initialName = categoryLabel(section.name),
            onConfirm = { name ->
                stateHolder.renameShoppingSection(section.id, name)
                renamingSection = null
            },
            onDismiss = { renamingSection = null }
        )
    }

    deletingSection?.let { section ->
        AlertDialog(
            onDismissRequest = { deletingSection = null },
            title = { Text(stringResource(R.string.shopping_section_delete_title, categoryLabel(section.name))) },
            text = { Text(stringResource(R.string.shopping_section_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    stateHolder.deleteShoppingSection(section.id)
                    deletingSection = null
                }) {
                    Text(stringResource(R.string.shopping_section_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSection = null }) {
                    Text(stringResource(R.string.share_recipe_cancel_button))
                }
            }
        )
    }

    if (showImportDialog) {
        var importText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.shopping_list_import)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.shopping_list_import_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = { Text(stringResource(R.string.shopping_list_import_placeholder)) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = importText
                        showImportDialog = false
                        stateHolder.importShoppingList(text) { added, found ->
                            onImportComplete(added, found)
                        }
                    },
                    enabled = importText.isNotBlank()
                ) {
                    Text(stringResource(R.string.shopping_list_import_confirm), color = BrandPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.share_recipe_cancel_button))
                }
            }
        )
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShoppingListRow(
    entry: ShoppingListEntry,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onCheckOff: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Sections the entry can be moved to from its long-press menu (its own one excluded), as
     *  id to label. The non-drag way to move an entry, for anyone who can't or won't drag. */
    moveTargets: List<Pair<String, String>> = emptyList(),
    onMoveToSection: (String) -> Unit = {}
) {
    var showMoveMenu by remember { mutableStateOf(false) }
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
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (moveTargets.isEmpty()) null else ({ showMoveMenu = true }),
                    onLongClickLabel = stringResource(R.string.shopping_section_move_to)
                )
        ) {
            DropdownMenu(expanded = showMoveMenu, onDismissRequest = { showMoveMenu = false }) {
                Text(
                    text = stringResource(R.string.shopping_section_move_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
                moveTargets.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            showMoveMenu = false
                            onMoveToSection(id)
                        }
                    )
                }
            }
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
                    // Consumes the handle's touch-down after the drag detector has seen it, so
                    // the card around it doesn't also start its long-press timer and pop the
                    // "Move to section" menu in the middle of a drag.
                    modifier = Modifier
                        .pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                        }
                        .then(dragHandleModifier)
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

/** One action row in the share sheet: an icon, a label, and nothing else. Disabled rows are
 *  dimmed rather than removed so the user can see what sharing offers even on an empty list. */
@Composable
private fun ShareActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) BrandPrimary else TextSecondary.copy(alpha = 0.4f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else TextSecondary.copy(alpha = 0.4f)
        )
    }
}
