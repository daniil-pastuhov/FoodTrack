package com.foodtracker.viewmodel

import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.model.ShelfLifeSuggestion
import kotlinx.datetime.LocalDate
import kotlin.time.TimeSource

/** How long after a Batch scan the camera keeps looking for that product's printed date. */
const val BATCH_DATE_WINDOW_MILLIS = 10_000L

/** How long a printed date read just before a barcode still counts for that barcode's product
 *  (see [RecentDate]). */
const val RECENT_DATE_MILLIS = 2_000L

/** Barcode and Batch modes read the printed date on the barcode's own frames, but outside an
 *  open [BatchDateWindow] text recognition runs at most this often (battery). */
const val BACKGROUND_DATE_READ_INTERVAL_MILLIS = 300L

/**
 * The row a printed date read in Batch mode would land on, and when its window opened.
 *
 * A barcode scan in Batch opens one (a new row or a repeat of a queued one) unless a
 * [RecentDate] landed on the row at once or the row can't take a date ([BatchDateRead.mayTake]),
 * replacing any window still open (a scan with no window closes it); tapping a queued
 * row's date opens a [rescan] one. It closes after [BATCH_DATE_WINDOW_MILLIS] or once a date has
 * landed. While one is open the camera reads dates on every frame it can, otherwise only every
 * [BACKGROUND_DATE_READ_INTERVAL_MILLIS]. iOS has a hand-kept twin (see CLAUDE.md,
 * "Barcode/OCR pipeline").
 */
data class BatchDateWindow(
    val rowId: String,
    val openedAtMillis: Long,
    /** Opened by tapping the row's date: the next read that differs from the row's date
     *  replaces it, whatever it was (see [BatchDateRead.mayTakeRescan]). */
    val rescan: Boolean = false
) {
    fun isOpen(nowMillis: Long): Boolean =
        nowMillis >= openedAtMillis && nowMillis - openedAtMillis < BATCH_DATE_WINDOW_MILLIS
}

/**
 * The latest printed date read in Barcode or Batch mode outside a window, so a barcode read just
 * after it (the date and the barcode on one frame, or the date first) takes it without a date
 * step. Used at most once, and forgotten when the mode changes or the scanner closes.
 */
data class RecentDate(val date: LocalDate, val readAtMillis: Long) {
    fun isRecent(nowMillis: Long): Boolean =
        nowMillis >= readAtMillis && nowMillis - readAtMillis < RECENT_DATE_MILLIS
}

/** The pure half of reading a printed date onto a Batch row: whether a row may take one,
 *  putting it on, taking it off again (Undo), and how a late product lookup merges with it. */
object BatchDateRead {

    /**
     * A row takes a read date only while it is still queued, is not a receipt row, has no date
     * read off its pack yet (GS1 or an earlier read) and has not had its date set by hand.
     */
    fun mayTake(entry: BatchScanEntry?): Boolean =
        entry != null &&
            !entry.isReceiptRow &&
            entry.expirySource != ShelfLifeSource.DETECTED &&
            !entry.dateSetByHand

    /** Whether tapping a row's date may start a rescan: not a receipt row, nor one whose lookup
     *  is still running. */
    fun mayRescan(entry: BatchScanEntry?): Boolean =
        entry != null && !entry.isReceiptRow && !entry.isLookingUp

    /**
     * During a rescan, a read replaces the row's date only when it differs from it, whatever its
     * source (a date read earlier, or one set by hand); the same date again is ignored and the
     * window stays open, so the camera can be moved from a production date to the best-before.
     */
    fun mayTakeRescan(entry: BatchScanEntry?, date: LocalDate): Boolean =
        mayRescan(entry) && entry!!.expiryDate != date

    /**
     * Whether a date read outside a window is kept as the [RecentDate]: not when it is the same
     * day as the date that last landed on a product in this scanner session (through a window,
     * a recent date, a rescan or a held single scan, or read from a GS1 barcode; replaced by
     * each landing, forgotten with the recent date on a mode change, a commit or the scanner
     * closing, kept by Undo and Scan again). A read while a window is open is never kept. The camera
     * usually still sees the pack it has just read, and without this its date would land on the
     * next product scanned. Inside a window such a read is handled as any other, so two
     * products with the same printed date still work by showing the date after the barcode.
     */
    fun keepsAsRecent(date: LocalDate, lastLanded: LocalDate?): Boolean = date != lastLanded

    /**
     * How often the camera reads dates in Barcode and Batch mode: as often as it can while a
     * window is open, as Date mode does, else every [BACKGROUND_DATE_READ_INTERVAL_MILLIS]; not
     * at all (null) while Barcode mode holds a scan that already has its date, until Scan again.
     */
    fun readIntervalMillis(windowOpen: Boolean, heldScanHasDate: Boolean = false): Long? = when {
        windowOpen -> 0L
        heldScanHasDate -> null
        else -> BACKGROUND_DATE_READ_INTERVAL_MILLIS
    }

    /** [entry] with [date] read off its pack, keeping what it had for [undone]. */
    fun applied(entry: BatchScanEntry, date: LocalDate): BatchScanEntry =
        entry.copy(
            expiryDate = date,
            expirySource = ShelfLifeSource.DETECTED,
            dateBeforeRead = ShelfLifeSuggestion(entry.expiryDate, entry.expirySource)
        )

    /** [entry] with its date as it was before the read, or null when there is no read to undo
     *  (already undone, or the date has been set by hand since). */
    fun undone(entry: BatchScanEntry): BatchScanEntry? {
        val before = entry.dateBeforeRead ?: return null
        return entry.copy(expiryDate = before.date, expirySource = before.source, dateBeforeRead = null)
    }

    /**
     * [entry] once its product lookup has suggested [suggestion]. A date read while the lookup
     * was in flight wins: the suggestion becomes what Undo goes back to instead.
     */
    fun withLookupSuggestion(entry: BatchScanEntry, suggestion: ShelfLifeSuggestion): BatchScanEntry =
        if (entry.dateBeforeRead != null) {
            entry.copy(dateBeforeRead = ShelfLifeSuggestion(suggestion.date, suggestion.source))
        } else {
            entry.copy(expiryDate = suggestion.date, expirySource = suggestion.source)
        }
}

private val monotonicStart = TimeSource.Monotonic.markNow()

/** Milliseconds on a clock that only moves forward, for [BatchDateWindow]. */
internal fun monotonicMillis(): Long = monotonicStart.elapsedNow().inWholeMilliseconds
