package com.foodtracker.android.camera

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Barcode and Batch mode's analyzer: every frame goes to [barcodes], and the same frames to
 * [dates], so a printed date can be read together with the barcode without a Date-mode step.
 * Text recognition is the costly half: it runs one frame at a time, and no more often than
 * [dateReadIntervalMillis] asks (`PantryStateHolder.dateReadIntervalMillis`: every frame it can
 * while a Batch date window is open, as Date mode does, not at all while Barcode mode holds a
 * scan that has its date, otherwise every 300 ms).
 *
 * The date is read from a copy of the frame, so the frame is closed as soon as the barcode
 * reader is done with it and the next frame never waits on text recognition.
 */
class ScanFrameAnalyzer(
    private val barcodes: BarcodeAnalyzer,
    private val dates: TextOcrAnalyzer,
    private val dateReadIntervalMillis: () -> Long?
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val readingDate = AtomicBoolean(false)
    private var lastDateReadStartedAt = Long.MIN_VALUE / 2

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val now = SystemClock.elapsedRealtime()
        val interval = dateReadIntervalMillis()
        if (interval != null && now - lastDateReadStartedAt >= interval && readingDate.compareAndSet(false, true)) {
            lastDateReadStartedAt = now
            val reading = runCatching { imageProxy.toBitmap() }.getOrNull()
                ?.let { copy -> dates.process(InputImage.fromBitmap(copy, rotation)) }
            if (reading == null) readingDate.set(false) else reading.addOnCompleteListener { readingDate.set(false) }
        }
        val barcodeRead = barcodes.process(InputImage.fromMediaImage(mediaImage, rotation))
        if (barcodeRead == null) imageProxy.close() else barcodeRead.addOnCompleteListener { imageProxy.close() }
    }

    /** Releases both ML Kit clients, each once its reads still running have finished. */
    override fun close() {
        barcodes.close()
        dates.close()
    }
}
