package com.foodtracker.android.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderArt
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderChip
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderTextField
import com.foodtracker.android.ui.design.LedeHeader
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.theme.LarderLightRoles
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.generateQrBitmap
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.ShoppingSection
import com.foodtracker.share.ShoppingListShare
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.PantryUiState
import com.foodtracker.viewmodel.ShoppingRow
import kotlinx.coroutines.launch

private const val MAX_NAME_SUGGESTIONS = 5

/** From shopping.css: the list and add field's pane on tablets. */
private val ShoppingSidePaneWidth = 300.dp

/** From shopping.css: the filled disc behind Add, inside the field's 48dp target. */
private val ShoppingAddDiscSize = 36.dp

/** From shopping.css: big enough for a phone to read the code from a table. */
private val ShoppingQrSize = 240.dp

/** What sits over the list right now. One at a time, so going from Share to QR is one change. */
private sealed interface ShoppingOverlay {
    data object Share : ShoppingOverlay
    data object Qr : ShoppingOverlay
    data object Import : ShoppingOverlay
    data object NewSection : ShoppingOverlay
    data class Rename(val section: ShoppingSection) : ShoppingOverlay
    data class Delete(val section: ShoppingSection, val itemCount: Int) : ShoppingOverlay
    data class Move(val entry: ShoppingListEntry, val sectionId: String) : ShoppingOverlay
}

@Composable
fun ShoppingListScreen(
    stateHolder: PantryStateHolder,
    onScanNewEntry: () -> Unit,
    onAddEntryManually: (ShoppingListEntry) -> Unit,
    onCheckOffEntry: (String) -> Unit = { stateHolder.deleteShoppingListEntry(it) },
    onDecrementEntry: (String) -> Unit = { stateHolder.decrementShoppingListQuantity(it) },
    /** Reports the outcome of an import, so the host can show it with the scanner's imports. */
    onImportComplete: (added: Int, found: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    // See PantryDashboardScreen's parameter of the same name.
    bottomOverlayClearance: Dp = 160.dp
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = LocalLarderSnackbars.current
    val expanded = isExpanded()
    var newEntryText by remember { mutableStateOf("") }
    var overlay by remember { mutableStateOf<ShoppingOverlay?>(null) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // Hoisted above the empty/non-empty switch so the list keeps its scroll position.
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.shopping_list_title)
    val isEmpty = state.shoppingList.isEmpty()

    fun addEntry(name: String) {
        if (name.isBlank()) return
        stateHolder.addManualShoppingListEntry(name) { existingName ->
            snackbars.show(context.getString(R.string.shopping_list_already_listed, existingName), null, null)
        }
        newEntryText = ""
    }

    // Nothing else to look at on an empty list, so put the cursor straight into the input.
    LaunchedEffect(isEmpty) {
        if (isEmpty) {
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Built from shoppingList rather than shoppingRows: a collapsed section's entries aren't
    // in the rows, but they are still on the list being shared.
    fun shareAsText() {
        val text = ShoppingListShare.toText(
            entries = state.shoppingList,
            title = shareTitle,
            sections = state.shoppingSections,
            sectionLabel = { categoryLabel(context.resources, it.name) }
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(sendIntent, null))
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

    val header: @Composable () -> Unit = {
        ShoppingHeader(
            entryCount = state.shoppingList.size,
            showNewSection = !expanded,
            onNewSection = { overlay = ShoppingOverlay.NewSection },
            onShare = { overlay = ShoppingOverlay.Share }
        ) {
            ShoppingAddField(
                text = newEntryText,
                onTextChange = { newEntryText = it },
                onAdd = { addEntry(newEntryText) },
                onScan = onScanNewEntry,
                focusRequester = inputFocusRequester
            )
            ShoppingSuggestions(
                suggestions = suggestions,
                wrap = expanded,
                onPick = { addEntry(it) }
            )
        }
    }

    val list: @Composable (Modifier) -> Unit = { paneModifier ->
        AnimatedContent(
            targetState = isEmpty,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            modifier = paneModifier,
            label = "shoppingListContent"
        ) { empty ->
            if (empty) {
                EmptyState(
                    title = stringResource(R.string.shopping_list_empty_title),
                    message = stringResource(R.string.shopping_list_empty_subtitle),
                    art = LarderArt.Basket,
                    modifier = Modifier.padding(bottom = bottomOverlayClearance)
                )
            } else {
                ShoppingSectionedList(
                    state = state,
                    stateHolder = stateHolder,
                    lazyListState = lazyListState,
                    bottomOverlayClearance = bottomOverlayClearance,
                    onCheckOffEntry = onCheckOffEntry,
                    onDecrementEntry = onDecrementEntry,
                    onEntryClick = onAddEntryManually,
                    onMoveEntry = { entry, sectionId -> overlay = ShoppingOverlay.Move(entry, sectionId) },
                    onRenameSection = { overlay = ShoppingOverlay.Rename(it) },
                    onDeleteSection = { section, count -> overlay = ShoppingOverlay.Delete(section, count) }
                )
            }
        }
    }

    val page = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
    if (expanded) {
        Row(modifier = page) {
            Column(
                modifier = Modifier
                    .width(ShoppingSidePaneWidth)
                    .fillMaxHeight()
            ) {
                header()
                if (!isEmpty) {
                    ShoppingSectionIndex(
                        state = state,
                        bottomClearance = bottomOverlayClearance,
                        onSectionClick = { key -> scope.launch { lazyListState.scrollToRow(state, key) } },
                        onNewSection = { overlay = ShoppingOverlay.NewSection },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            list(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = LarderSpace.s3)
            )
        }
    } else {
        Column(modifier = page) {
            header()
            list(Modifier.weight(1f))
        }
    }

    when (val current = overlay) {
        null -> Unit
        ShoppingOverlay.Share -> ShoppingShareSheet(
            isEmpty = isEmpty,
            onShareAsText = {
                overlay = null
                shareAsText()
            },
            onShowQr = { overlay = ShoppingOverlay.Qr },
            onImport = { overlay = ShoppingOverlay.Import },
            onDismiss = { overlay = null }
        )
        ShoppingOverlay.Qr -> ShoppingQrSheet(
            state = state,
            onShareAsText = {
                overlay = null
                shareAsText()
            },
            onDismiss = { overlay = null }
        )
        ShoppingOverlay.Import -> ShoppingImportSheet(
            onImport = { text ->
                overlay = null
                stateHolder.importShoppingList(text, onImportComplete)
            },
            onDismiss = { overlay = null }
        )
        ShoppingOverlay.NewSection -> ShoppingSectionNameDialog(
            title = stringResource(R.string.shopping_section_new),
            initialName = "",
            onConfirm = { name ->
                stateHolder.createShoppingSection(name)
                overlay = null
            },
            onDismiss = { overlay = null }
        )
        is ShoppingOverlay.Rename -> ShoppingSectionNameDialog(
            title = stringResource(R.string.shopping_section_rename),
            initialName = categoryLabel(current.section.name),
            onConfirm = { name ->
                stateHolder.renameShoppingSection(current.section.id, name)
                overlay = null
            },
            onDismiss = { overlay = null }
        )
        is ShoppingOverlay.Delete -> LarderDialog(
            title = stringResource(R.string.shopping_section_delete_title, categoryLabel(current.section.name)),
            text = if (current.itemCount > 0) {
                pluralStringResource(R.plurals.shopping_section_delete_count, current.itemCount, current.itemCount)
            } else {
                null
            },
            confirmLabel = stringResource(R.string.shopping_section_delete_confirm),
            onConfirm = {
                stateHolder.deleteShoppingSection(current.section.id)
                overlay = null
            },
            dismissLabel = stringResource(R.string.shopping_section_keep),
            onDismissRequest = { overlay = null },
            destructive = true
        )
        is ShoppingOverlay.Move -> ShoppingMoveSheet(
            entryName = current.entry.productName,
            targets = state.shoppingSections.filter { it.id != current.sectionId },
            onMove = { target ->
                stateHolder.moveShoppingListEntryToSection(current.entry.id, target.id)
                overlay = null
            },
            onDismiss = { overlay = null }
        )
    }
}

private suspend fun LazyListState.scrollToRow(state: PantryUiState, key: String) {
    val index = state.shoppingRows.indexOfFirst { it.key == key }
    if (index >= 0) animateScrollToItem(index)
}

/** The brand bar, the count, and [below], which is the add field. */
@Composable
private fun ShoppingHeader(
    entryCount: Int,
    showNewSection: Boolean,
    onNewSection: () -> Unit,
    onShare: () -> Unit,
    below: @Composable ColumnScope.() -> Unit
) {
    LedeHeader(
        lede = if (entryCount == 0) {
            stringResource(R.string.shopping_list_lede_empty)
        } else {
            pluralStringResource(R.plurals.shopping_list_lede, entryCount, entryCount)
        },
        brand = stringResource(R.string.shopping_list_brand),
        actions = {
            if (showNewSection) {
                LarderIconButton(
                    icon = LarderIcons.ListPlus,
                    contentDescription = stringResource(R.string.shopping_section_new),
                    onClick = onNewSection
                )
            }
            LarderIconButton(
                icon = LarderIcons.Share2,
                contentDescription = stringResource(R.string.shopping_list_share_content_description),
                onClick = onShare
            )
        },
        below = below
    )
}

/** A blank field offers the scanner, the fast path most of the time; once there's text, the
 *  same spot adds it. */
@Composable
private fun ShoppingAddField(
    text: String,
    onTextChange: (String) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    LarderTextField(
        value = text,
        onValueChange = onTextChange,
        label = null,
        placeholder = stringResource(R.string.shopping_list_add_placeholder),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        trailing = {
            if (text.isBlank()) {
                LarderIconButton(
                    icon = LarderIcons.ScanBarcode,
                    contentDescription = stringResource(R.string.shopping_list_scan_content_description),
                    onClick = onScan,
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(ShoppingAddDiscSize)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    LarderIconButton(
                        icon = LarderIcons.Plus,
                        contentDescription = stringResource(R.string.shopping_list_add_button),
                        onClick = onAdd,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    )
}

/** Names used before: a scrolling row on phones, wrapped in the narrow pane on tablets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoppingSuggestions(suggestions: List<String>, wrap: Boolean, onPick: (String) -> Unit) {
    AnimatedVisibility(
        visible = suggestions.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        if (wrap) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                modifier = Modifier.padding(top = LarderSpace.s1)
            ) {
                suggestions.forEach { name ->
                    LarderChip(label = name, selected = false, onClick = { onPick(name) })
                }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                contentPadding = PaddingValues(vertical = LarderSpace.s1),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(suggestions, key = { it }) { name ->
                    LarderChip(
                        label = name,
                        selected = false,
                        onClick = { onPick(name) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

/** Tablets only: every section with its count, beside the list. A tap brings it into view. */
@Composable
private fun ShoppingSectionIndex(
    state: PantryUiState,
    bottomClearance: Dp,
    onSectionClick: (key: String) -> Unit,
    onNewSection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val headers = state.shoppingRows.filterIsInstance<ShoppingRow.Header>()
    val defaultLabel = stringResource(R.string.shopping_section_default)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = LarderSpace.s4, end = LarderSpace.s4, bottom = bottomClearance)
    ) {
        SectionHeader(stringResource(R.string.shopping_sections_label), first = true)
        LarderGroup {
            headers.forEachIndexed { index, header ->
                if (index > 0) LarderDivider()
                LarderListItem(
                    title = if (header.section.isDefault) defaultLabel else categoryLabel(header.section.name),
                    onClick = { onSectionClick(header.key) },
                    trailing = {
                        Text(
                            text = header.itemCount.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }
        LarderButton(
            text = stringResource(R.string.shopping_section_new),
            onClick = onNewSection,
            style = LarderButtonStyle.Text,
            leadingIcon = LarderIcons.Plus,
            modifier = Modifier.padding(top = LarderSpace.s2)
        )
    }
}

/** A [LarderSheet] that slides away before [content]'s `close` runs what comes next. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShoppingSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(close: (after: () -> Unit) -> Unit) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: (() -> Unit) -> Unit = { after ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }
    LarderSheet(onDismissRequest = onDismiss, title = title, sheetState = sheetState) {
        content(close)
    }
}

@Composable
private fun SheetLead(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = LarderSpace.s3)
    )
}

@Composable
private fun SheetActions(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.End),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LarderSpace.s4),
        content = content
    )
}

/** One action in a sheet. A disabled row dims rather than leaves, so people can still see what
 *  sharing offers on an empty list. */
@Composable
private fun ShoppingSheetAction(
    icon: LarderIcons,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurface.copy(alpha = 0.38f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LarderSize.touch)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = LarderSpace.s1, vertical = LarderSpace.s2)
    ) {
        LarderIcon(icon, contentDescription = null, tint = if (enabled) scheme.primary else muted)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) scheme.onSurface else muted,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ShoppingShareActions(
    isEmpty: Boolean,
    onShareAsText: () -> Unit,
    onShowQr: () -> Unit,
    onImport: () -> Unit
) {
    SheetLead(stringResource(R.string.shopping_list_share_dialog_message))
    ShoppingSheetAction(
        icon = LarderIcons.Share2,
        label = stringResource(R.string.shopping_list_share_as_text),
        onClick = onShareAsText,
        enabled = !isEmpty
    )
    ShoppingSheetAction(
        icon = LarderIcons.QrCode,
        label = stringResource(R.string.shopping_list_share_as_qr),
        onClick = onShowQr,
        enabled = !isEmpty
    )
    ShoppingSheetAction(
        icon = LarderIcons.Download,
        label = stringResource(R.string.shopping_list_import),
        onClick = onImport
    )
}

@Composable
private fun ShoppingShareSheet(
    isEmpty: Boolean,
    onShareAsText: () -> Unit,
    onShowQr: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    ShoppingSheet(stringResource(R.string.shopping_list_share_dialog_title), onDismiss) { close ->
        ShoppingShareActions(
            isEmpty = isEmpty,
            onShareAsText = { close(onShareAsText) },
            onShowQr = { close(onShowQr) },
            onImport = { close(onImport) }
        )
        SheetActions {
            LarderButton(
                text = stringResource(R.string.share_recipe_cancel_button),
                onClick = { close(onDismiss) },
                style = LarderButtonStyle.Text
            )
        }
    }
}

@Composable
private fun ShoppingQrSheet(state: PantryUiState, onShareAsText: () -> Unit, onDismiss: () -> Unit) {
    val payload = remember(state.shoppingList, state.shoppingSections) {
        ShoppingListShare.toPayload(state.shoppingList, state.shoppingSections)
    }
    // A QR code has a hard capacity limit and ZXing refuses rather than truncating, so null
    // means the list is too long for one.
    val qrBitmap = remember(payload) { generateQrBitmap(payload) }
    ShoppingSheet(stringResource(R.string.shopping_list_share_as_qr), onDismiss) { close ->
        if (qrBitmap != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LarderSpace.s3),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Pinned to the light theme, so the code stays dark on light in dark mode.
                Box(
                    modifier = Modifier
                        .background(LarderLightRoles.surface, RoundedCornerShape(LarderRadius.md))
                        .padding(LarderSpace.s3)
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.shopping_list_share_qr_content_description),
                        modifier = Modifier.size(ShoppingQrSize)
                    )
                }
                Text(
                    text = stringResource(R.string.shopping_list_share_qr_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            SheetActions {
                LarderButton(
                    text = stringResource(R.string.shopping_list_done),
                    onClick = { close(onDismiss) }
                )
            }
        } else {
            ShoppingQrTooLong(
                onClose = { close(onDismiss) },
                onShareAsText = { close(onShareAsText) }
            )
        }
    }
}

/** T4: this lasts until the list gets shorter, so the way out is right here. */
@Composable
private fun ShoppingQrTooLong(onClose: () -> Unit, onShareAsText: () -> Unit) {
    LarderCallout(
        kind = LarderCalloutKind.Warning,
        message = stringResource(R.string.shopping_list_share_qr_too_large)
    )
    SheetActions {
        LarderButton(
            text = stringResource(R.string.shopping_list_close),
            onClick = onClose,
            style = LarderButtonStyle.Text
        )
        LarderButton(
            text = stringResource(R.string.shopping_list_share_as_text),
            onClick = onShareAsText
        )
    }
}

@Composable
private fun ShoppingImportSheet(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var importText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    ShoppingSheet(stringResource(R.string.shopping_list_import), onDismiss) { close ->
        SheetLead(stringResource(R.string.shopping_list_import_message))
        LarderTextField(
            value = importText,
            onValueChange = { importText = it },
            label = stringResource(R.string.shopping_list_import_label),
            placeholder = stringResource(R.string.shopping_list_import_placeholder),
            singleLine = false,
            minLines = 4,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
        SheetActions {
            LarderButton(
                text = stringResource(R.string.share_recipe_cancel_button),
                onClick = { close(onDismiss) },
                style = LarderButtonStyle.Text
            )
            LarderButton(
                text = stringResource(R.string.shopping_list_import_confirm),
                onClick = { close { onImport(importText) } },
                enabled = importText.isNotBlank()
            )
        }
    }
}

@Composable
private fun ShoppingMoveSheet(
    entryName: String,
    targets: List<ShoppingSection>,
    onMove: (ShoppingSection) -> Unit,
    onDismiss: () -> Unit
) {
    val defaultLabel = stringResource(R.string.shopping_section_default)
    ShoppingSheet(stringResource(R.string.shopping_section_move_to), onDismiss) { close ->
        SheetLead(entryName)
        targets.forEach { target ->
            ShoppingSheetAction(
                icon = if (target.isDefault) LarderIcons.ShoppingBasket else LarderIcons.Layers,
                label = if (target.isDefault) defaultLabel else categoryLabel(target.name),
                onClick = { close { onMove(target) } }
            )
        }
        SheetActions {
            LarderButton(
                text = stringResource(R.string.share_recipe_cancel_button),
                onClick = { close(onDismiss) },
                style = LarderButtonStyle.Text
            )
        }
    }
}

/** Name prompt for creating or renaming a section. Save stays off while the name is blank. */
@Composable
private fun ShoppingSectionNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LarderDialog(
        title = title,
        confirmLabel = stringResource(R.string.shopping_section_save),
        onConfirm = { onConfirm(value) },
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.share_recipe_cancel_button),
        confirmStyle = LarderButtonStyle.Text,
        confirmEnabled = value.isNotBlank()
    ) {
        LarderTextField(
            value = value,
            onValueChange = { value = it },
            label = stringResource(R.string.shopping_section_name_label),
            placeholder = stringResource(R.string.shopping_section_name_placeholder),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
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
    LarderDialog(
        title = stringResource(R.string.shopping_list_did_you_mean_title),
        confirmLabel = stringResource(R.string.shopping_list_did_you_mean_none_of_these),
        onConfirm = onNoneOfThese,
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.shopping_list_did_you_mean_cancel),
        confirmStyle = LarderButtonStyle.Text
    ) {
        Column {
            candidates.forEach { candidate ->
                Text(
                    text = "${candidate.productName} (${candidate.quantity})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCandidateSelected(candidate) }
                        .padding(vertical = LarderSpace.s3)
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun ShoppingHeaderPreview() = LarderPreview {
    ShoppingHeader(5, showNewSection = true, onNewSection = {}, onShare = {}) {
        ShoppingAddField("", {}, {}, {})
    }
    ShoppingHeader(0, showNewSection = false, onNewSection = {}, onShare = {}) {
        ShoppingAddField("Oat", {}, {}, {})
        ShoppingSuggestions(listOf("Oat milk", "Oatcakes", "Porridge oats"), wrap = false, onPick = {})
    }
}

@PreviewLightDark
@Composable
private fun ShoppingShareActionsPreview() = LarderPreview {
    ShoppingShareActions(isEmpty = false, onShareAsText = {}, onShowQr = {}, onImport = {})
    Spacer(Modifier.height(LarderSpace.s4))
    ShoppingShareActions(isEmpty = true, onShareAsText = {}, onShowQr = {}, onImport = {})
}

@PreviewLightDark
@Composable
private fun ShoppingQrTooLongPreview() = LarderPreview {
    ShoppingQrTooLong(onClose = {}, onShareAsText = {})
}
