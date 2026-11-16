package com.foodtracker.android.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class BarcodeAnalyzer(
    private val onBarcodeFound: (String) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_DATA_MATRIX
        )
        .build()

    private val scanner = BarcodeScanning.getClient(options)
    private val client = InFlightClient { scanner.close() }
    private var lastScannedBarcode: String? = null
    private var lastScanTimestamp: Long = 0

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val task = process(image)
            if (task == null) imageProxy.close() else task.addOnCompleteListener { imageProxy.close() }
        } else {
            imageProxy.close()
        }
    }

    /** Reads [image] and reports what it finds; the caller owns the frame and closes it once
     *  the returned task completes (see [ScanFrameAnalyzer]). Null once [close]d. */
    fun process(image: InputImage): Task<*>? = client.start {
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val now = System.currentTimeMillis()
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    // Debounce identical scans within 2 seconds
                    if (raw != lastScannedBarcode || now - lastScanTimestamp > 2000) {
                        lastScannedBarcode = raw
                        lastScanTimestamp = now
                        onBarcodeFound(raw)
                        break
                    }
                }
            }
    }

    /** Releases the ML Kit scanner, once any read still running has finished. */
    override fun close() = client.close()
}
