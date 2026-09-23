package com.foodtracker.android.camera

import android.content.Context
import android.net.Uri
import com.foodtracker.recognition.FoodLabels
import com.foodtracker.recognition.FoodLabelSuggestion
import com.foodtracker.recognition.RecognizedLabel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Names a photographed product using ML Kit's bundled image-labeling model, so an item with no
 * barcode (produce, bakery, market goods) can be added without typing its name.
 *
 * Deliberately **not** an `ImageAnalysis.Analyzer` like [BarcodeAnalyzer] and [TextOcrAnalyzer]:
 * those have to watch a live stream because they are hunting for a barcode or a date stamp that
 * may only come into focus for a frame. Here the user has already chosen the picture, so
 * classifying every preview frame would burn battery to produce a label that changes under them.
 * One pass over the captured photo is both cheaper and more stable.
 *
 * Runs entirely on-device against the bundled model - no photo, and no part of one, leaves the
 * device.
 */
object ProductImageLabeler {

    // Below the shared threshold the label is discarded anyway (see FoodLabels.MIN_CONFIDENCE);
    // asking the model for less just means more rows to filter out.
    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(FoodLabels.MIN_CONFIDENCE)
                .build()
        )
    }

    /**
     * Classifies the photo at [photoUri] and returns the grocery suggestions worth offering.
     *
     * Returns an empty list on any failure - an unreadable file, a model that declines to load,
     * a picture of something that isn't food. Suggestions are an accelerator, so their absence
     * has to be silent: the name field is right there either way.
     */
    suspend fun suggestNames(context: Context, photoUri: String): List<FoodLabelSuggestion> {
        val image = try {
            InputImage.fromFilePath(context, Uri.parse(photoUri))
        } catch (_: Exception) {
            return emptyList()
        }
        val labels = suspendCancellableCoroutine { continuation ->
            labeler.process(image)
                .addOnSuccessListener { result ->
                    continuation.resume(result.map { RecognizedLabel(it.text, it.confidence) })
                }
                .addOnFailureListener { continuation.resume(emptyList()) }
        }
        return FoodLabels.suggestionsFrom(labels)
    }
}
