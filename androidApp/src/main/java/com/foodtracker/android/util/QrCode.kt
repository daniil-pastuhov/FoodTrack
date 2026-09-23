package com.foodtracker.android.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [contents] as a QR bitmap, or null when it won't fit (ZXing throws rather than
 * truncating once the payload exceeds what a QR code can hold at this error-correction level).
 * Callers fall back to sharing as text, which has no such limit.
 *
 * Error correction is deliberately kept at [ErrorCorrectionLevel.L]: this is read off a screen
 * held a few inches away rather than off a crumpled label, so capacity is worth more than
 * damage tolerance — a higher level would cap the shareable list at noticeably fewer items.
 *
 * Generation only. Scanning stays with ML Kit (see `camera/BarcodeAnalyzer.kt`), which already
 * reads QR among its formats.
 */
fun generateQrBitmap(contents: String, sizePx: Int = 640): Bitmap? {
    if (contents.isEmpty()) return null
    return try {
        val matrix = QRCodeWriter().encode(
            contents,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 1,
                // The payload is JSON, so it is not pure-alphanumeric and must round-trip
                // non-ASCII product names intact.
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
        val bitmap = createBitmap(matrix.width, matrix.height)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val offset = y * matrix.width
            for (x in 0 until matrix.width) {
                // Opaque black/white rather than transparent: the code is shown on a themed
                // surface, and a transparent "white" would go unreadable in dark mode.
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        bitmap
    } catch (_: Exception) {
        null
    }
}
