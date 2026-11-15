package com.foodtracker.parser

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun testParseCompactNoSeparator() {
        val lines = listOf(
            "FROZEN PEAS",
            "BEST BEFORE 01122026"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2026, 12, 1), result.date)
    }

    @Test
    fun testParseDashFormat() {
        val lines = listOf(
            "USE BY 11-12-2027"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2027, 12, 11), result.date)
    }

    @Test
    fun testParseSlashMonthYearFullYear() {
        val lines = listOf(
            "EXP 10/2026"
        )
        val result = DateOcrParser.parseBestDate(lines, referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2026, 10, 31), result.date)
    }

    @Test
    fun testAcceptsDateTenYearsAhead() {
        val result = DateOcrParser.parseBestDate(listOf("EXP 24.11.2036"), referenceYear = 2026)
        assertNotNull(result)
        assertEquals(LocalDate(2036, 11, 24), result.date)
    }

    @Test
    fun testRejectsDateMoreThanTenYearsAhead() {
        assertNull(DateOcrParser.parseBestDate(listOf("EXP 24.11.2037"), referenceYear = 2026))
        assertNull(DateOcrParser.parseBestDate(listOf("EXP 11/37"), referenceYear = 2026))
    }

    @Test
    fun testRejectsDateMoreThanTenYearsAgo() {
        assertNull(DateOcrParser.parseBestDate(listOf("EXP 24.11.2015"), referenceYear = 2026))
        assertNotNull(DateOcrParser.parseBestDate(listOf("EXP 24.11.2016"), referenceYear = 2026))
    }

    /** The keyword line wins over an earlier date without one (both 0.70 otherwise, and the
     *  first would be kept), in every language the app ships in. */
    @Test
    fun testKeywordsInEveryAppLanguage() {
        val keywords = listOf(
            "BEST BEFORE", "Consumir preferentemente antes del", "CAD.", "Mindestens haltbar bis", "MHD",
            "À consommer de préférence avant le", "DDM", "Bäst före", "BÄST FÖRE", "Bedst før", "MINDST HOLDBAR TIL",
            "Best før", "Siste forbruksdag", "Годен до", "Срок годности"
        )
        for (keyword in keywords) {
            val result = DateOcrParser.parseBestDate(listOf("PACKED 01.11.2026", "$keyword 24.11.2026"), referenceYear = 2026)
            assertEquals(LocalDate(2026, 11, 24), result?.date, keyword)
        }
        // Not inside a longer word: "cadena" is no "cad", "valg" no "val".
        assertEquals(LocalDate(2026, 11, 1), DateOcrParser.parseBestDate(listOf("PACKED 01.11.2026", "CADENA 24.11.2026"), referenceYear = 2026)?.date)
        assertEquals(LocalDate(2026, 11, 1), DateOcrParser.parseBestDate(listOf("PACKED 01.11.2026", "VALG 24.11.2026"), referenceYear = 2026)?.date)
    }

    @Test
    fun testMonthNamesInEveryAppLanguage() {
        val cases = mapOf(
            "15 DÉC 2026" to LocalDate(2026, 12, 15),
            "15. MÄR 2027" to LocalDate(2027, 3, 15),
            "15 OKT. 2026" to LocalDate(2026, 10, 15),
            "12 ABR 2027" to LocalDate(2027, 4, 12),
            "3 AOÛT 2027" to LocalDate(2027, 8, 3),
            "20 juillet 2027" to LocalDate(2027, 7, 20),
            "1 MAJ 2027" to LocalDate(2027, 5, 1),
            "5 DES 2026" to LocalDate(2026, 12, 5),
            "7 DIC 26" to LocalDate(2026, 12, 7),
            "15 ОКТ 2026" to LocalDate(2026, 10, 15),
            "9 мая 2027" to LocalDate(2027, 5, 9),
            "15 November 2026" to LocalDate(2026, 11, 15)
        )
        for ((line, date) in cases) {
            assertEquals(date, DateOcrParser.parseBestDate(listOf(line), referenceYear = 2026)?.date, line)
        }
    }
}
