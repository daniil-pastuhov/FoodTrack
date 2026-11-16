package com.foodtracker.viewmodel

import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.model.ShelfLifeSuggestion
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rule behind reading a printed date onto a Batch row (see [BatchDateRead]). */
class BatchDateReadTest {

    private val guessed = LocalDate(2026, 11, 23)
    private val printed = LocalDate(2026, 12, 2)

    private fun row(
        id: String = "111",
        source: ShelfLifeSource = ShelfLifeSource.FALLBACK,
        dateSetByHand: Boolean = false
    ) = BatchScanEntry(
        id = id,
        barcode = if (id.startsWith("receipt_")) null else id,
        name = "Milk",
        expiryDate = guessed,
        storageLocation = "Fridge",
        expirySource = source,
        dateSetByHand = dateSetByHand
    )

    @Test
    fun testTheWindowIsOpenForTenSecondsFromTheScan() {
        val window = BatchDateWindow("111", openedAtMillis = 5_000)
        assertTrue(window.isOpen(5_000))
        assertTrue(window.isOpen(5_000 + BATCH_DATE_WINDOW_MILLIS - 1))
        assertFalse(window.isOpen(5_000 + BATCH_DATE_WINDOW_MILLIS))
        assertFalse(window.isOpen(4_999))
        assertEquals(10_000L, BATCH_DATE_WINDOW_MILLIS)
    }

    @Test
    fun testAGuessedDateMayBeReplacedByARead() {
        for (source in listOf(ShelfLifeSource.FALLBACK, ShelfLifeSource.REMEMBERED, ShelfLifeSource.CATEGORY_DEFAULT)) {
            assertTrue(BatchDateRead.mayTake(row(source = source)), "$source")
        }
    }

    @Test
    fun testADetectedDateAHandSetDateAReceiptRowOrAGoneRowTakeNoRead() {
        assertFalse(BatchDateRead.mayTake(row(source = ShelfLifeSource.DETECTED)))
        assertFalse(BatchDateRead.mayTake(row(dateSetByHand = true)))
        assertFalse(BatchDateRead.mayTake(row(id = "receipt_1_2")))
        assertFalse(BatchDateRead.mayTake(null))
    }

    @Test
    fun testApplyingMarksTheDateDetectedAndUndoRestoresItExactly() {
        val before = row(source = ShelfLifeSource.REMEMBERED)
        val read = BatchDateRead.applied(before, printed)

        assertEquals(printed, read.expiryDate)
        assertEquals(ShelfLifeSource.DETECTED, read.expirySource)
        assertFalse(read.showsCheckDate)
        assertFalse(BatchDateRead.mayTake(read), "one date per row")

        assertEquals(before, BatchDateRead.undone(read))
    }

    @Test
    fun testUndoWithNothingReadDoesNothing() {
        assertNull(BatchDateRead.undone(row()))
    }

    @Test
    fun testALateLookupKeepsTheReadDateAndBecomesWhatUndoGoesBackTo() {
        val placeholder = row().copy(isLookingUp = true)
        val read = BatchDateRead.applied(placeholder, printed)
        val suggestion = ShelfLifeSuggestion(LocalDate(2026, 11, 28), ShelfLifeSource.CATEGORY_DEFAULT, days = 12)

        val merged = BatchDateRead.withLookupSuggestion(read, suggestion)

        assertEquals(printed, merged.expiryDate)
        assertEquals(ShelfLifeSource.DETECTED, merged.expirySource)
        val undone = BatchDateRead.undone(merged)!!
        assertEquals(suggestion.date, undone.expiryDate)
        assertEquals(ShelfLifeSource.CATEGORY_DEFAULT, undone.expirySource)
    }

    @Test
    fun testALookupWithNoReadFillsTheDateAsBefore() {
        val suggestion = ShelfLifeSuggestion(LocalDate(2026, 11, 28), ShelfLifeSource.REMEMBERED, days = 12)
        val merged = BatchDateRead.withLookupSuggestion(row(), suggestion)
        assertEquals(suggestion.date, merged.expiryDate)
        assertEquals(ShelfLifeSource.REMEMBERED, merged.expirySource)
        assertNull(merged.dateBeforeRead)
    }

    // ---- Round 2: the recent date and rescans ------------------------------------------

    @Test
    fun testARecentDateCountsForTwoSeconds() {
        val recent = RecentDate(printed, readAtMillis = 1_000)
        assertTrue(recent.isRecent(1_000))
        assertTrue(recent.isRecent(1_000 + RECENT_DATE_MILLIS - 1))
        assertFalse(recent.isRecent(1_000 + RECENT_DATE_MILLIS))
        assertFalse(recent.isRecent(999))
        assertEquals(2_000L, RECENT_DATE_MILLIS)
    }

    @Test
    fun testTheDateThatLastLandedIsNotKeptAsRecentAgain() {
        assertFalse(BatchDateRead.keepsAsRecent(printed, lastLanded = printed))
        assertFalse(BatchDateRead.keepsAsRecent(LocalDate(2026, 12, 2), lastLanded = LocalDate(2026, 12, 2)))
        assertTrue(BatchDateRead.keepsAsRecent(printed, lastLanded = guessed))
        assertTrue(BatchDateRead.keepsAsRecent(printed, lastLanded = null))
    }

    @Test
    fun testDatesAreReadOnEveryFrameOnlyWhileAWindowIsOpen() {
        assertEquals(0L, BatchDateRead.readIntervalMillis(windowOpen = true))
        assertEquals(300L, BatchDateRead.readIntervalMillis(windowOpen = false))
        assertEquals(300L, BACKGROUND_DATE_READ_INTERVAL_MILLIS)
        // Barcode mode holding a scan that has its date: no reading until Scan again.
        assertNull(BatchDateRead.readIntervalMillis(windowOpen = false, heldScanHasDate = true))
        assertEquals(0L, BatchDateRead.readIntervalMillis(windowOpen = true, heldScanHasDate = true))
    }

    @Test
    fun testAnyQueuedBarcodeRowMayBeRescannedOnceItsLookupIsDone() {
        assertTrue(BatchDateRead.mayRescan(row(source = ShelfLifeSource.DETECTED)))
        assertTrue(BatchDateRead.mayRescan(row(dateSetByHand = true)))
        assertTrue(BatchDateRead.mayRescan(row()))
        assertFalse(BatchDateRead.mayRescan(row().copy(isLookingUp = true)))
        assertFalse(BatchDateRead.mayRescan(row(id = "receipt_1_2")))
        assertFalse(BatchDateRead.mayRescan(null))
    }

    @Test
    fun testARescanTakesOnlyADifferentDateWhateverTheSource() {
        val read = row(source = ShelfLifeSource.DETECTED, dateSetByHand = true)
        assertFalse(BatchDateRead.mayTakeRescan(read, guessed))
        assertTrue(BatchDateRead.mayTakeRescan(read, printed))
        assertFalse(BatchDateRead.mayTakeRescan(read.copy(isLookingUp = true), printed))
    }

    @Test
    fun testUndoAfterARescanRestoresTheDateFromBeforeIt() {
        val before = row(source = ShelfLifeSource.DETECTED)
        val rescanned = BatchDateRead.applied(before, printed)
        assertEquals(before, BatchDateRead.undone(rescanned))
    }
}
