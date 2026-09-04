package com.foodtracker.parser

import kotlinx.datetime.LocalDate

object GS1BarcodeParser {

    /**
     * Extracts an expiration date or best-before date from a GS1 barcode if present.
     * Application Identifiers:
     * - (17) / 17: Expiration date in format YYMMDD
     * - (15) / 15: Best before date in format YYMMDD
     */
    fun extractExpiryDate(rawBarcode: String): LocalDate? {
        val clean = rawBarcode.trim()

        // 1. Check bracketed format: (17)YYMMDD or (15)YYMMDD
        val bracketRegex = Regex("""\((17|15)\)\s*(\d{6})""")
        val bracketMatch = bracketRegex.find(clean)
        if (bracketMatch != null) {
            val yymmdd = bracketMatch.groupValues[2]
            return parseYYMMDD(yymmdd)
        }

        // 2. Check unbracketed format with AI 17 or 15
        // Usually preceded by (01) GTIN 14 digits, e.g. 010123456789012817YYMMDD
        val gtinFollowedRegex = Regex("""(?:01\d{14}|01\(\d{14}\))(?:17|15)(\d{6})""")
        val gtinMatch = gtinFollowedRegex.find(clean)
        if (gtinMatch != null) {
            return parseYYMMDD(gtinMatch.groupValues[1])
        }

        // 3. Simple prefix starting with 17 or 15 followed by 6 digits if total length matches GS1 segment
        val standaloneRegex = Regex("""^(?:17|15)(\d{6})""")
        val standaloneMatch = standaloneRegex.find(clean)
        if (standaloneMatch != null) {
            return parseYYMMDD(standaloneMatch.groupValues[1])
        }

        return null
    }

    private fun parseYYMMDD(yymmdd: String): LocalDate? {
        if (yymmdd.length != 6) return null
        return try {
            val yy = yymmdd.substring(0, 2).toInt()
            val mm = yymmdd.substring(2, 4).toInt()
            var dd = yymmdd.substring(4, 6).toInt()

            if (mm !in 1..12) return null

            // Century rule: YY from 00 to 50 is 2000-2050, 51-99 is 1951-1999
            val year = if (yy < 50) 2000 + yy else 1900 + yy

            // GS1 standard: if DD is 00, it represents the last day of the specified month
            if (dd == 0) {
                dd = daysInMonth(year, mm)
            } else if (dd !in 1..daysInMonth(year, mm)) {
                return null
            }

            LocalDate(year, mm, dd)
        } catch (_: Exception) {
            null
        }
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        return when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> 30
        }
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}
