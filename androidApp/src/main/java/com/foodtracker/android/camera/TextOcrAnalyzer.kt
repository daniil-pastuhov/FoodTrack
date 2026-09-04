package com.foodtracker.android.camera

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.foodtracker.parser.DateOcrParser
import com.foodtracker.parser.DateOcrResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TextOcrAnalyzer(
    private val onDateDetected: (DateOcrResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastEmittedDate: String? = null
    private var lastDetectionTimestamp: Long = 0

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val lines = visionText.textBlocks.flatMap { it.lines }.map { it.text }
                    if (lines.isNotEmpty()) {
                        val result = DateOcrParser.parseBestDate(lines)
                        if (result != null && result.confidence >= 0.65f) {
                            val now = System.currentTimeMillis()
                            val dateKey = result.date.toString()
                            if (dateKey != lastEmittedDate || now - lastDetectionTimestamp > 2000) {
                                lastEmittedDate = dateKey
                                lastDetectionTimestamp = now
                                onDateDetected(result)
                            }
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}
