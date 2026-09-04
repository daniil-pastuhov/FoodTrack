package com.foodtracker.android.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.concurrent.futures.await
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.datetime.LocalDate
import java.io.File
import java.util.concurrent.Executors

enum class CameraScanMode {
    BARCODE,
    DATE_OCR,
    PHOTO
}

@Composable
fun CameraPreviewView(
    mode: CameraScanMode,
    onBarcodeFound: (String) -> Unit,
    onDateFound: (LocalDate) -> Unit,
    onPhotoCaptured: ((File) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }

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
                CameraScanMode.BARCODE -> {
                    imageAnalysis.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcode ->
                        onBarcodeFound(barcode)
                    })
                }
                CameraScanMode.DATE_OCR -> {
                    imageAnalysis.setAnalyzer(cameraExecutor, TextOcrAnalyzer { result ->
                        onDateFound(result.date)
                    })
                }
                CameraScanMode.PHOTO -> {
                    imageAnalysis.clearAnalyzer()
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis,
                imageCapture
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
