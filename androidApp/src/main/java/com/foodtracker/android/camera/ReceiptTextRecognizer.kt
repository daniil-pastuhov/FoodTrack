package com.foodtracker.android.camera

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the text off a photographed till receipt, in reading order.
 *
 * Deliberately a one-shot pass over a captured still rather than an `ImageAnalysis.Analyzer`
 * like [TextOcrAnalyzer]. That one re-reads a small date stamp from the live stream until it
 * sees the same answer twice, which suits a few characters that only need to be caught once. A
 * receipt is thirty lines that must all be read from a single, fully-framed, in-focus image —
 * streaming it would produce a different partial reading every frame with no way to tell which
 * was complete.
 *
 * Runs entirely on-device against ML Kit's bundled Latin model; no image, and no part of one,
 * leaves the phone. Parsing the lines into products is [com.foodtracker.parser.ReceiptParser]'s
 * job, and lives in `shared` so iOS applies the same rules.
 */
object ReceiptTextRecognizer {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /**
     * Returns the recognized lines of the receipt photographed at [photoUri], top to bottom, or
     * an empty list if the image can't be read.
     *
     * Lines come from `textBlocks.flatMap { it.lines }` for the same reason [TextOcrAnalyzer]
     * does it: ML Kit groups a receipt's columns into blocks, and a block's own `text` joins
     * them with newlines in an order that doesn't survive a right-aligned price column.
     */
    suspend fun recognizeLines(context: Context, photoUri: String): List<String> {
        val image = try {
            InputImage.fromFilePath(context, Uri.parse(photoUri))
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    continuation.resume(visionText.textBlocks.flatMap { block -> block.lines }.map { it.text })
                }
                .addOnFailureListener { error ->
                    error.printStackTrace()
                    continuation.resume(emptyList())
                }
        }
    }
}
