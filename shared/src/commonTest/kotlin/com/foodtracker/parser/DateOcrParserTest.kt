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

    /** A keyword printed at the end of a longer word is not one: the boundary before it counts
     *  as much as the one after it. The keyword-free first date wins (both score 0.70). */
    @Test
    fun testKeywordAtTheEndOfALongerWordIsNoKeyword() {
        for (word in listOf("LOT-XEXP", "DECAD", "BOBB", "INTERVAL", "AMHD", "XDDM", "OVERBB")) {
            assertEquals(
                LocalDate(2026, 11, 1),
                DateOcrParser.parseBestDate(listOf("PACKED 01.11.2026", "$word 24.11.2026"), referenceYear = 2026)?.date,
                word
            )
        }
        // The same keywords on their own do count, so the cases above fail only for the boundary.
        for (word in listOf("EXP", "CAD", "BB", "VAL", "MHD", "DDM")) {
            assertEquals(
                LocalDate(2026, 11, 24),
                DateOcrParser.parseBestDate(listOf("PACKED 01.11.2026", "LOT-X $word 24.11.2026"), referenceYear = 2026)?.date,
                word
            )
        }
    }

    /** Every entry of the month table, in every language, read as its own month both day-first
     *  and month-first, in lower and upper case. The list is the parser's table, entry for entry. */
    @Test
    fun testEveryMonthNameInTheTable() {
        val months = mapOf(
            1 to listOf("jan", "ene", "janv", "янв"),
            2 to listOf("feb", "fev", "fév", "фев"),
            3 to listOf("mar", "mrz", "mär", "мар"),
            4 to listOf("apr", "avr", "abr", "апр"),
            5 to listOf("may", "mai", "maj", "мая", "май"),
            6 to listOf("jun", "juin", "июн"),
            7 to listOf("jul", "juil", "juillet", "июл"),
            8 to listOf("aug", "août", "aout", "aoû", "ago", "авг"),
            9 to listOf("sep", "sept", "сен"),
            10 to listOf("oct", "okt", "окт"),
            11 to listOf("nov", "ноя"),
            12 to listOf("dec", "dez", "déc", "dic", "des", "дек")
        )
        for ((month, names) in months) {
            for (name in names) {
                for (spelled in listOf(name, name.uppercase())) {
                    val date = LocalDate(2027, month, 15)
                    assertEquals(date, DateOcrParser.parseBestDate(listOf("15 $spelled 2027"), referenceYear = 2026)?.date, "15 $spelled 2027")
                    assertEquals(date, DateOcrParser.parseBestDate(listOf("$spelled 15 2027"), referenceYear = 2026)?.date, "$spelled 15 2027")
                }
            }
        }
    }

    @Test
    fun testMonthFirstDates() {
        val cases = mapOf(
            "NOV 15 2026" to LocalDate(2026, 11, 15),
            "Nov. 15, 2026" to LocalDate(2026, 11, 15),
            "BEST BY DÉC 15 2026" to LocalDate(2026, 12, 15),
            "MÄR 15 2027" to LocalDate(2027, 3, 15),
            "ОКТ 15 2026" to LocalDate(2026, 10, 15),
            "годен до ОКТ 15 26" to LocalDate(2026, 10, 15)
        )
        for ((line, date) in cases) {
            val result = DateOcrParser.parseBestDate(listOf(line), referenceYear = 2026)
            assertEquals(date, result?.date, line)
            assertEquals("MMM DD YYYY", result?.formatDescription, line)
        }
        // Not the end of a longer word: "XNOV 15 2026" has no month.
        assertNull(DateOcrParser.parseBestDate(listOf("XNOV 15 2026"), referenceYear = 2026))
    }
}
