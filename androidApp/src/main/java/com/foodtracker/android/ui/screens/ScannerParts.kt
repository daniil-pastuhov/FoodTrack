package com.foodtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.foodtracker.android.R
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.ui.design.LarderBadge
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderChip
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIconSize
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderProgressBar
import com.foodtracker.android.ui.design.LarderSpinner
import com.foodtracker.android.ui.design.LarderThumb
import com.foodtracker.android.ui.design.ScreenError
import com.foodtracker.android.ui.design.categoryIcon
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.formatDayMonth
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.api.ProductLookupResult
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.viewmodel.BatchScanEntry
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.datetime.LocalDate

/** Text on the live picture only ever sits on this, the lowest round value that keeps
 *  on-surface above 4.5:1 with a white packet behind it. */
private const val SCAN_SCRIM_ALPHA = 0.72f

/** How far the picture outside the aiming frame is dimmed. */
private const val SCAN_DIM_ALPHA = 0.45f

/** The tallest the result panel gets on a phone, so some of the camera always shows. */
private const val PANEL_MAX_HEIGHT_FRACTION = 0.7f

private val SHUTTER_SIZE = 72.dp
private val SHUTTER_ICON_SIZE = 30.dp
private val SHUTTER_SPINNER_SIZE = 28.dp
private val MODE_SLOT_MIN_HEIGHT = 60.dp
private val MODE_INDICATOR_WIDTH = 52.dp
private val MODE_INDICATOR_HEIGHT = 32.dp
private val RESULT_THUMB_SIZE = 60.dp
private val EXPANDED_PANEL_WIDTH = 400.dp
private val QUEUE_LIST_MAX_HEIGHT = 270.dp
private val QUEUE_ROW_MIN_HEIGHT = 60.dp
private val CAMERA_OFF_ACTIONS_MAX_WIDTH = 280.dp

/** Sits the snackbar below the top controls, clear of the shutter and the result panel. */
private val SNACKBAR_TOP_OFFSET = 72.dp

/** What the result panel is showing. */
internal enum class ScanPanel { None, Queue, LookingUp, Photo, Product, NotFound, Offline, Date }

/** Batch and Receipt fill one shared queue; every other mode produces a single result. */
internal val CameraScanMode.usesQueue: Boolean
    get() = this == CameraScanMode.BATCH || this == CameraScanMode.RECEIPT

/**
 * The panel for this moment. A not-found barcode stays quiet in Photo mode, because that is what
 * Photo mode on the result hands off into. A date is only the whole result with no barcode held.
 */
internal fun scanPanelFor(mode: CameraScanMode, state: PantryUiState, hasCapturedPhoto: Boolean): ScanPanel =
    when {
        mode.usesQueue -> ScanPanel.Queue
        state.isLookingUpBarcode -> ScanPanel.LookingUp
        hasCapturedPhoto -> ScanPanel.Photo
        state.scannedProduct != null -> ScanPanel.Product
        state.productNotFoundBarcode != null -> when {
            mode == CameraScanMode.PHOTO -> ScanPanel.None
            state.lookupResult is ProductLookupResult.Offline -> ScanPanel.Offline
            else -> ScanPanel.NotFound
        }
        state.detectedDate != null -> ScanPanel.Date
        else -> ScanPanel.None
    }

/** Whether the aiming hint shows. It goes once there is a result, except while a date is being
 *  read for a product, and on Receipt once the queue has something to check. */
internal fun showsAimHint(
    mode: CameraScanMode,
    panel: ScanPanel,
    queueIsEmpty: Boolean,
    readingReceipt: Boolean
): Boolean = when (panel) {
    ScanPanel.None -> true
    ScanPanel.Queue -> mode == CameraScanMode.BATCH || (queueIsEmpty && !readingReceipt)
    ScanPanel.Product -> mode == CameraScanMode.DATE_OCR
    else -> false
}

/** The aiming frame for a mode: landscape for barcodes, wider and shallower for a printed date,
 *  square for a whole product, tall for a receipt. */
internal fun scanFrameSize(mode: CameraScanMode, expanded: Boolean): Pair<Dp, Dp> = when (mode) {
    CameraScanMode.BARCODE, CameraScanMode.BATCH -> if (expanded) 360.dp to 200.dp else 280.dp to 160.dp
    CameraScanMode.DATE_OCR -> if (expanded) 380.dp to 150.dp else 300.dp to 120.dp
    CameraScanMode.PHOTO -> if (expanded) 340.dp to 340.dp else 260.dp to 260.dp
    CameraScanMode.RECEIPT -> if (expanded) 300.dp to 480.dp else 240.dp to 300.dp
}

/** The dark half of the scanner, in both themes: a bright interface around a live picture
 *  dazzles and hides what the camera sees. Result panels stay outside it and follow the theme. */
@Composable
internal fun CameraDark(content: @Composable () -> Unit) {
    LarderTheme(darkTheme = true) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}

/**
 * The scanner's layout. On a phone the panel docks over the bottom of the picture, above the mode
 * bar. From 700dp the camera is on the left and the panel fills a column on the right, showing
 * [idlePanel] until there is a result.
 */
@Composable
internal fun ScannerScaffold(
    expanded: Boolean,
    picture: @Composable BoxScope.() -> Unit,
    controls: @Composable () -> Unit,
    frame: Pair<Dp, Dp>?,
    hint: String?,
    foot: (@Composable () -> Unit)?,
    modeBar: @Composable () -> Unit,
    panel: (@Composable (expanded: Boolean) -> Unit)?,
    idlePanel: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim)) {
        val panelMaxHeight = maxHeight * PANEL_MAX_HEIGHT_FRACTION
        if (expanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    CameraArea(
                        picture, controls, frame, hint, foot, snackbarHost,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    CameraDark {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.fillMaxWidth(EXPANDED_MODE_BAR_WIDTH_FRACTION)) { modeBar() }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .width(EXPANDED_PANEL_WIDTH)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .navigationBarsPadding()
                ) {
                    // The system bar's icons are light for the camera, so they need dark under
                    // them on this side too.
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(MaterialTheme.colorScheme.scrim)
                    )
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        if (panel != null) panel(true) else idlePanel()
                    }
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                CameraArea(
                    picture, controls, frame, hint, foot, snackbarHost,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    dockedPanel = panel?.let { content ->
                        { PanelSurface(Modifier.heightIn(max = panelMaxHeight)) { content(false) } }
                    }
                )
                CameraDark(modeBar)
            }
        }
    }
}

/** The mode bar's share of the camera column from 700dp, so the slots don't drift apart. */
private const val EXPANDED_MODE_BAR_WIDTH_FRACTION = 0.7f

/** The picture fills this whether or not a panel is up, so it doesn't jump when one appears. */
@Composable
private fun CameraArea(
    picture: @Composable BoxScope.() -> Unit,
    controls: @Composable () -> Unit,
    frame: Pair<Dp, Dp>?,
    hint: String?,
    foot: (@Composable () -> Unit)?,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dockedPanel: (@Composable () -> Unit)? = null
) {
    Box(modifier = modifier.clipToBounds()) {
        CameraDark { Box(Modifier.fillMaxSize()) { picture() } }
        Column(modifier = Modifier.fillMaxSize()) {
            // The frame dims everything around it, so all that sits on top is raised above it.
            Box(Modifier.zIndex(1f)) { CameraDark(controls) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CameraDark { AimArea(frame, hint, Modifier.fillMaxSize()) }
            }
            foot?.let { Box(Modifier.fillMaxWidth().zIndex(1f)) { CameraDark(it) } }
            dockedPanel?.let { Box(Modifier.zIndex(2f)) { it() } }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(3f)
                .statusBarsPadding()
                .padding(top = SNACKBAR_TOP_OFFSET, start = LarderSpace.s4, end = LarderSpace.s4)
        ) { CameraDark(snackbarHost) }
    }
}

@Composable
private fun PanelSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = LarderRadius.xl, topEnd = LarderRadius.xl)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
    }
}

@Composable
private fun AimArea(frame: Pair<Dp, Dp>?, hint: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s4, Alignment.CenterVertically)
    ) {
        frame?.let { (width, height) ->
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .width(width)
                    .height(height)
                    .aimingFrame()
            )
        }
        hint?.let {
            Text(
                text = it,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(alpha = SCAN_SCRIM_ALPHA),
                        RoundedCornerShape(LarderRadius.sm)
                    )
                    .padding(horizontal = LarderSpace.s3, vertical = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Dims everything outside the frame, then draws two rings, light then dark, so one of them always
 *  clears 3:1 against the picture. Draws past its bounds; the camera area clips it. */
@Composable
private fun Modifier.aimingFrame(): Modifier {
    val light = MaterialTheme.colorScheme.onSurface
    val dark = MaterialTheme.colorScheme.scrim
    return drawBehind {
        val radius = LarderRadius.lg.toPx()
        val ringWidth = 2.dp.toPx()
        val reach = maxOf(size.width, size.height) * 20
        val surround = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(-reach, -reach, reach, reach))
            addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
        }
        drawPath(surround, dark.copy(alpha = SCAN_DIM_ALPHA))
        fun ring(outset: Float, color: Color) = drawRoundRect(
            color = color,
            topLeft = Offset(-outset, -outset),
            size = Size(size.width + outset * 2, size.height + outset * 2),
            cornerRadius = CornerRadius(radius + outset),
            style = Stroke(ringWidth)
        )
        ring(ringWidth * 1.5f, dark)
        ring(ringWidth * 0.5f, light)
    }
}

/** Close, the torch and Type it in. [onTypeItIn] is null where the form is already underneath. */
@Composable
internal fun ScanControls(
    onClose: () -> Unit,
    torchAvailable: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onTypeItIn: (() -> Unit)?
) {
    val scheme = MaterialTheme.colorScheme
    val scrim = scheme.scrim.copy(alpha = SCAN_SCRIM_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = LarderSpace.s3, end = LarderSpace.s3, top = LarderSpace.s3, bottom = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIconButton(
            icon = LarderIcons.X,
            contentDescription = stringResource(R.string.close_scanner_content_description),
            onClick = onClose,
            tint = scheme.onSurface,
            modifier = Modifier.background(scrim, CircleShape)
        )
        if (torchAvailable) {
            val torchState = stringResource(if (torchOn) R.string.scan_torch_on else R.string.scan_torch_off)
            LarderIconButton(
                icon = LarderIcons.Flashlight,
                contentDescription = stringResource(R.string.torch_toggle_content_description),
                onClick = onToggleTorch,
                tint = if (torchOn) scheme.background else scheme.onSurface,
                modifier = Modifier
                    .background(if (torchOn) scheme.onSurface else scrim, CircleShape)
                    .semantics { stateDescription = torchState }
            )
        }
        Spacer(Modifier.weight(1f))
        onTypeItIn?.let {
            // A text button in on-surface, on the scrim like everything else on the picture.
            MaterialTheme(colorScheme = scheme.copy(primary = scheme.onSurface)) {
                LarderButton(
                    text = stringResource(R.string.manual_entry_button),
                    onClick = it,
                    style = LarderButtonStyle.Text,
                    leadingIcon = LarderIcons.Pencil,
                    modifier = Modifier.background(scrim, CircleShape)
                )
            }
        }
    }
}

/** All the modes in reach of a thumb, with no swipe. [queued] badges Batch, because a queue
 *  outlives a change of mode and is out of sight in the others. */
@Composable
internal fun ScanModeBar(
    modes: List<CameraScanMode>,
    selected: CameraScanMode,
    queued: Int,
    onSelect: (CameraScanMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = LarderSpace.s1, vertical = LarderSpace.s2)
    ) {
        modes.forEach { mode ->
            ScanModeSlot(
                mode = mode,
                selected = mode == selected,
                queued = if (mode == CameraScanMode.BATCH) queued else 0,
                onClick = { onSelect(mode) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ScanModeSlot(
    mode: CameraScanMode,
    selected: Boolean,
    queued: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(
        when (mode) {
            CameraScanMode.BARCODE -> R.string.scan_mode_barcode
            CameraScanMode.BATCH -> R.string.scan_mode_batch
            CameraScanMode.RECEIPT -> R.string.scan_mode_receipt
            CameraScanMode.DATE_OCR -> R.string.scan_mode_date_ocr
            CameraScanMode.PHOTO -> R.string.scan_mode_photo
        }
    )
    val icon = when (mode) {
        CameraScanMode.BARCODE -> LarderIcons.ScanBarcode
        CameraScanMode.BATCH -> LarderIcons.Layers
        CameraScanMode.RECEIPT -> LarderIcons.Receipt
        CameraScanMode.DATE_OCR -> LarderIcons.ScanText
        CameraScanMode.PHOTO -> LarderIcons.Camera
    }
    val queuedDescription = if (queued > 0) {
        pluralStringResource(R.plurals.batch_queue_count, queued, queued)
    } else {
        null
    }
    Column(
        modifier = modifier
            .heightIn(min = MODE_SLOT_MIN_HEIGHT)
            .clip(RoundedCornerShape(LarderRadius.md))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                queuedDescription?.let { stateDescription = it }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(MODE_INDICATOR_WIDTH, MODE_INDICATOR_HEIGHT)
                .background(if (selected) scheme.primaryContainer else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            LarderIcon(
                icon,
                contentDescription = null,
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
            )
            if (queued > 0) {
                LarderBadge(
                    count = queued,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(y = (-2).dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** For Photo and Receipt: everywhere else reading is continuous. */
@Composable
internal fun ScanShutter(
    icon: LarderIcons,
    description: String,
    busy: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.onSurface
    val dark = scheme.scrim
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LarderSpace.s2, bottom = LarderSpace.s6),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(SHUTTER_SIZE)
                .drawBehind {
                    val ring = 3.dp.toPx()
                    drawCircle(light, radius = size.minDimension / 2 + ring * 2)
                    drawCircle(dark, radius = size.minDimension / 2 + ring)
                }
                .clip(CircleShape)
                .background(light, CircleShape)
                .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                LarderSpinner(Modifier.size(SHUTTER_SPINNER_SIZE), color = scheme.background)
            } else {
                LarderIcon(icon, contentDescription = null, size = SHUTTER_ICON_SIZE, tint = scheme.background)
            }
        }
    }
}

/** A single result: scrolls by itself if a long name or a large font outgrows the panel. */
@Composable
private fun ResultPanel(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = if (expanded) LarderSpace.s5 else LarderSpace.s4,
                vertical = if (expanded) LarderSpace.s6 else LarderSpace.s4
            ),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        content = content
    )
}

/** Two buttons share a row until the wording needs one each, as in Russian. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        content = content
    )
}

@Composable
private fun ScanResultHeader(
    title: String,
    imageUrl: String?,
    icon: LarderIcons,
    subtitle: String? = null,
    code: String? = null,
    inPantry: Int? = null,
    centred: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = if (centred) Alignment.CenterVertically else Alignment.Top
    ) {
        LarderThumb(imageUrl = imageUrl, fallbackIcon = icon, modifier = Modifier.size(RESULT_THUMB_SIZE))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            code?.let { ScanCode(it) }
            inPantry?.let {
                Text(
                    text = stringResource(R.string.in_pantry_count, it),
                    modifier = Modifier.padding(top = LarderSpace.s1),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ScanCode(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ScanBusy(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = LarderSpace.s12),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderSpinner(Modifier.size(LarderIconSize.Large))
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun LookingUpPanel(expanded: Boolean) = ResultPanel(expanded) {
    ScanBusy(stringResource(R.string.looking_up_product))
}

/** Where the date on a product result came from. */
internal enum class ScanDateSource { Barcode, Packaging }

/**
 * A product: found, found with a date, in the pantry, or matching an allergen, in any combination.
 * [forDate] is the same product while Date mode reads a date for it. [inPantry] is null at zero.
 */
@Composable
internal fun ProductPanel(
    expanded: Boolean,
    product: ProductInfo,
    date: LocalDate?,
    dateSource: ScanDateSource,
    allergens: List<String>,
    inPantry: Int?,
    forDate: Boolean,
    onUseOne: () -> Unit,
    onScanDate: () -> Unit,
    onScanAgain: () -> Unit,
    onContinue: () -> Unit
) = ResultPanel(expanded) {
    if (allergens.isNotEmpty()) {
        LarderCallout(
            kind = LarderCalloutKind.Error,
            icon = LarderIcons.TriangleAlert,
            title = stringResource(R.string.allergen_warning_format, allergens.joinToString(", ")),
            message = stringResource(R.string.scan_allergen_note)
        )
    }
    ScanResultHeader(
        title = product.name,
        imageUrl = product.imageUrl?.ifBlank { null },
        icon = categoryIcon(product.category),
        subtitle = product.brand?.ifBlank { null },
        code = stringResource(R.string.barcode_format, product.barcode),
        inPantry = inPantry
    )
    date?.let {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderIcon(
                LarderIcons.CircleCheck,
                contentDescription = null,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = stringResource(R.string.scan_best_before_format, formatLocalDate(it)),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = stringResource(
                        when (dateSource) {
                            ScanDateSource.Barcode -> R.string.scan_date_from_barcode
                            ScanDateSource.Packaging -> R.string.scan_date_from_packaging
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (!forDate) {
        ScanActions {
            if (inPantry != null) {
                LarderButton(
                    text = stringResource(R.string.use_one_content_description),
                    onClick = onUseOne,
                    style = LarderButtonStyle.Outline,
                    leadingIcon = LarderIcons.CircleMinus,
                    modifier = Modifier.weight(1f)
                )
            }
            LarderButton(
                text = stringResource(R.string.scan_date_button),
                onClick = onScanDate,
                style = LarderButtonStyle.Outline,
                leadingIcon = LarderIcons.ScanText,
                modifier = Modifier.weight(1f)
            )
        }
    }
    ScanActions {
        LarderButton(
            text = stringResource(R.string.scan_again_button),
            onClick = onScanAgain,
            style = LarderButtonStyle.Text
        )
        LarderButton(
            text = stringResource(R.string.continue_button),
            onClick = onContinue,
            modifier = Modifier.weight(1f)
        )
    }
}

/** [onPhotoMode] is null where the host doesn't offer Photo. */
@Composable
internal fun NotFoundPanel(
    expanded: Boolean,
    barcode: String,
    onPhotoMode: (() -> Unit)?,
    onTypeItIn: () -> Unit,
    onScanAgain: () -> Unit
) = ResultPanel(expanded) {
    ScanResultHeader(
        title = stringResource(R.string.product_not_found_title),
        imageUrl = null,
        icon = LarderIcons.Info,
        code = stringResource(R.string.barcode_format, barcode),
        centred = true
    )
    ScanActions {
        onPhotoMode?.let {
            LarderButton(
                text = stringResource(R.string.switch_to_photo_button),
                onClick = it,
                style = LarderButtonStyle.Outline,
                leadingIcon = LarderIcons.Camera,
                modifier = Modifier.weight(1f)
            )
        }
        LarderButton(
            text = stringResource(R.string.manual_entry_button),
            onClick = onTypeItIn,
            modifier = Modifier.weight(1f)
        )
    }
    LarderButton(
        text = stringResource(R.string.scan_again_button),
        onClick = onScanAgain,
        style = LarderButtonStyle.Text
    )
}

@Composable
internal fun OfflinePanel(
    expanded: Boolean,
    barcode: String,
    onTryAgain: () -> Unit,
    onContinueByHand: () -> Unit
) = ResultPanel(expanded) {
    LarderCallout(
        kind = LarderCalloutKind.Warning,
        icon = LarderIcons.WifiOff,
        title = stringResource(R.string.scan_offline_title),
        message = stringResource(R.string.scan_offline_message)
    )
    ScanCode(stringResource(R.string.barcode_format, barcode))
    ScanActions {
        LarderButton(
            text = stringResource(R.string.scan_try_again),
            onClick = onTryAgain,
            style = LarderButtonStyle.Outline,
            modifier = Modifier.weight(1f)
        )
        LarderButton(
            text = stringResource(R.string.scan_continue_by_hand),
            onClick = onContinueByHand,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
internal fun DateFoundPanel(
    expanded: Boolean,
    date: LocalDate,
    onScanAgain: () -> Unit,
    onUseDate: () -> Unit
) = ResultPanel(expanded) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderThumb(imageUrl = null, fallbackIcon = LarderIcons.Clock, modifier = Modifier.size(RESULT_THUMB_SIZE))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.date_captured_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatLocalDate(date),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
    ScanActions {
        LarderButton(
            text = stringResource(R.string.scan_again_button),
            onClick = onScanAgain,
            style = LarderButtonStyle.Text
        )
        LarderButton(
            text = stringResource(R.string.scan_use_this_date),
            onClick = onUseDate,
            modifier = Modifier.weight(1f)
        )
    }
}

/** A suggested name for a photographed product, already in the person's language. */
internal data class PhotoSuggestion(val label: String, val category: String?)

/** [imageUrl] is the captured photo. [identifying] is the labeller still running; Continue works
 *  meanwhile, because a name isn't needed to carry on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoPanel(
    expanded: Boolean,
    imageUrl: String?,
    subtitle: String,
    identifying: Boolean,
    suggestions: List<PhotoSuggestion>,
    onSuggestion: (PhotoSuggestion) -> Unit,
    onRetake: () -> Unit,
    onContinue: () -> Unit
) = ResultPanel(expanded) {
    ScanResultHeader(
        title = stringResource(R.string.photo_captured_title),
        imageUrl = imageUrl,
        icon = LarderIcons.Image,
        subtitle = subtitle,
        centred = true
    )
    if (identifying) {
        ScanBusy(stringResource(R.string.photo_suggestions_analyzing))
    } else if (suggestions.isNotEmpty()) {
        Column {
            Text(
                text = stringResource(R.string.photo_suggestions_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                suggestions.forEach { suggestion ->
                    LarderChip(
                        label = suggestion.label,
                        selected = false,
                        onClick = { onSuggestion(suggestion) }
                    )
                }
            }
        }
    }
    ScanActions {
        LarderButton(
            text = stringResource(R.string.retake_photo_button),
            onClick = onRetake,
            style = LarderButtonStyle.Outline,
            leadingIcon = LarderIcons.Camera,
            modifier = Modifier.weight(1f)
        )
        LarderButton(
            text = stringResource(R.string.continue_button),
            onClick = onContinue,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Shown on the right from 700dp until there is a result. */
@Composable
internal fun ScanIdlePanel(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = LarderSpace.s5, vertical = LarderSpace.s6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.CenterVertically)
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            modifier = Modifier.widthIn(max = 280.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun ReceiptReadingPanel(expanded: Boolean) = ResultPanel(expanded) {
    ScanBusy(stringResource(R.string.scan_reading_receipt))
    LarderProgressBar(Modifier.clip(CircleShape))
}

/**
 * The queue Batch and Receipt share. Rows appear the moment a barcode is read and fill in as their
 * lookups land. The list scrolls inside the panel, so the camera stays usable and Add all stays
 * put; a part-cut row shows there is more.
 */
@Composable
internal fun QueuePanel(
    expanded: Boolean,
    entries: List<BatchScanEntry>,
    emptyTitle: String,
    today: LocalDate,
    onRemove: (BatchScanEntry) -> Unit,
    onClear: () -> Unit,
    onCommit: () -> Unit
) {
    val inset = if (expanded) LarderSpace.s5 else LarderSpace.s4
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.fillMaxHeight() else Modifier)
            .padding(vertical = if (expanded) LarderSpace.s6 else LarderSpace.s3)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LarderSpace.s12)
                .padding(horizontal = inset),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (entries.isEmpty()) {
                    emptyTitle
                } else {
                    pluralStringResource(R.plurals.batch_queue_count, entries.size, entries.size)
                },
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.titleMedium
            )
            if (entries.isNotEmpty()) {
                LarderButton(
                    text = stringResource(R.string.batch_clear_button),
                    onClick = onClear,
                    style = LarderButtonStyle.Text
                )
            }
        }
        if (entries.isNotEmpty()) {
            LazyColumn(
                modifier = if (expanded) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = QUEUE_LIST_MAX_HEIGHT)
                }
            ) {
                items(entries, key = { it.id }) { entry ->
                    QueueRow(entry, today, inset, onRemove = { onRemove(entry) })
                }
            }
            LarderButton(
                text = stringResource(R.string.batch_commit_button),
                onClick = onCommit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = inset, end = inset, top = LarderSpace.s2)
            )
        }
    }
}

@Composable
private fun QueueRow(entry: BatchScanEntry, today: LocalDate, inset: Dp, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = QUEUE_ROW_MIN_HEIGHT)
            .padding(start = inset, end = LarderSpace.s1, top = LarderSpace.s1, bottom = LarderSpace.s1),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderThumb(imageUrl = entry.imageUrl?.ifBlank { null }, fallbackIcon = categoryIcon(entry.category))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (entry.quantity > 1) "${entry.name} ×${entry.quantity}" else entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (entry.isLookingUp) LarderSpinner(Modifier.size(LarderIconSize.Small))
                if (entry.showsCheckDate) CheckDateMark()
                Text(
                    text = queueRowMeta(entry, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        LarderIconButton(
            icon = LarderIcons.X,
            contentDescription = stringResource(R.string.batch_queue_remove_content_description, entry.name),
            onClick = onRemove
        )
    }
}

@Composable
private fun queueRowMeta(entry: BatchScanEntry, today: LocalDate): String {
    if (entry.isLookingUp) return stringResource(R.string.looking_up_product)
    val location = storageLocationLabel(entry.storageLocation)
    val date = if (entry.showsCheckDate) {
        val shown = if (entry.expiryDate.year == today.year) {
            formatDayMonth(entry.expiryDate)
        } else {
            formatLocalDate(entry.expiryDate)
        }
        stringResource(R.string.batch_queue_estimated_format, shown)
    } else {
        formatLocalDate(entry.expiryDate)
    }
    return "$date · $location"
}

@Composable
private fun CheckDateMark() {
    val colors = LarderTheme.colors
    Row(
        modifier = Modifier
            .background(colors.warningContainer, RoundedCornerShape(LarderRadius.sm))
            .padding(start = LarderSpace.s1, end = LarderSpace.s2, top = 1.dp, bottom = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(
            LarderIcons.CircleAlert,
            contentDescription = null,
            size = LarderIconSize.Small,
            tint = colors.onWarningContainer
        )
        Text(
            text = stringResource(R.string.batch_queue_needs_date),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onWarningContainer,
            maxLines = 1
        )
    }
}

/** T5: every mode needs the camera, so the modes and the torch go, and Close stays. While
 *  Android will still show its permission dialog ([canAskAgain]), the main button asks again
 *  in the app; after a second refusal only system settings can turn the camera back on. */
@Composable
internal fun CameraOffContent(
    onClose: () -> Unit,
    canAskAgain: Boolean,
    onAllowCamera: () -> Unit,
    onAddByHand: () -> Unit,
    modifier: Modifier = Modifier
) = CameraDark {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val fullHeight = maxHeight
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ScreenError(
                icon = LarderIcons.CameraOff,
                title = stringResource(R.string.camera_off_title),
                message = stringResource(R.string.camera_off_body),
                modifier = Modifier.heightIn(min = fullHeight)
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = CAMERA_OFF_ACTIONS_MAX_WIDTH)
                        .fillMaxWidth()
                        .padding(top = LarderSpace.s2),
                    verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
                ) {
                    LarderButton(
                        text = stringResource(
                            if (canAskAgain) R.string.camera_off_allow else R.string.camera_off_open_settings
                        ),
                        onClick = onAllowCamera,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LarderButton(
                        text = stringResource(R.string.camera_off_add_by_hand),
                        onClick = onAddByHand,
                        style = LarderButtonStyle.Outline,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        ScanControls(
            onClose = onClose,
            torchAvailable = false,
            torchOn = false,
            onToggleTorch = {},
            onTypeItIn = null
        )
    }
}

// Previews, one per state. The picture is a flat stand-in, since previews have no camera.

private val PreviewProduct = ProductInfo(
    barcode = "5012345678900",
    name = "Mature cheddar",
    brand = "Wyke Farms",
    imageUrl = null,
    category = "Dairy"
)
private val PreviewDate = LocalDate(2026, 11, 24)
private val PreviewToday = LocalDate(2026, 11, 4)

private fun previewEntry(name: String, source: ShelfLifeSource, quantity: Int = 1, lookingUp: Boolean = false) =
    BatchScanEntry(
        id = name,
        name = name,
        category = "Dairy",
        expiryDate = LocalDate(2026, 11, 18),
        quantity = quantity,
        storageLocation = "Fridge",
        expirySource = source,
        isLookingUp = lookingUp
    )

private val PreviewQueue = listOf(
    previewEntry("Free-range eggs", ShelfLifeSource.DETECTED, quantity = 6),
    previewEntry("Mature cheddar", ShelfLifeSource.FALLBACK),
    previewEntry("5012345678917", ShelfLifeSource.FALLBACK, lookingUp = true)
)

@Composable
private fun ScannerPreviewFrame(expanded: Boolean = false) {
    ScannerScaffold(
        expanded = expanded,
        picture = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh)) },
        controls = { ScanControls({}, torchAvailable = true, torchOn = false, onToggleTorch = {}, onTypeItIn = {}) },
        frame = scanFrameSize(CameraScanMode.BARCODE, expanded),
        hint = "Line up the barcode inside the frame",
        foot = null,
        modeBar = {
            ScanModeBar(
                modes = SCANNER_DEFAULT_MODES,
                selected = CameraScanMode.BARCODE,
                queued = 3,
                onSelect = {}
            )
        },
        panel = null,
        idlePanel = { ScanIdlePanel("Results show up here", "Scan a barcode and the product appears on this side.") },
        snackbarHost = {}
    )
}

@PreviewLightDark
@Composable
internal fun ScannerReadyPreview() = LarderPreview { Box(Modifier.height(640.dp)) { ScannerPreviewFrame() } }

@Preview(widthDp = 820, heightDp = 600)
@Composable
internal fun ScannerExpandedPreview() = LarderPreview {
    Box(Modifier.height(600.dp)) { ScannerPreviewFrame(expanded = true) }
}

@PreviewLightDark
@Composable
internal fun ScanShutterPreview() = LarderPreview {
    CameraDark {
        Column {
            ScanShutter(LarderIcons.Camera, "Take photo", busy = false) {}
            ScanShutter(LarderIcons.Receipt, "Reading receipt", busy = true) {}
        }
    }
}

@PreviewLightDark
@Composable
internal fun ProductPanelPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        ProductPanel(false, PreviewProduct, null, ScanDateSource.Barcode, emptyList(), null, false, {}, {}, {}, {})
        ProductPanel(false, PreviewProduct, PreviewDate, ScanDateSource.Barcode, emptyList(), null, false, {}, {}, {}, {})
        ProductPanel(false, PreviewProduct, null, ScanDateSource.Barcode, emptyList(), 2, false, {}, {}, {}, {})
    }
}

@PreviewLightDark
@Composable
internal fun ProductPanelAllergenPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        ProductPanel(false, PreviewProduct, null, ScanDateSource.Barcode, listOf("milk"), null, false, {}, {}, {}, {})
        ProductPanel(false, PreviewProduct, null, ScanDateSource.Barcode, emptyList(), null, true, {}, {}, {}, {})
    }
}

@PreviewLightDark
@Composable
internal fun LookupOutcomePanelsPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        LookingUpPanel(false)
        NotFoundPanel(false, "5012345678917", {}, {}, {})
        OfflinePanel(false, "5012345678900", {}, {})
        DateFoundPanel(false, PreviewDate, {}, {})
    }
}

@PreviewLightDark
@Composable
internal fun PhotoPanelPreview() = LarderPreview {
    val message = "Use this photo for the item, or retake it."
    val apple = listOf(PhotoSuggestion("Apple", null), PhotoSuggestion("Pear", null))
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        PhotoPanel(false, null, message, true, emptyList(), {}, {}, {})
        PhotoPanel(false, null, message, false, apple, {}, {}, {})
        PhotoPanel(false, null, message, false, emptyList(), {}, {}, {})
    }
}

@PreviewLightDark
@Composable
internal fun QueuePanelPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        QueuePanel(false, emptyList(), "Scan a product to start the list", PreviewToday, {}, {}, {})
        QueuePanel(false, PreviewQueue, "", PreviewToday, {}, {}, {})
        ReceiptReadingPanel(false)
    }
}

@PreviewLightDark
@Composable
internal fun CameraOffPreview() = LarderPreview {
    Box(Modifier.height(560.dp)) { CameraOffContent({}, canAskAgain = true, {}, {}) }
}
