package com.foodtracker.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.foodtracker.android.R
import com.foodtracker.android.camera.CameraPreviewView
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.camera.ProductImageLabeler
import com.foodtracker.android.camera.ReceiptTextRecognizer
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.FreshGreen
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.share.ShoppingListShare
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.viewmodel.BatchScanEntry
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.delay
import java.io.File

/** How long a transient scanner banner (receipt result, unsupported scan) stays up. */
private const val SCANNER_MESSAGE_VISIBLE_MILLIS = 3000L

/**
 * Chip order for the mode row: the plain barcode scan first, the two "unpack a whole shop"
 * modes that extend it next, then the two single-item helpers.
 */
val SCANNER_DEFAULT_MODES: List<CameraScanMode> = listOf(
    CameraScanMode.BARCODE,
    CameraScanMode.BATCH,
    CameraScanMode.RECEIPT,
    CameraScanMode.DATE_OCR,
    CameraScanMode.PHOTO
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScannerScreen(
    stateHolder: PantryStateHolder,
    onNavigateBack: () -> Unit,
    onProceedToAddItem: (barcode: String?, productName: String?, brand: String?, imageUrl: String?, expiryDate: LocalDate?, category: String?, suggestedExpiry: ShelfLifeSuggestion?) -> Unit,
    /** Called instead of the product lookup when the camera reads one of our own shared-list QR
     *  codes (see [ShoppingListShare.isPayload]), so scanning a friend's list imports it rather
     *  than looking the payload up as if it were a product barcode. Null where the host cannot
     *  import one; the scanner then says so rather than silently swallowing the scan. */
    onShoppingListScanned: ((payload: String) -> Unit)? = null,
    /** Reports how many items a batch commit added, so the host can surface a snackbar. */
    onBatchCommitted: (added: Int) -> Unit = {},
    // Hidden when this screen is embedded inside Add/Edit (see AddEditItemScreen's scan
    // button): "skip to manual entry" makes no sense there since manual entry is already
    // the screen underneath, and dismissing would otherwise discard fields already typed.
    showManualEntryShortcut: Boolean = true,
    /** Which modes this host can actually complete. A mode whose result the host has nowhere
     *  to put does not belong in the chip row - see AddEditItemScreen's call. */
    enabledModes: List<CameraScanMode> = SCANNER_DEFAULT_MODES,
    /** Whether to offer "add one to the one you already have" on a re-scan. Only hosts that
     *  render `PantryUiState.duplicateOf` should ask for it. */
    checkDuplicates: Boolean = true,
    /** Takes one unit of the scanned product out of the pantry - the "I'm using this" answer to
     *  scanning something already there. Needs [checkDuplicates], since `duplicateOf` is what
     *  says which pantry item it is; null where the host has no use for it (Add/Edit, or a
     *  scan that is filling in a shopping list entry). */
    onUseOne: ((PantryItem) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    var currentMode by remember { mutableStateOf(enabledModes.first()) }
    val context = LocalContext.current
    // Every way into the scanner lands here - the scan buttons, the Add/Edit overlay and the
    // launcher shortcut - so the permission is settled here rather than by each caller.
    var cameraAccess by remember { mutableStateOf(CameraAccess(granted = hasCameraPermission(context))) }
    // The multiple-permissions contract even for one permission: it returns no entry for a
    // cancelled request, where the single one reports a refusal. A shortcut's cold start needs
    // that difference: the root's notification request lands in the same frame, and the system
    // cancels whichever of the two comes second.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        cameraAccess = cameraAccess.answered(results[Manifest.permission.CAMERA])
    }
    fun requestCameraIfNeeded() {
        if (cameraAccess.shouldRequest) {
            cameraAccess = cameraAccess.requested()
            cameraPermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }
    LaunchedEffect(Unit) { requestCameraIfNeeded() }
    // Catches the way back from "Open settings" with the camera allowed, and asks again once
    // whatever cancelled the request has gone.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        cameraAccess = cameraAccess.resumed(hasCameraPermission(context))
        requestCameraIfNeeded()
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturingPhoto by remember { mutableStateOf(false) }
    var capturedPhotoUri by remember { mutableStateOf<String?>(null) }
    // A captured photo is written to filesDir and only becomes a saved item's imageUrl if the
    // user continues with it. Every other way out - retaking, switching mode, leaving the
    // scanner - has to delete the file, or it stays on disk referenced by nothing. This flag
    // marks the one case that must not delete it.
    var photoHandedOff by remember { mutableStateOf(false) }
    // Receipt capture is a one-shot read rather than a photo that is kept, so it needs its own
    // in-flight flag: `isCapturingPhoto` covers only the shutter, while this also spans the OCR
    // pass and the queue fill that follow it.
    var isReadingReceipt by remember { mutableStateOf(false) }
    var pendingReceiptUri by remember { mutableStateOf<String?>(null) }
    var transientMessage by remember { mutableStateOf<String?>(null) }
    var confirmDiscardQueue by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    // Measured rather than guessed: the queue panel is as tall as its contents (empty header,
    // or up to a scrolling list plus a commit button), and the receipt shutter has to sit clear
    // of whichever it currently is.
    var queuePanelHeightPx by remember { mutableFloatStateOf(0f) }
    val queuePanelHeight = with(LocalDensity.current) { queuePanelHeightPx.toDp() }

    fun deletePhotoFile(uri: String?) {
        uri?.let { runCatching { File(Uri.parse(it).path!!).delete() } }
    }

    fun discardCapturedPhoto() {
        deletePhotoFile(capturedPhotoUri)
        capturedPhotoUri = null
    }

    // Batch and Receipt fill one shared queue; every other mode produces a single result.
    val batchModeActive = currentMode == CameraScanMode.BATCH || currentMode == CameraScanMode.RECEIPT

    // A date on its own is a real result. Date OCR is a legitimate opening move - there may be
    // no barcode to scan, or the user may only want the date - and until this had a card of its
    // own the date could leave the screen only through the Manual Entry shortcut, which the
    // Add/Edit host hides, making the mode a dead end there.
    val dateOnlyResult = !batchModeActive && !state.isLookingUpBarcode &&
        state.detectedDate != null && state.scannedProduct == null &&
        state.productNotFoundBarcode == null && capturedPhotoUri == null

    // Picking a mode from the chip row starts that mode clean. A result belongs to the mode
    // that produced it, and leaving a product card, an OCR'd date or a captured photo published
    // while the camera has moved on is how a stale result ends up on screen under a mode that
    // could not have produced it. The two in-card hand-offs below ("Scan Date", "Try Photo
    // Mode") assign currentMode directly instead, precisely because they *do* carry the scan
    // forward. The batch queue survives either way: it is dropped only on commit, or on leaving.
    fun selectMode(target: CameraScanMode) {
        if (target == currentMode) return
        discardCapturedPhoto()
        photoHandedOff = false
        stateHolder.clearScanResults()
        currentMode = target
    }

    // An uncommitted queue does not outlive the screen that shows it - otherwise its rows sit
    // in state nothing displays, to be committed later alongside an unrelated batch. Discarding
    // a shop's worth of scanning silently would be worse, so leaving asks first.
    fun requestNavigateBack() {
        if (state.batchQueue.isEmpty()) {
            onNavigateBack()
        } else {
            confirmDiscardQueue = true
        }
    }

    fun discardQueueAndLeave() {
        stateHolder.clearBatchQueue()
        onNavigateBack()
    }

    // The host's own predictive-back handler would otherwise leave the scanner without going
    // through the prompt above, taking the queue out of view but not out of state.
    BackHandler(enabled = cameraAccess.granted && state.batchQueue.isNotEmpty()) {
        confirmDiscardQueue = true
    }

    // Catches every remaining way out - the host navigating away, the overlay closing - for a
    // photo that was taken but never continued with.
    DisposableEffect(Unit) {
        onDispose { if (!photoHandedOff) deletePhotoFile(capturedPhotoUri) }
    }

    // Classify the captured photo once, off the capture callback, so a barcode-less product
    // (produce, bakery) can suggest its own name. Keyed on the URI so retaking a photo
    // reclassifies and cancelling one drops the previous suggestions.
    LaunchedEffect(capturedPhotoUri) {
        val uri = capturedPhotoUri
        if (uri == null) {
            stateHolder.onPhotoLabelsDetected(emptyList())
        } else {
            stateHolder.onPhotoLabelingStarted()
            stateHolder.onPhotoLabelsDetected(ProductImageLabeler.suggestNames(context, uri))
        }
    }

    // Reads a captured receipt once, off the capture callback, and turns its lines into queue
    // rows. The file is deleted either way: it has served its purpose by here, and a photo of a
    // receipt carries a card number and an address worth not leaving on disk.
    LaunchedEffect(pendingReceiptUri) {
        val uri = pendingReceiptUri ?: return@LaunchedEffect
        val lines = ReceiptTextRecognizer.recognizeLines(context, uri)
        runCatching { File(Uri.parse(uri).path!!).delete() }
        stateHolder.enqueueReceiptLines(lines) { added ->
            transientMessage = if (added > 0) {
                context.resources.getQuantityString(R.plurals.receipt_lines_added, added, added)
            } else {
                context.getString(R.string.receipt_nothing_found)
            }
            isReadingReceipt = false
            pendingReceiptUri = null
        }
    }

    if (!cameraAccess.granted) {
        if (cameraAccess.refused) {
            CameraOffState(
                // Revoking the permission kills the process, so no queue reaches this state;
                // going through the discard keeps that true without a prompt to host here.
                onClose = { discardQueueAndLeave() },
                onOpenSettings = { context.startActivity(appSettingsIntent(context)) },
                onAddByHand = {
                    // In Add/Edit the form is what is underneath, so going back is adding by hand.
                    if (showManualEntryShortcut) {
                        onProceedToAddItem(null, null, null, null, state.detectedDate, null, state.suggestedExpiry)
                    } else {
                        onNavigateBack()
                    }
                },
                modifier = modifier
            )
        } else {
            // Blank behind the system dialog: telling someone the camera is off before they
            // have answered would be wrong.
            Box(modifier = modifier.fillMaxSize().background(Color.Black))
        }
        return
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Live Camera Stream
        CameraPreviewView(
            mode = currentMode,
            onBarcodeFound = { barcode ->
                val importSharedList = onShoppingListScanned
                // A shared shopping list is a QR code, and so is a product's DataMatrix/QR - they
                // arrive on the same callback. Route ours away before the lookup, or the whole
                // JSON payload would be sent to Open Food Facts as a barcode.
                if (ShoppingListShare.isPayload(barcode)) {
                    if (importSharedList != null) {
                        importSharedList(barcode)
                    } else {
                        // Say so rather than drop it: doing nothing is indistinguishable from
                        // the camera having failed to read the code.
                        transientMessage = context.getString(R.string.shopping_list_scan_unsupported)
                    }
                } else if (currentMode == CameraScanMode.BATCH) {
                    stateHolder.enqueueBatchScan(barcode)
                } else {
                    val taken = stateHolder.onBarcodeScanned(barcode)
                    // Only where the host renders the prompt, and only outside batch: batch
                    // collapses repeats into one row's quantity by itself, and prompting per
                    // scan is exactly the interruption batch mode exists to avoid. Not for a
                    // scan ignored because a result is already held, either - it would pin
                    // another product's pantry row onto the card on screen.
                    if (taken && checkDuplicates) stateHolder.checkForDuplicate(barcode)
                }
            },
            onDateFound = { date ->
                stateHolder.onDateOcrDetected(date)
            },
            onImageCaptureReady = { imageCapture = it },
            torchEnabled = torchEnabled,
            onTorchAvailabilityChanged = { torchAvailable = it }
        )

        // Top Controls: back button, mode chips, and the two transient readouts. These sit in
        // one flow rather than at fixed offsets because the chip row's height is no longer
        // fixed - it wraps (see below), and hardcoded top paddings collided with a second line.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { requestNavigateBack() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back_content_description),
                        tint = Color.White
                    )
                }
                if (torchAvailable) {
                    IconButton(
                        onClick = { torchEnabled = !torchEnabled },
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = stringResource(R.string.torch_toggle_content_description),
                            tint = Color.White
                        )
                    }
                }
                // Not in batch or receipt: leaving for the single-item form mid-queue strands
                // the rows already scanned on a screen that no longer shows them.
                if (showManualEntryShortcut && !batchModeActive) {
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            onProceedToAddItem(null, null, null, null, state.detectedDate, null, state.suggestedExpiry)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.manual_entry_button), fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scanner Mode Selector. Wraps rather than scrolls: every mode has to be reachable
            // without a swipe that nothing signposts, and five chips will not fit one line on a
            // narrow phone or at a large font scale.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                enabledModes.forEach { mode ->
                    ScanModeChip(
                        mode = mode,
                        selected = currentMode == mode,
                        // A queue built in Batch outlives a switch to another mode, where the
                        // panel that shows it is hidden. Without this it is simply invisible.
                        queuedCount = if (mode == CameraScanMode.BATCH) state.batchQueue.size else 0,
                        onClick = { selectMode(mode) }
                    )
                }
            }

            // Floating Date Detected Chip. Suppressed when the date is the whole result: the
            // card at the bottom is then showing the same date, with something to do about it.
            val detectedDate = state.detectedDate
            if (detectedDate != null && !dateOnlyResult) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(20.dp))
                        .background(FreshGreen)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.expiry_date_found_format, formatLocalDate(detectedDate)),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            // Transient banner. Non-blocking: for a receipt the queue panel below already shows
            // what was added, so this only has to report the count - and, more usefully, the
            // cases where a scan produced nothing this screen could act on.
            transientMessage?.let { message ->
                LaunchedEffect(message) {
                    delay(SCANNER_MESSAGE_VISIBLE_MILLIS)
                    transientMessage = null
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Center Viewfinder Reticle Guide
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            val (reticleWidth, reticleHeight) = when (currentMode) {
                CameraScanMode.BARCODE, CameraScanMode.BATCH -> 280.dp to 160.dp
                // A receipt is tall and narrow. Framing one inside the landscape box the other
                // modes use is not what "fit the whole receipt in frame" is asking for.
                CameraScanMode.RECEIPT -> 240.dp to 300.dp
                CameraScanMode.DATE_OCR, CameraScanMode.PHOTO -> 300.dp to 120.dp
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(width = reticleWidth, height = reticleHeight)
                        .border(2.dp, BrandPrimary.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        when (currentMode) {
                            CameraScanMode.BARCODE -> R.string.reticle_hint_barcode
                            CameraScanMode.DATE_OCR -> R.string.reticle_hint_date_ocr
                            CameraScanMode.PHOTO -> R.string.reticle_hint_photo
                            CameraScanMode.BATCH -> R.string.reticle_hint_batch
                            CameraScanMode.RECEIPT -> R.string.reticle_hint_receipt
                        }
                    ),
                    color = Color.White,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Batch queue panel: the review list that replaces stopping at a form per product.
        if (batchModeActive) {
            BatchQueuePanel(
                entries = state.batchQueue,
                onRemove = stateHolder::removeBatchEntry,
                onClear = stateHolder::clearBatchQueue,
                onCommit = {
                    stateHolder.commitBatchQueue { added ->
                        if (added > 0) onBatchCommitted(added)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { queuePanelHeightPx = it.size.height.toFloat() }
            )
        }

        // Shutter Button (Receipt mode). Separate from the photo shutter below: nothing is kept
        // afterwards — the still is read once, turned into queue rows and deleted — so there is
        // no captured-photo state to show or retake.
        if (currentMode == CameraScanMode.RECEIPT) {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = queuePanelHeight + 24.dp)
            ) {
                IconButton(
                    onClick = {
                        val capture = imageCapture
                        if (capture == null || isReadingReceipt) return@IconButton
                        isReadingReceipt = true
                        // cacheDir, unlike the product photos below: this image is read once and
                        // never referenced again, so there is nothing to orphan by letting the
                        // OS reclaim it.
                        val receiptFile = File(context.cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
                        capture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(receiptFile).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    pendingReceiptUri = Uri.fromFile(receiptFile).toString()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isReadingReceipt = false
                                }
                            }
                        )
                    },
                    enabled = !isReadingReceipt,
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.White, CircleShape)
                        .border(3.dp, BrandPrimary, CircleShape)
                ) {
                    if (isReadingReceipt) {
                        CircularProgressIndicator(color = BrandPrimary, modifier = Modifier.size(28.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.ReceiptLong,
                            contentDescription = stringResource(R.string.scan_receipt_button),
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        // Shutter Button (Photo mode, before a photo has been taken)
        if (currentMode == CameraScanMode.PHOTO && capturedPhotoUri == null) {
            // Only the branches the card can actually show in photo mode: a not-found barcode
            // is suppressed here (that is what "Try Photo Mode" hands off into), so asking
            // about it again would be asking about a state this block has already excluded.
            val resultCardShowing = state.isLookingUpBarcode || state.scannedProduct != null || dateOnlyResult
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (resultCardShowing) 210.dp else 48.dp)
            ) {
                IconButton(
                    onClick = {
                        val capture = imageCapture
                        if (capture == null || isCapturingPhoto) return@IconButton
                        isCapturingPhoto = true
                        // filesDir (not cacheDir): cache can be reclaimed by the OS at any time
                        // and is excluded from Auto Backup, which would silently orphan a saved
                        // item's imageUrl — filesDir persists and is included in the default
                        // Auto Backup rules `android:allowBackup="true"` enables, matching iOS's
                        // equivalent choice of Application Support over Caches for the same photos.
                        val photosDir = File(context.filesDir, "product_photos").apply { mkdirs() }
                        val photoFile = File(photosDir, "product_photo_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                        capture.takePicture(
                            outputOptions,
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    isCapturingPhoto = false
                                    capturedPhotoUri = Uri.fromFile(photoFile).toString()
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isCapturingPhoto = false
                                }
                            }
                        )
                    },
                    enabled = !isCapturingPhoto,
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.White, CircleShape)
                        .border(3.dp, BrandPrimary, CircleShape)
                ) {
                    if (isCapturingPhoto) {
                        CircularProgressIndicator(color = BrandPrimary, modifier = Modifier.size(28.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.take_photo_button),
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        // Bottom Result Card (barcode lookup result, captured photo, or an OCR'd date on its
        // own). Never in batch or receipt mode: those review their results in the queue panel.
        if (!batchModeActive &&
            (state.isLookingUpBarcode || state.scannedProduct != null ||
                (state.productNotFoundBarcode != null && currentMode != CameraScanMode.PHOTO) ||
                capturedPhotoUri != null || dateOnlyResult)) {
            Card(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    if (state.isLookingUpBarcode) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                color = BrandPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.looking_up_product),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else if (capturedPhotoUri != null) {
                        val product = state.scannedProduct
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = capturedPhotoUri,
                                contentDescription = stringResource(R.string.photo_captured_title),
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.photo_captured_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                // Name the product this photo is being attached to when there
                                // is one: this branch outranks the product branch below, so
                                // without it the card would show a photo and nothing about
                                // what was scanned.
                                Text(
                                    text = product?.name
                                        ?: state.productNotFoundBarcode?.let {
                                            stringResource(R.string.barcode_format, it)
                                        }
                                        ?: stringResource(R.string.photo_captured_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { discardCapturedPhoto() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.retake_photo_button))
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = {
                                    // The saved item will point at this file, so it survives
                                    // this screen going away.
                                    photoHandedOff = true
                                    onProceedToAddItem(
                                        product?.barcode ?: state.productNotFoundBarcode,
                                        product?.name,
                                        product?.brand,
                                        capturedPhotoUri,
                                        state.detectedDate,
                                        product?.category,
                                        state.suggestedExpiry
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.continue_button))
                            }
                        }
                    } else if (state.scannedProduct != null) {
                        val product = state.scannedProduct!!
                        // duplicateOf is looked up alongside the product, not after it, so it
                        // is checked against this product's barcode rather than trusted to be
                        // about the same scan.
                        val inPantry = state.duplicateOf
                            ?.takeIf { onUseOne != null && it.barcode == product.barcode }
                        Row(verticalAlignment = Alignment.Top) {
                            if (!product.imageUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = product.imageUrl,
                                    contentDescription = product.name,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = product.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                if (!product.brand.isNullOrBlank()) {
                                    Text(
                                        text = product.brand!!,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.barcode_format, product.barcode),
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                                if (inPantry != null) {
                                    Text(
                                        text = stringResource(R.string.in_pantry_count, inPantry.quantity),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BrandPrimary
                                    )
                                }
                            }
                            ScanAgainButton(onClick = { stateHolder.clearScanResults() })
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        if (inPantry != null && onUseOne != null) {
                            OutlinedButton(
                                onClick = { onUseOne(inPantry) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.use_one_content_description))
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                // A hand-off, not a mode pick: this deliberately carries the
                                // product forward so the OCR'd date lands on it, which is why
                                // it assigns currentMode instead of going through selectMode.
                                onClick = { currentMode = CameraScanMode.DATE_OCR },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.scan_date_button))
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = {
                                    onProceedToAddItem(
                                        product.barcode,
                                        product.name,
                                        product.brand,
                                        product.imageUrl,
                                        state.detectedDate,
                                        product.category,
                                        state.suggestedExpiry
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.continue_button))
                            }
                        }
                    } else if (state.productNotFoundBarcode != null && currentMode != CameraScanMode.PHOTO) {
                        // A notice, not an error: an unknown barcode is routine, and the two
                        // buttons below already say what can be done about it.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.product_not_found_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.barcode_format, state.productNotFoundBarcode!!),
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                            ScanAgainButton(onClick = { stateHolder.clearScanResults() })
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                // Also a hand-off: the unrecognised barcode has to survive into
                                // photo mode so the photo is attached to it. Hence not
                                // selectMode, and hence the `currentMode != PHOTO` guard above,
                                // which hides this card once the hand-off has happened.
                                onClick = { currentMode = CameraScanMode.PHOTO },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.switch_to_photo_button))
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = {
                                    onProceedToAddItem(
                                        state.productNotFoundBarcode,
                                        null,
                                        null,
                                        null,
                                        state.detectedDate,
                                        null,
                                        state.suggestedExpiry
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.continue_button))
                            }
                        }
                    } else {
                        // Date-only: an OCR'd date with no product attached to it. The way on
                        // is the same Add/Edit hand-off every other branch uses, carrying the
                        // date and nothing else.
                        val date = state.detectedDate!!
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.EventAvailable,
                                contentDescription = null,
                                tint = FreshGreen,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.date_captured_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = formatLocalDate(date),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { stateHolder.clearScanResults() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.scan_again_button))
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Button(
                                onClick = {
                                    onProceedToAddItem(null, null, null, null, date, null, state.suggestedExpiry)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.continue_button))
                            }
                        }
                    }
                }
            }
        }

        if (confirmDiscardQueue) {
            AlertDialog(
                onDismissRequest = { confirmDiscardQueue = false },
                title = { Text(stringResource(R.string.batch_discard_title)) },
                text = {
                    Text(
                        pluralStringResource(
                            R.plurals.batch_discard_message,
                            state.batchQueue.size,
                            state.batchQueue.size
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscardQueue = false
                        discardQueueAndLeave()
                    }) {
                        Text(stringResource(R.string.batch_discard_confirm), color = BrandPrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscardQueue = false }) {
                        Text(stringResource(R.string.batch_discard_keep))
                    }
                }
            )
        }
    }
}

/**
 * One mode chip. [queuedCount] hangs a count off the label when that mode is holding work the
 * user cannot currently see — only Batch has any, and only while its panel is hidden behind
 * another mode.
 */
/** Lets go of the single-item result on screen, so the camera takes the next barcode again. */
/** Where the scanner stands on the camera permission. */
internal data class CameraAccess(
    val granted: Boolean,
    val requestInFlight: Boolean = false,
    val refused: Boolean = false
) {
    val shouldRequest: Boolean get() = !granted && !requestInFlight && !refused

    fun requested(): CameraAccess = copy(requestInFlight = true)

    /** [answer] is null when the system cancelled the request without showing it, which is not
     *  a refusal: [shouldRequest] turns true again so it can be asked later. */
    fun answered(answer: Boolean?): CameraAccess =
        copy(granted = answer == true, requestInFlight = false, refused = answer == false)

    fun resumed(granted: Boolean): CameraAccess = copy(granted = granted, refused = refused && !granted)
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** This app's page in system settings, where a refused camera permission is turned back on. */
internal fun appSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

/** Stands in for the whole scanner when the camera is refused: every mode needs the camera, so
 *  the mode chips and the torch go with it and only the way out stays. */
@Composable
private fun CameraOffState(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddByHand: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 96.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NoPhotography,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.camera_off_title),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.camera_off_body),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.camera_off_open_settings))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onAddByHand,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.camera_off_add_by_hand))
            }
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .padding(top = 40.dp, start = 16.dp)
                .size(40.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = stringResource(R.string.back_content_description),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun ScanAgainButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.scan_again_button))
    }
}

@Composable
private fun ScanModeChip(
    mode: CameraScanMode,
    selected: Boolean,
    queuedCount: Int,
    onClick: () -> Unit
) {
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
        CameraScanMode.BARCODE -> Icons.Default.QrCodeScanner
        CameraScanMode.BATCH -> Icons.Default.PlaylistAdd
        CameraScanMode.RECEIPT -> Icons.Default.ReceiptLong
        CameraScanMode.DATE_OCR -> Icons.Default.TextFields
        CameraScanMode.PHOTO -> Icons.Default.CameraAlt
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(if (queuedCount > 0) "$label ($queuedCount)" else label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Black.copy(alpha = 0.6f),
            labelColor = Color.White,
            iconColor = Color.White,
            selectedContainerColor = BrandPrimary,
            selectedLabelColor = Color.White,
            selectedLeadingIconColor = Color.White
        )
    )
}

/**
 * The batch queue, docked over the live camera so scanning and reviewing happen on one screen.
 *
 * Rows appear the instant a barcode is read (see [PantryStateHolder.enqueueBatchScan]) and fill
 * in as their lookups land, so the list visibly keeps up with the scanning rather than lagging
 * behind the network. Anything the app could not work out a date for is flagged rather than
 * silently defaulted — that is the one thing worth stopping to check before committing.
 */
@Composable
private fun BatchQueuePanel(
    entries: List<BatchScanEntry>,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (entries.isEmpty()) {
                    stringResource(R.string.batch_queue_empty)
                } else {
                    pluralStringResource(R.plurals.batch_queue_count, entries.size, entries.size)
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.batch_clear_button), color = TextSecondary)
                }
            }
        }

        if (entries.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            // Capped height so a long queue scrolls inside the panel rather than swallowing the
            // camera preview it is docked over.
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (entry.quantity > 1) "${entry.name} ×${entry.quantity}" else entry.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = when {
                                    entry.isLookingUp -> stringResource(R.string.looking_up_product)
                                    entry.needsAttention -> stringResource(R.string.batch_queue_needs_date)
                                    else -> formatLocalDate(entry.expiryDate)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.needsAttention && !entry.isLookingUp) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    TextSecondary
                                }
                            )
                        }
                        IconButton(onClick = { onRemove(entry.id) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.batch_queue_remove_content_description),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onCommit,
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(stringResource(R.string.batch_commit_button), fontWeight = FontWeight.Bold)
            }
        }
    }
}
