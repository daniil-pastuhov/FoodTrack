package com.foodtracker.android.ui.screens

import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TextFields
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.share.ShoppingListShare
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.viewmodel.BatchScanEntry
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.delay
import java.io.File

/** How long the receipt result banner stays up before clearing itself. */
private const val RECEIPT_MESSAGE_VISIBLE_MILLIS = 3000L

@Composable
fun ScannerScreen(
    stateHolder: PantryStateHolder,
    onNavigateBack: () -> Unit,
    onProceedToAddItem: (barcode: String?, productName: String?, brand: String?, imageUrl: String?, expiryDate: LocalDate?, category: String?, suggestedExpiry: ShelfLifeSuggestion?) -> Unit,
    /** Called instead of the product lookup when the camera reads one of our own shared-list QR
     *  codes (see [ShoppingListShare.isPayload]), so scanning a friend's list imports it rather
     *  than looking the payload up as if it were a product barcode. */
    onShoppingListScanned: (payload: String) -> Unit = {},
    /** Reports how many items a batch commit added, so the host can surface a snackbar. */
    onBatchCommitted: (added: Int) -> Unit = {},
    // Hidden when this screen is embedded inside Add/Edit (see AddEditItemScreen's scan
    // button): "skip to manual entry" makes no sense there since manual entry is already
    // the screen underneath, and dismissing would otherwise discard fields already typed.
    showManualEntryShortcut: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    var currentMode by remember { mutableStateOf(CameraScanMode.BARCODE) }
    val context = LocalContext.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturingPhoto by remember { mutableStateOf(false) }
    var capturedPhotoUri by remember { mutableStateOf<String?>(null) }
    // Receipt capture is a one-shot read rather than a photo that is kept, so it needs its own
    // in-flight flag: `isCapturingPhoto` covers only the shutter, while this also spans the OCR
    // pass and the queue fill that follow it.
    var isReadingReceipt by remember { mutableStateOf(false) }
    var pendingReceiptUri by remember { mutableStateOf<String?>(null) }
    var receiptMessage by remember { mutableStateOf<String?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }

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
            receiptMessage = if (added > 0) {
                context.resources.getQuantityString(R.plurals.receipt_lines_added, added, added)
            } else {
                context.getString(R.string.receipt_nothing_found)
            }
            isReadingReceipt = false
            pendingReceiptUri = null
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Live Camera Stream
        CameraPreviewView(
            mode = currentMode,
            onBarcodeFound = { barcode ->
                // A shared shopping list is a QR code, and so is a product's DataMatrix/QR - they
                // arrive on the same callback. Route ours away before the lookup, or the whole
                // JSON payload would be sent to Open Food Facts as a barcode.
                if (ShoppingListShare.isPayload(barcode)) {
                    onShoppingListScanned(barcode)
                } else if (currentMode == CameraScanMode.BATCH) {
                    stateHolder.enqueueBatchScan(barcode)
                } else {
                    stateHolder.onBarcodeScanned(barcode)
                    // Only the single-item flow asks: batch collapses repeats into one row's
                    // quantity by itself, and prompting per scan is exactly the interruption
                    // batch mode exists to avoid.
                    stateHolder.checkForDuplicate(barcode)
                }
            },
            onDateFound = { date ->
                stateHolder.onDateOcrDetected(date)
            },
            onImageCaptureReady = { imageCapture = it },
            torchEnabled = torchEnabled,
            onTorchAvailabilityChanged = { torchAvailable = it }
        )

        // Top Controls: Back button & Mode Chips
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
                    onClick = onNavigateBack,
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
                if (showManualEntryShortcut) {
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

            // Scanner Mode Selector. Horizontally scrollable: the chips no longer fit across
            // a narrow phone, and truncating the last one would hide a whole mode.
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                FilterChip(
                    selected = currentMode == CameraScanMode.BARCODE,
                    onClick = { currentMode = CameraScanMode.BARCODE },
                    label = { Text(stringResource(R.string.scan_mode_barcode)) },
                    leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        labelColor = Color.White,
                        iconColor = Color.White,
                        selectedContainerColor = BrandPrimary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = currentMode == CameraScanMode.BATCH,
                    onClick = { currentMode = CameraScanMode.BATCH },
                    label = { Text(stringResource(R.string.scan_mode_batch)) },
                    leadingIcon = { Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        labelColor = Color.White,
                        iconColor = Color.White,
                        selectedContainerColor = BrandPrimary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = currentMode == CameraScanMode.RECEIPT,
                    onClick = { currentMode = CameraScanMode.RECEIPT },
                    label = { Text(stringResource(R.string.scan_mode_receipt)) },
                    leadingIcon = { Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        labelColor = Color.White,
                        iconColor = Color.White,
                        selectedContainerColor = BrandPrimary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = currentMode == CameraScanMode.DATE_OCR,
                    onClick = { currentMode = CameraScanMode.DATE_OCR },
                    label = { Text(stringResource(R.string.scan_mode_date_ocr)) },
                    leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        labelColor = Color.White,
                        iconColor = Color.White,
                        selectedContainerColor = BrandPrimary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = currentMode == CameraScanMode.PHOTO,
                    onClick = { currentMode = CameraScanMode.PHOTO },
                    label = { Text(stringResource(R.string.scan_mode_photo)) },
                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp)) },
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
        }

        // Center Viewfinder Reticle Guide
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            val isBarcodeShaped = currentMode == CameraScanMode.BARCODE || currentMode == CameraScanMode.BATCH
            val reticleWidth = if (isBarcodeShaped) 280.dp else 300.dp
            val reticleHeight = if (isBarcodeShaped) 160.dp else 120.dp

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
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Batch queue panel: the review list that replaces stopping at a form per product.
        if (currentMode == CameraScanMode.BATCH || currentMode == CameraScanMode.RECEIPT) {
            BatchQueuePanel(
                entries = state.batchQueue,
                onRemove = stateHolder::removeBatchEntry,
                onClear = stateHolder::clearBatchQueue,
                onCommit = {
                    stateHolder.commitBatchQueue { added ->
                        if (added > 0) onBatchCommitted(added)
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Floating Date Detected Chip
        val detectedDate = state.detectedDate
        if (detectedDate != null) {
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 130.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
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
        }

        // Receipt result banner. Transient and non-blocking: the queue panel below already
        // shows what was added, so this only has to report the count — and, more usefully, the
        // case where a photo yielded nothing at all.
        receiptMessage?.let { message ->
            LaunchedEffect(message) {
                delay(RECEIPT_MESSAGE_VISIBLE_MILLIS)
                receiptMessage = null
            }
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().padding(top = 160.dp)
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        // Shutter Button (Receipt mode). Separate from the photo shutter below: nothing is kept
        // afterwards — the still is read once, turned into queue rows and deleted — so there is
        // no captured-photo state to show or retake.
        if (currentMode == CameraScanMode.RECEIPT) {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (state.batchQueue.isEmpty()) 48.dp else 260.dp)
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
            val resultCardShowing = state.isLookingUpBarcode || state.scannedProduct != null ||
                (state.productNotFoundBarcode != null && currentMode != CameraScanMode.PHOTO)
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

        // Bottom Result Card (Barcode lookup result, OCR detection, or captured photo)
        if (currentMode != CameraScanMode.BATCH && currentMode != CameraScanMode.RECEIPT &&
            (state.isLookingUpBarcode || state.scannedProduct != null ||
                (state.productNotFoundBarcode != null && currentMode != CameraScanMode.PHOTO) || capturedPhotoUri != null)) {
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
                                Text(
                                    text = stringResource(R.string.photo_captured_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { capturedPhotoUri = null },
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
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
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.product_not_found_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.product_not_found_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
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
                    }
                }
            }
        }
    }
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
