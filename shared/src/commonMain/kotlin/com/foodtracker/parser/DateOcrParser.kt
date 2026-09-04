package com.foodtracker.parser

import kotlinx.datetime.LocalDate

data class DateOcrResult(
    val date: LocalDate,
    val rawMatch: String,
    val confidence: Float,
    val formatDescription: String
)

object DateOcrParser {

    private val KEYWORD_PATTERN = Regex(
        """(?i)\b(exp|bb|bbd|best\s*before|best\s*by|use\s*by|expires?|val|venc|a\s*cons(?:umer)?)\b"""
    )

    private val MONTH_NAMES = mapOf(
        "jan" to 1, "ene" to 1, "janv" to 1,
        "feb" to 2, "fev" to 2,
        "mar" to 3, "mrz" to 3,
        "apr" to 4, "avr" to 4,
        "may" to 5, "mai" to 5,
        "jun" to 6, "juin" to 6,
        "jul" to 7, "juil" to 7,
        "aug" to 8, "août" to 8, "aout" to 8,
        "sep" to 9, "sept" to 9,
        "oct" to 10, "okt" to 10,
        "nov" to 11,
        "dec" to 12, "dez" to 12, "déc" to 12
    )

    /**
     * Parses a list of recognized text lines from OCR and returns the highest confidence expiration date found.
     */
    fun parseBestDate(lines: List<String>, referenceYear: Int = 2026): DateOcrResult? {
        val candidates = mutableListOf<DateOcrResult>()

        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            val hasKeywordOnLine = KEYWORD_PATTERN.containsMatchIn(line)
            val hasKeywordNearby = hasKeywordOnLine ||
                    (lineIndex > 0 && KEYWORD_PATTERN.containsMatchIn(lines[lineIndex - 1]))

            // Try different matchers on line
            extractDatesFromText(line, hasKeywordNearby, referenceYear, candidates)
        }

        return candidates.maxByOrNull { it.confidence }
    }

    private fun extractDatesFromText(
        text: String,
        hasKeywordNearby: Boolean,
        referenceYear: Int,
        out: MutableList<DateOcrResult>
    ) {
        // Pattern 1: DD/MM/YYYY or DD.MM.YYYY or DD-MM-YYYY
        // e.g. 24.11.2026 or 24/11/26
        val dmyRegex = Regex("""\b(\d{1,2})[\.\/\-](\d{1,2})[\.\/\-](\d{2,4})\b""")
        for (match in dmyRegex.findAll(text)) {
            val d = match.groupValues[1].toIntOrNull() ?: continue
            val m = match.groupValues[2].toIntOrNull() ?: continue
            val yRaw = match.groupValues[3].toIntOrNull() ?: continue
            val y = normalizeYear(yRaw, referenceYear) ?: continue

            if (isValidDate(y, m, d, referenceYear)) {
                val conf = if (hasKeywordNearby) 0.95f else 0.70f
                out.add(DateOcrResult(LocalDate(y, m, d), match.value, conf, "DD.MM.YY(YY)"))
            }
        }

        // Pattern 2: YYYY-MM-DD or YYYY.MM.DD or YYYY/MM/DD (ISO standard)
        val ymdRegex = Regex("""\b(20\d{2})[\.\/\-](\d{1,2})[\.\/\-](\d{1,2})\b""")
        for (match in ymdRegex.findAll(text)) {
            val y = match.groupValues[1].toIntOrNull() ?: continue
            val m = match.groupValues[2].toIntOrNull() ?: continue
            val d = match.groupValues[3].toIntOrNull() ?: continue

            if (isValidDate(y, m, d, referenceYear)) {
                val conf = if (hasKeywordNearby) 0.98f else 0.75f
                out.add(DateOcrResult(LocalDate(y, m, d), match.value, conf, "YYYY-MM-DD"))
            }
        }

        // Pattern 3: DD MMM YYYY or DD MMM YY (e.g. 15 NOV 2026 or 28 OCT 25)
        val textualMonthRegex = Regex("""\b(\d{1,2})\s+([a-zA-Z]{3,4})\.?\s+(\d{2,4})\b""")
        for (match in textualMonthRegex.findAll(text)) {
            val d = match.groupValues[1].toIntOrNull() ?: continue
            val monthStr = match.groupValues[2].lowercase()
            val m = findMonth(monthStr) ?: continue
            val yRaw = match.groupValues[3].toIntOrNull() ?: continue
            val y = normalizeYear(yRaw, referenceYear) ?: continue

            if (isValidDate(y, m, d, referenceYear)) {
                val conf = if (hasKeywordNearby) 0.99f else 0.85f
                out.add(DateOcrResult(LocalDate(y, m, d), match.value, conf, "DD MMM YYYY"))
            }
        }

        // Pattern 4: MMM DD YYYY (e.g. NOV 15 2026)
        val monthFirstRegex = Regex("""\b([a-zA-Z]{3,4})\.?\s+(\d{1,2}),?\s+(\d{2,4})\b""")
        for (match in monthFirstRegex.findAll(text)) {
            val monthStr = match.groupValues[1].lowercase()
            val m = findMonth(monthStr) ?: continue
            val d = match.groupValues[2].toIntOrNull() ?: continue
            val yRaw = match.groupValues[3].toIntOrNull() ?: continue
            val y = normalizeYear(yRaw, referenceYear) ?: continue

            if (isValidDate(y, m, d, referenceYear)) {
                val conf = if (hasKeywordNearby) 0.99f else 0.85f
                out.add(DateOcrResult(LocalDate(y, m, d), match.value, conf, "MMM DD YYYY"))
            }
        }

        // Pattern 5: MM/YY or MM/YYYY (Common on canned items & long shelf-life)
        val monthYearOnlyRegex = Regex("""\b(0[1-9]|1[0-2])[\.\/\-](20\d{2}|\d{2})\b""")
        for (match in monthYearOnlyRegex.findAll(text)) {
            val m = match.groupValues[1].toIntOrNull() ?: continue
            val yRaw = match.groupValues[2].toIntOrNull() ?: continue
            val y = normalizeYear(yRaw, referenceYear) ?: continue

            if (m in 1..12 && y in (referenceYear - 2)..(referenceYear + 10)) {
                val lastDay = daysInMonth(y, m)
                // Slightly lower confidence because of no day specified
                val conf = if (hasKeywordNearby) 0.85f else 0.55f
                out.add(DateOcrResult(LocalDate(y, m, lastDay), match.value, conf, "MM/YY"))
            }
        }
    }

    private fun findMonth(str: String): Int? {
        val prefix = str.take(3)
        return MONTH_NAMES[prefix] ?: MONTH_NAMES[str]
    }

    private fun normalizeYear(rawYear: Int, referenceYear: Int): Int? {
        return when {
            rawYear in 2000..2099 -> rawYear
            rawYear in 0..99 -> {
                // If rawYear is 26, it's 2026. If rawYear is 99, it's 1999.
                if (rawYear <= 50) 2000 + rawYear else 1900 + rawYear
            }
            else -> null
        }
    }

    private fun isValidDate(year: Int, month: Int, day: Int, referenceYear: Int): Boolean {
        // Expiration dates should be somewhat plausible (from 1 year ago to 8 years in future)
        if (year !in (referenceYear - 1)..(referenceYear + 8)) return false
        if (month !in 1..12) return false
        if (day !in 1..daysInMonth(year, month)) return false
        return true
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
