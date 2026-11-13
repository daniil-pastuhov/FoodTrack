package com.foodtracker.android.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.foodtracker.android.R
import com.foodtracker.android.camera.CameraPreviewView
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.camera.ProductImageLabeler
import com.foodtracker.android.camera.ReceiptTextRecognizer
import com.foodtracker.android.notification.NotificationHelper
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSnackbar
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.theme.LocalIsDarkTheme
import com.foodtracker.android.util.foodLabelName
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.share.ShoppingListShare
import com.foodtracker.viewmodel.BatchScanEntry
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import java.io.File

/**
 * Slot order for the mode bar: the plain barcode scan first, the two "unpack a whole shop" modes
 * that extend it next, then the two single-item helpers.
 */
val SCANNER_DEFAULT_MODES: List<CameraScanMode> = listOf(
    CameraScanMode.BARCODE,
    CameraScanMode.BATCH,
    CameraScanMode.RECEIPT,
    CameraScanMode.DATE_OCR,
    CameraScanMode.PHOTO
)

/** Where to go once a queue has been discarded. */
private enum class AfterDiscard { Leave, TypeItIn }

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
    /** Reports how many items a batch commit added. */
    onBatchCommitted: (added: Int) -> Unit = {},
    // Hidden when this screen is embedded inside Add/Edit (see AddEditItemScreen's scan
    // button): "skip to manual entry" makes no sense there since manual entry is already
    // the screen underneath, and dismissing would otherwise discard fields already typed.
    showManualEntryShortcut: Boolean = true,
    /** Which modes this host can actually complete. A mode whose result the host has nowhere
     *  to put does not belong in the mode bar - see AddEditItemScreen's call. */
    enabledModes: List<CameraScanMode> = SCANNER_DEFAULT_MODES,
    /** Whether to offer "add one to the one you already have" on a re-scan. */
    checkDuplicates: Boolean = true,
    /** Takes one unit of the scanned product out of the pantry - the "I'm using this" answer to
     *  scanning something already there. Needs [checkDuplicates], since `duplicateOf` is what
     *  says which pantry item it is; null where the host has no use for it (Add/Edit, or a
     *  scan that is filling in a shopping list entry). */
    onUseOne: ((PantryItem) -> Unit)? = null,
    /** The mode to open in, when the host can complete it. */
    initialMode: CameraScanMode = enabledModes.first(),
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    var currentMode by remember {
        mutableStateOf(initialMode.takeIf { it in enabledModes } ?: enabledModes.first())
    }
    val context = LocalContext.current
    val expanded = isExpanded()

    // The camera's dark theme sets the system bars for itself, and nothing sets them back once
    // this screen goes, because the app's own theme doesn't recompose for it.
    val view = LocalView.current
    val appIsDark = LocalIsDarkTheme.current
    DisposableEffect(view, appIsDark) {
        onDispose {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !appIsDark
                    isAppearanceLightNavigationBars = !appIsDark
                }
            }
        }
    }

    // Every way into the scanner lands here - the scan buttons, the Add/Edit overlay and the
    // launcher shortcut - so the permission is settled here rather than by each caller.
    // A refusal given earlier, on the first-run camera screen or an earlier scan, goes straight to
    // the camera-off state rather than putting the same question again.
    var cameraAccess by remember {
        mutableStateOf(
            CameraAccess(
                granted = hasCameraPermission(context),
                refused = (view.context as? Activity)?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                } == true
            )
        )
    }
    // The multiple-permissions contract even for one permission: it returns no entry for a
    // cancelled request, where the single one reports a refusal. The system cancels a request
    // that lands while another is up, and that is not the person saying no.
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
    var pendingDiscard by remember { mutableStateOf<AfterDiscard?>(null) }
    var askDuplicate by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    // A product's date came off its barcode unless Scan date read it.
    var dateReadOffPack by remember { mutableStateOf(false) }

    // The Root's host sits at the bottom, under the shutter and the panel, so the scanner has
    // its own at the top. As there, a new message replaces the last rather than queueing.
    val snackbars = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    fun showMessage(message: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
        snackbarScope.launch {
            snackbars.currentSnackbarData?.dismiss()
            val result = snackbars.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    fun deletePhotoFile(uri: String?) {
        uri?.let { runCatching { File(Uri.parse(it).path!!).delete() } }
    }

    fun discardCapturedPhoto() {
        deletePhotoFile(capturedPhotoUri)
        capturedPhotoUri = null
    }

    fun clearResults() {
        dateReadOffPack = false
        stateHolder.clearScanResults()
    }

    // Picking a mode from the bar starts that mode clean. A result belongs to the mode that
    // produced it, and leaving a product, an OCR'd date or a captured photo published while the
    // camera has moved on is how a stale result ends up on screen under a mode that could not
    // have produced it. The two hand-offs on the result panel (Scan date, Photo mode) assign
    // currentMode directly instead, precisely because they *do* carry the scan forward. The
    // batch queue survives either way: it is dropped only on commit, or on leaving.
    fun selectMode(target: CameraScanMode) {
        if (target == currentMode) return
        discardCapturedPhoto()
        photoHandedOff = false
        clearResults()
        currentMode = target
    }

    /** Hands the current scan's date and suggestion to Add item, with whatever else is known. */
    fun proceed(
        barcode: String? = null,
        name: String? = null,
        brand: String? = null,
        imageUrl: String? = null,
        category: String? = null
    ) = onProceedToAddItem(barcode, name, brand, imageUrl, state.detectedDate, category, state.suggestedExpiry)

    fun typeItInNow() {
        // Add item would otherwise ask about the product on screen, which isn't what's being added.
        stateHolder.dismissDuplicate()
        proceed()
    }

    // An uncommitted queue does not outlive the screen that shows it - otherwise its rows sit
    // in state nothing displays, to be committed later alongside an unrelated batch. Discarding
    // a shop's worth of scanning silently would be worse, so leaving asks first.
    fun requestNavigateBack() {
        if (state.batchQueue.isEmpty()) onNavigateBack() else pendingDiscard = AfterDiscard.Leave
    }

    fun requestTypeItIn() {
        if (state.batchQueue.isEmpty()) typeItInNow() else pendingDiscard = AfterDiscard.TypeItIn
    }

    fun discardQueueAndLeave() {
        stateHolder.clearBatchQueue()
        onNavigateBack()
    }

    fun removeQueueRow(entry: BatchScanEntry) {
        val index = state.batchQueue.indexOf(entry)
        stateHolder.removeBatchEntry(entry.id)
        showMessage(
            context.getString(R.string.item_removed_snackbar, entry.name),
            context.getString(R.string.undo_action)
        ) { stateHolder.restoreBatchEntry(entry, index) }
    }

    fun clearQueue() {
        val entries = state.batchQueue
        stateHolder.clearBatchQueue()
        showMessage(
            context.getString(R.string.scan_queue_cleared),
            context.getString(R.string.undo_action)
        ) { stateHolder.restoreBatchQueue(entries) }
    }

    // The host's own predictive-back handler would otherwise leave the scanner without going
    // through the prompt above, taking the queue out of view but not out of state.
    BackHandler(enabled = cameraAccess.granted && state.batchQueue.isNotEmpty()) {
        pendingDiscard = AfterDiscard.Leave
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
            showMessage(
                if (added > 0) {
                    context.resources.getQuantityString(R.plurals.receipt_lines_added, added, added)
                } else {
                    context.getString(R.string.receipt_nothing_found)
                }
            )
            isReadingReceipt = false
            pendingReceiptUri = null
        }
    }

    fun takePhoto() {
        val capture = imageCapture
        if (capture == null || isCapturingPhoto) return
        isCapturingPhoto = true
        // filesDir (not cacheDir): cache can be reclaimed by the OS at any time and is excluded
        // from Auto Backup, which would silently orphan a saved item's imageUrl — filesDir
        // persists and is included in the default Auto Backup rules `android:allowBackup="true"`
        // enables, matching iOS's equivalent choice of Application Support over Caches.
        val photosDir = File(context.filesDir, "product_photos").apply { mkdirs() }
        val photoFile = File(photosDir, "product_photo_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(photoFile).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    isCapturingPhoto = false
                    capturedPhotoUri = Uri.fromFile(photoFile).toString()
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturingPhoto = false
                    showMessage(context.getString(R.string.scan_capture_failed))
                }
            }
        )
    }

    fun readReceipt() {
        val capture = imageCapture
        if (capture == null || isReadingReceipt) return
        isReadingReceipt = true
        // cacheDir, unlike the product photos: this image is read once and never referenced
        // again, so there is nothing to orphan by letting the OS reclaim it.
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
                    showMessage(context.getString(R.string.scan_capture_failed))
                }
            }
        )
    }

    if (!cameraAccess.granted) {
        if (cameraAccess.refused) {
            // True after a first refusal, when Android will still show its dialog; false once
            // it won't (a second refusal), and settings is the only way back.
            val canAskAgain = remember(cameraAccess) {
                (view.context as? Activity)?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                } == true
            }
            CameraOffState(
                // Revoking the permission kills the process, so no queue reaches this state;
                // going through the discard keeps that true without a prompt to host here.
                onClose = { discardQueueAndLeave() },
                canAskAgain = canAskAgain,
                onAllowCamera = {
                    if (canAskAgain) {
                        cameraAccess = cameraAccess.copy(refused = false)
                        requestCameraIfNeeded()
                    } else {
                        context.startActivity(appSettingsIntent(context))
                    }
                },
                onAddByHand = {
                    // In Add/Edit the form is what is underneath, so going back is adding by hand.
                    if (showManualEntryShortcut) proceed() else onNavigateBack()
                },
                modifier = modifier
            )
        } else {
            // Blank behind the system dialog: telling someone the camera is off before they
            // have answered would be wrong.
            Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim))
        }
        return
    }

    val panel = scanPanelFor(currentMode, state, hasCapturedPhoto = capturedPhotoUri != null)
    val product = state.scannedProduct
    // duplicateOf is looked up alongside the product, not after it, so it is checked against
    // this product's barcode rather than trusted to be about the same scan.
    val inPantry = state.duplicateOf?.takeIf { onUseOne != null && it.barcode == product?.barcode }

    ScannerScaffold(
        expanded = expanded,
        modifier = modifier,
        picture = {
            CameraPreviewView(
                mode = currentMode,
                onBarcodeFound = { barcode ->
                    val importSharedList = onShoppingListScanned
                    // A shared shopping list is a QR code, and so is a product's DataMatrix/QR -
                    // they arrive on the same callback. Route ours away before the lookup, or the
                    // whole JSON payload would be sent to Open Food Facts as a barcode.
                    if (ShoppingListShare.isPayload(barcode)) {
                        if (importSharedList != null) {
                            importSharedList(barcode)
                        } else {
                            // Say so rather than drop it: doing nothing is indistinguishable
                            // from the camera having failed to read the code.
                            showMessage(context.getString(R.string.shopping_list_scan_unsupported))
                        }
                    } else if (currentMode == CameraScanMode.BATCH) {
                        stateHolder.enqueueBatchScan(barcode)
                    } else {
                        val taken = stateHolder.onBarcodeScanned(barcode)
                        // Only where the host offers it, and only outside batch: batch collapses
                        // repeats into one row's quantity by itself, and prompting per scan is
                        // exactly the interruption batch mode exists to avoid. Not for a scan
                        // ignored because a result is already held, either - it would pin
                        // another product's pantry row onto the card on screen.
                        if (taken && checkDuplicates) stateHolder.checkForDuplicate(barcode)
                    }
                },
                onDateFound = { date ->
                    val held = stateHolder.uiState.value
                    if (held.scannedProduct != null) {
                        // Scan date: the date lands on the product, which comes back to show it.
                        stateHolder.onDateOcrDetected(date)
                        dateReadOffPack = true
                        currentMode = CameraScanMode.BARCODE
                    } else if (held.detectedDate == null) {
                        // Held like a barcode result, so the date being confirmed can't change
                        // under the thumb as the camera keeps reading. Scan again lets it go.
                        stateHolder.onDateOcrDetected(date)
                    }
                },
                onImageCaptureReady = { imageCapture = it },
                torchEnabled = torchEnabled,
                onTorchAvailabilityChanged = { torchAvailable = it }
            )
            capturedPhotoUri?.let {
                AsyncImage(
                    model = it,
                    contentDescription = stringResource(R.string.photo_captured_title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        },
        controls = {
            ScanControls(
                onClose = { requestNavigateBack() },
                torchAvailable = torchAvailable,
                torchOn = torchEnabled,
                onToggleTorch = { torchEnabled = !torchEnabled },
                onTypeItIn = if (showManualEntryShortcut) ({ requestTypeItIn() }) else null
            )
        },
        frame = scanFrameSize(currentMode, expanded).takeIf { panel != ScanPanel.Photo },
        hint = stringResource(
            when (currentMode) {
                CameraScanMode.BARCODE -> R.string.reticle_hint_barcode
                CameraScanMode.DATE_OCR -> R.string.reticle_hint_date_ocr
                CameraScanMode.PHOTO -> R.string.reticle_hint_photo
                CameraScanMode.BATCH -> R.string.reticle_hint_batch
                CameraScanMode.RECEIPT -> R.string.reticle_hint_receipt
            }
        ).takeIf { showsAimHint(currentMode, panel, state.batchQueue.isEmpty(), isReadingReceipt) },
        foot = when {
            currentMode == CameraScanMode.RECEIPT -> {
                {
                    ScanShutter(
                        icon = LarderIcons.Receipt,
                        description = stringResource(R.string.scan_receipt_button),
                        busy = isReadingReceipt,
                        onClick = { readReceipt() }
                    )
                }
            }
            currentMode == CameraScanMode.PHOTO && capturedPhotoUri == null -> {
                {
                    ScanShutter(
                        icon = LarderIcons.Camera,
                        description = stringResource(R.string.take_photo_button),
                        busy = isCapturingPhoto,
                        onClick = { takePhoto() }
                    )
                }
            }
            else -> null
        },
        modeBar = {
            ScanModeBar(
                modes = enabledModes,
                selected = currentMode,
                queued = state.batchQueue.size,
                onSelect = { selectMode(it) }
            )
        },
        panel = when (panel) {
            ScanPanel.None -> null
            ScanPanel.Queue -> { wide ->
                if (isReadingReceipt) {
                    ReceiptReadingPanel(wide)
                } else {
                    QueuePanel(
                        expanded = wide,
                        entries = state.batchQueue,
                        emptyTitle = stringResource(
                            if (currentMode == CameraScanMode.RECEIPT) {
                                R.string.receipt_queue_empty
                            } else {
                                R.string.batch_queue_empty
                            }
                        ),
                        today = state.today,
                        onRemove = { removeQueueRow(it) },
                        onClear = { clearQueue() },
                        onCommit = {
                            stateHolder.commitBatchQueue { added ->
                                if (added > 0) onBatchCommitted(added)
                            }
                        }
                    )
                }
            }
            ScanPanel.LookingUp -> { wide -> LookingUpPanel(wide) }
            ScanPanel.Photo -> { wide ->
                val barcode = product?.barcode ?: state.productNotFoundBarcode
                PhotoPanel(
                    expanded = wide,
                    imageUrl = capturedPhotoUri,
                    // Name what this photo is being attached to, when there is something.
                    subtitle = product?.name
                        ?: state.productNotFoundBarcode?.let { stringResource(R.string.barcode_format, it) }
                        ?: stringResource(R.string.photo_captured_message),
                    identifying = state.isLabelingPhoto,
                    suggestions = state.photoLabelSuggestions.mapNotNull { suggestion ->
                        foodLabelName(context, suggestion)?.let { PhotoSuggestion(it, suggestion.category) }
                    },
                    onSuggestion = { suggestion ->
                        // The saved item will point at this file, so it survives this screen
                        // going away.
                        photoHandedOff = true
                        proceed(barcode, suggestion.label, product?.brand, capturedPhotoUri, suggestion.category)
                    },
                    onRetake = { discardCapturedPhoto() },
                    onContinue = {
                        photoHandedOff = true
                        proceed(barcode, product?.name, product?.brand, capturedPhotoUri, product?.category)
                    }
                )
            }
            ScanPanel.Product -> { wide ->
                val found = product!!
                ProductPanel(
                    expanded = wide,
                    product = found,
                    date = state.detectedDate,
                    dateSource = if (dateReadOffPack) ScanDateSource.Packaging else ScanDateSource.Barcode,
                    allergens = state.allergenWarnings,
                    inPantry = inPantry?.quantity,
                    forDate = currentMode == CameraScanMode.DATE_OCR,
                    onUseOne = { inPantry?.let { onUseOne?.invoke(it) } },
                    // A hand-off, not a mode pick: this deliberately carries the product forward
                    // so the OCR'd date lands on it, which is why it assigns currentMode instead
                    // of going through selectMode.
                    onScanDate = { currentMode = CameraScanMode.DATE_OCR },
                    onScanAgain = {
                        clearResults()
                        currentMode = CameraScanMode.BARCODE
                    },
                    onContinue = {
                        if (inPantry != null) {
                            askDuplicate = true
                        } else {
                            proceed(found.barcode, found.name, found.brand, found.imageUrl, found.category)
                        }
                    }
                )
            }
            ScanPanel.NotFound -> { wide ->
                val barcode = state.productNotFoundBarcode.orEmpty()
                NotFoundPanel(
                    expanded = wide,
                    barcode = barcode,
                    // Also a hand-off: the unrecognised barcode has to survive into Photo mode
                    // so the photo is attached to it. Hence not selectMode.
                    onPhotoMode = if (CameraScanMode.PHOTO in enabledModes) {
                        { currentMode = CameraScanMode.PHOTO }
                    } else {
                        null
                    },
                    onTypeItIn = { proceed(barcode) },
                    onScanAgain = { clearResults() }
                )
            }
            ScanPanel.Offline -> { wide ->
                val barcode = state.productNotFoundBarcode.orEmpty()
                OfflinePanel(
                    expanded = wide,
                    barcode = barcode,
                    onTryAgain = {
                        val retried = stateHolder.retryBarcodeLookup()
                        if (retried != null && checkDuplicates) stateHolder.checkForDuplicate(retried)
                    },
                    onContinueByHand = { proceed(barcode) }
                )
            }
            ScanPanel.Date -> { wide ->
                DateFoundPanel(
                    expanded = wide,
                    date = state.detectedDate!!,
                    onScanAgain = { clearResults() },
                    onUseDate = { proceed() }
                )
            }
        },
        idlePanel = {
            when (currentMode) {
                CameraScanMode.DATE_OCR -> ScanIdlePanel(
                    stringResource(R.string.scan_idle_date_title),
                    stringResource(R.string.scan_idle_date_message)
                )
                CameraScanMode.PHOTO -> ScanIdlePanel(
                    stringResource(R.string.scan_idle_photo_title),
                    stringResource(R.string.scan_idle_photo_message)
                )
                else -> ScanIdlePanel(
                    stringResource(R.string.scan_idle_results_title),
                    stringResource(R.string.scan_idle_results_message)
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbars) { LarderSnackbar(it) }
        }
    )

    pendingDiscard?.let { after ->
        LarderDialog(
            title = stringResource(R.string.batch_discard_title),
            text = pluralStringResource(
                R.plurals.batch_discard_message,
                state.batchQueue.size,
                state.batchQueue.size
            ),
            confirmLabel = stringResource(R.string.batch_discard_confirm),
            dismissLabel = stringResource(R.string.batch_discard_keep),
            destructive = true,
            onConfirm = {
                pendingDiscard = null
                when (after) {
                    AfterDiscard.Leave -> discardQueueAndLeave()
                    AfterDiscard.TypeItIn -> {
                        stateHolder.clearBatchQueue()
                        typeItInNow()
                    }
                }
            },
            // A real choice, so a tap outside doesn't answer it.
            onDismissRequest = {},
            onDismiss = { pendingDiscard = null }
        )
    }

    if (askDuplicate && inPantry != null && product != null) {
        LarderDialog(
            title = stringResource(R.string.duplicate_dialog_title),
            text = stringResource(
                R.string.duplicate_dialog_message,
                if (inPantry.quantity > 1) "${inPantry.name} ×${inPantry.quantity}" else inPantry.name
            ),
            confirmLabel = stringResource(R.string.duplicate_dialog_add_one),
            dismissLabel = stringResource(R.string.duplicate_dialog_separate),
            onConfirm = {
                askDuplicate = false
                // Scheduled again like every other write, because the reminder was built from
                // a state that no longer exists.
                stateHolder.addOneMore(inPantry.id) { NotificationHelper.scheduleNotification(context, it) }
                onNavigateBack()
            },
            onDismissRequest = {},
            onDismiss = {
                askDuplicate = false
                // Answered here, so Add item mustn't ask again.
                stateHolder.dismissDuplicate()
                proceed(product.barcode, product.name, product.brand, product.imageUrl, product.category)
            }
        )
    }
}

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

/** Stands in for the whole scanner when the camera is refused. */
@Composable
private fun CameraOffState(
    onClose: () -> Unit,
    canAskAgain: Boolean,
    onAllowCamera: () -> Unit,
    onAddByHand: () -> Unit,
    modifier: Modifier = Modifier
) = CameraOffContent(onClose, canAskAgain, onAllowCamera, onAddByHand, modifier)
