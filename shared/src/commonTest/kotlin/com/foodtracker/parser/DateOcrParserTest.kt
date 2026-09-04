package com.foodtracker.parser

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DateOcrParserTest {

    @Test
    fun testParseDotFormatWithExpKeyword() {
        val lines = listOf(
            "ORGANIC WHOLE MILK",
            "KEEP REFRIGERATED",
            "EXP: 24.11.2026",
            "LOT 4819"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2026, 11, 24), result.date)
    }

    @Test
    fun testParseTextualMonthWithBestBefore() {
        val lines = listOf(
            "SNACK BAR CRUNCH",
            "BEST BEFORE 15 NOV 2026",
            "NET WT 40g"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2026, 11, 15), result.date)
    }

    @Test
    fun testParseSlashShortYear() {
        val lines = listOf(
            "BB 05/12/26",
            "PRODUCT OF NORWAY"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2026, 12, 5), result.date)
    }

    @Test
    fun testParseIsoStandard() {
        val lines = listOf(
            "USE BY 2026-10-08",
            "PLANT #48"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2026, 10, 8), result.date)
    }

    @Test
    fun testParseMonthYearOnly() {
        val lines = listOf(
            "CANNED TOMATOES",
            "EXP 09/27"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2027, 9, 30), result.date)
    }
}
