package com.foodtracker.android.camera

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.datetime.LocalDate
import java.util.concurrent.Executors

enum class CameraScanMode {
    BARCODE,
    DATE_OCR,
    PHOTO,

    /**
     * Keeps the camera live and queues each scanned product instead of stopping to fill in a
     * form, for unpacking a whole shop. Uses the same analyzer as [BARCODE]; only what the
     * screen does with each result differs.
     */
    BATCH,

    /**
     * Photographs a till receipt and reads every product line off it in one pass, filling the
     * same queue [BATCH] does.
     *
     * Runs no live analyzer, like [PHOTO]: a receipt has to be read from one stable,
     * fully-framed still rather than from the stream. See `ReceiptTextRecognizer`.
     */
    RECEIPT
}

@Composable
fun CameraPreviewView(
    mode: CameraScanMode,
    onBarcodeFound: (String) -> Unit,
    onDateFound: (LocalDate) -> Unit,
    onImageCaptureReady: (ImageCapture) -> Unit = {},
    torchEnabled: Boolean = false,
    onTorchAvailabilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(mode) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).await()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            when (mode) {
                // Batch shares the barcode analyzer verbatim; only what ScannerScreen does with
                // each result differs (queue it, rather than stop and look it up).
                CameraScanMode.BARCODE, CameraScanMode.BATCH -> {
                    imageAnalysis.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode ->
                        onBarcodeFound(barcode)
                    })
                }
                CameraScanMode.DATE_OCR -> {
                    imageAnalysis.setAnalyzer(cameraExecutor, TextOcrAnalyzer { result ->
                        onDateFound(result.date)
                    })
                }
                // Both capture a still and process it afterwards, so neither wants a
                // frame-by-frame analyzer running behind the preview.
                CameraScanMode.PHOTO, CameraScanMode.RECEIPT -> {
                    imageAnalysis.clearAnalyzer()
                }
            }

            cameraProvider.unbindAll()
            val boundCamera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
                imageCapture
            )
            camera = boundCamera
            onTorchAvailabilityChanged(boundCamera.cameraInfo.hasFlashUnit())
            onImageCaptureReady(imageCapture)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LaunchedEffect(camera, torchEnabled) {
        camera?.cameraControl?.enableTorch(torchEnabled)
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
