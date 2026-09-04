package com.foodtracker.parser

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GS1BarcodeParserTest {

    @Test
    fun testBracketedExpiryDate() {
        val barcode = "(01)09501101530003(17)261025(10)ABCD"
        val date = GS1BarcodeParser.extractExpiryDate(barcode)
        assertEquals(LocalDate(2026, 10, 25), date)
    }

    @Test
    fun testBracketedBestBeforeDate() {
        val barcode = "(15)270415"
        val date = GS1BarcodeParser.extractExpiryDate(barcode)
        assertEquals(LocalDate(2027, 4, 15), date)
    }

    @Test
    fun testDayZeroLastDayOfMonth() {
        // Day 00 should yield the last day of the month (Feb 2026 has 28 days)
        val barcode = "(17)260200"
        val date = GS1BarcodeParser.extractExpiryDate(barcode)
        assertEquals(LocalDate(2026, 2, 28), date)
    }

    @Test
    fun testCompoundUnbracketedBarcode() {
        val barcode = "010123456789012817261130"
        val date = GS1BarcodeParser.extractExpiryDate(barcode)
        assertEquals(LocalDate(2026, 11, 30), date)
    }

    @Test
    fun testStandardUpcNoExpiryDate() {
        val barcode = "012345678905"
        val date = GS1BarcodeParser.extractExpiryDate(barcode)
        assertNull(date)
    }
}
