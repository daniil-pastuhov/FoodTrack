package com.foodtracker.android.ui.screens

import androidx.compose.ui.unit.dp
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.api.ProductLookupResult
import com.foodtracker.model.ProductInfo
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerPanelLogicTest {

    private val product = ProductInfo(barcode = "1", name = "Milk")
    private val date = LocalDate(2026, 11, 24)
    private val offline = PantryUiState(
        productNotFoundBarcode = "1",
        lookupResult = ProductLookupResult.Offline
    )
    private val notFound = PantryUiState(
        productNotFoundBarcode = "1",
        lookupResult = ProductLookupResult.NotFound
    )

    private fun panel(
        state: PantryUiState = PantryUiState(),
        mode: CameraScanMode = CameraScanMode.BARCODE,
        photo: Boolean = false
    ) = scanPanelFor(mode, state, hasCapturedPhoto = photo)

    @Test
    fun `nothing scanned shows no panel`() {
        assertEquals(ScanPanel.None, panel())
    }

    @Test
    fun `batch and receipt always show the queue, whatever a single scan left behind`() {
        val held = PantryUiState(scannedProduct = product)
        assertEquals(ScanPanel.Queue, panel(held, CameraScanMode.BATCH))
        assertEquals(ScanPanel.Queue, panel(held, CameraScanMode.RECEIPT))
    }

    @Test
    fun `a running lookup outranks everything single-item`() {
        val state = PantryUiState(isLookingUpBarcode = true, scannedProduct = product, detectedDate = date)
        assertEquals(ScanPanel.LookingUp, panel(state, photo = true))
    }

    @Test
    fun `a captured photo outranks the product it belongs to`() {
        assertEquals(ScanPanel.Photo, panel(PantryUiState(scannedProduct = product), photo = true))
    }

    @Test
    fun `a product with a date is still the product panel`() {
        assertEquals(ScanPanel.Product, panel(PantryUiState(scannedProduct = product, detectedDate = date)))
    }

    @Test
    fun `offline and not found are told apart`() {
        assertEquals(ScanPanel.Offline, panel(offline))
        assertEquals(ScanPanel.NotFound, panel(notFound))
    }

    @Test
    fun `the not found result goes quiet once it has handed off to photo mode`() {
        assertEquals(ScanPanel.None, panel(notFound, CameraScanMode.PHOTO))
        assertEquals(ScanPanel.Photo, panel(notFound, CameraScanMode.PHOTO, photo = true))
    }

    @Test
    fun `a date alone is a result, but not beside a held barcode`() {
        assertEquals(ScanPanel.Date, panel(PantryUiState(detectedDate = date), CameraScanMode.DATE_OCR))
        assertEquals(ScanPanel.NotFound, panel(notFound.copy(detectedDate = date)))
    }

    @Test
    fun `the hint shows when ready and goes with a result`() {
        assertTrue(showsAimHint(CameraScanMode.BARCODE, ScanPanel.None, true, false))
        assertFalse(showsAimHint(CameraScanMode.BARCODE, ScanPanel.Product, true, false))
        assertFalse(showsAimHint(CameraScanMode.BARCODE, ScanPanel.LookingUp, true, false))
    }

    @Test
    fun `the hint stays while a date is being read for a product`() {
        assertTrue(showsAimHint(CameraScanMode.DATE_OCR, ScanPanel.Product, true, false))
    }

    @Test
    fun `batch keeps its hint with rows queued`() {
        assertTrue(showsAimHint(CameraScanMode.BATCH, ScanPanel.Queue, false, false))
    }

    @Test
    fun `receipt drops its hint while reading and once rows are queued`() {
        assertTrue(showsAimHint(CameraScanMode.RECEIPT, ScanPanel.Queue, true, false))
        assertFalse(showsAimHint(CameraScanMode.RECEIPT, ScanPanel.Queue, true, true))
        assertFalse(showsAimHint(CameraScanMode.RECEIPT, ScanPanel.Queue, false, false))
    }

    @Test
    fun `each mode has the design's frame`() {
        assertEquals(280.dp to 160.dp, scanFrameSize(CameraScanMode.BARCODE, expanded = false))
        assertEquals(280.dp to 160.dp, scanFrameSize(CameraScanMode.BATCH, expanded = false))
        assertEquals(300.dp to 120.dp, scanFrameSize(CameraScanMode.DATE_OCR, expanded = false))
        assertEquals(260.dp to 260.dp, scanFrameSize(CameraScanMode.PHOTO, expanded = false))
        assertEquals(240.dp to 300.dp, scanFrameSize(CameraScanMode.RECEIPT, expanded = false))
        assertEquals(300.dp to 480.dp, scanFrameSize(CameraScanMode.RECEIPT, expanded = true))
        assertEquals(360.dp to 200.dp, scanFrameSize(CameraScanMode.BARCODE, expanded = true))
        assertEquals(360.dp to 200.dp, scanFrameSize(CameraScanMode.BATCH, expanded = true))
        assertEquals(380.dp to 150.dp, scanFrameSize(CameraScanMode.DATE_OCR, expanded = true))
        assertEquals(340.dp to 340.dp, scanFrameSize(CameraScanMode.PHOTO, expanded = true))
    }
}
