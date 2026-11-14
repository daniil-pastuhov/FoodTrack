package com.foodtracker.android.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.foodtracker.android.BuildConfig
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

/** What the scan demo shows and reads. See [ScanDemo]. */
class ScanDemoFeed(val picturePath: String, val barcode: String, val date: LocalDate)

/**
 * The store screenshots' scan, in debug builds only: MainActivity's `--es demoRoute scan` (with
 * `demoScanImage`, `demoScanBarcode` and `demoScanDate`) sets [feed], and the scanner then shows
 * that picture in place of the camera, which an emulator lacks, and reads its barcode and date as
 * the camera would. scripts/demo_scan_image.py draws the picture. Everything here checks
 * BuildConfig.DEBUG before reading [feed], so release builds never take this path.
 */
object ScanDemo {
    @Volatile var feed: ScanDemoFeed? = null
}

/**
 * The demo's stand-in for the camera preview: reads the barcode and then the printed date off the
 * picture, in the order a real scan reads them (barcode first, then Scan date once the product has
 * been found). The picture itself is drawn by [scanDemoBackdrop].
 */
@Composable
internal fun ScanDemoPicture(
    feed: ScanDemoFeed,
    onBarcodeFound: (String) -> Unit,
    awaitProduct: suspend () -> Unit,
    onDateFound: (LocalDate) -> Unit
) {
    LaunchedEffect(feed) {
        onBarcodeFound(feed.barcode)
        awaitProduct()
        onDateFound(feed.date)
    }
}

/**
 * The demo's picture, drawn behind the aiming frame and centred on it, so its barcode sits in the
 * frame whatever the layout, as it would with the phone held over the pack. 4.5 frame widths wide
 * (see scripts/demo_scan_image.py), it covers the rest of the camera area too, which clips it.
 * Nothing outside the demo.
 */
@Composable
internal fun Modifier.scanDemoBackdrop(): Modifier {
    val feed = (if (BuildConfig.DEBUG) ScanDemo.feed else null) ?: return this
    val picture = remember(feed) {
        // Halved: the full picture would take about 90 MB as a bitmap.
        BitmapFactory.decodeFile(feed.picturePath, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
    } ?: return this
    return drawBehind {
        val width = size.width * 4.5f
        val height = width * picture.height / picture.width
        drawImage(
            picture,
            dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
            dstSize = IntSize(width.roundToInt(), height.roundToInt())
        )
    }
}
