package com.foodtracker.android.ui.screens

import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.foodtracker.android.R
import com.foodtracker.android.camera.CameraPreviewView
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.FreshGreen
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.datetime.LocalDate
import java.io.File

@Composable
fun ScannerScreen(
    stateHolder: PantryStateHolder,
    onNavigateBack: () -> Unit,
    onProceedToAddItem: (barcode: String?, productName: String?, brand: String?, imageUrl: String?, expiryDate: LocalDate?) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsState()
    var currentMode by remember { mutableStateOf(CameraScanMode.BARCODE) }
    val context = LocalContext.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturingPhoto by remember { mutableStateOf(false) }
    var capturedPhotoUri by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Live Camera Stream
        CameraPreviewView(
            mode = currentMode,
            onBarcodeFound = { barcode ->
                stateHolder.onBarcodeScanned(barcode)
            },
            onDateFound = { date ->
                stateHolder.onDateOcrDetected(date)
            },
            onImageCaptureReady = { imageCapture = it }
        )

        // Top Controls: Back button & Mode Chips
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
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
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        onProceedToAddItem(null, null, null, null, state.detectedDate)
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

            Spacer(modifier = Modifier.height(12.dp))

            // Scanner Mode Selector
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
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
            val reticleWidth = if (currentMode == CameraScanMode.BARCODE) 280.dp else 300.dp
            val reticleHeight = if (currentMode == CameraScanMode.BARCODE) 160.dp else 120.dp

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

        // Floating Date Detected Chip
        if (state.detectedDate != null) {
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
                        text = stringResource(R.string.expiry_date_found_format, state.detectedDate.toString()),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
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
                        val photoFile = File(context.cacheDir, "product_photo_${System.currentTimeMillis()}.jpg")
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
        if (state.isLookingUpBarcode || state.scannedProduct != null ||
            (state.productNotFoundBarcode != null && currentMode != CameraScanMode.PHOTO) || capturedPhotoUri != null) {
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
                                        state.detectedDate
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
                                        state.detectedDate
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
                                        state.detectedDate
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
