package com.foodtracker.parser

/**
 * One product line recovered from a till receipt.
 *
 * [confidence] is how sure the parser is that this is a product at all, not how sure it is of
 * the name. Callers should surface everything above [ReceiptParser.MIN_CONFIDENCE] as
 * *suggestions the user confirms* — receipt layouts vary by retailer and country, and this is
 * heuristic in a way barcode scanning is not.
 */
data class ReceiptLine(
    val rawText: String,
    val name: String,
    val quantity: Int,
    val confidence: Float
)

/**
 * Pulls product lines out of the raw OCR text of a supermarket receipt.
 *
 * The problem is subtraction rather than recognition: a receipt is mostly *not* products — a
 * store header and address, a date and till number, per-line prices, a totals block, a payment
 * method, a VAT summary and a loyalty footer. What's left after those are removed, minus the
 * price column, is roughly the shopping.
 *
 * Deliberately conservative: a line that can't be cleaned up into something name-shaped is
 * dropped rather than guessed at, because a wrong row costs the user an edit while a missing
 * row costs them a scan they were going to do anyway.
 *
 * Works the same way as [DateOcrParser] — score each line, keep what clears a threshold — and is
 * tested the same way, with one table per retailer layout.
 */
object ReceiptParser {

    /** Below this a line isn't offered at all. */
    const val MIN_CONFIDENCE = 0.5f

    /** Longest plausible quantity on a grocery receipt line; more than this and the leading
     *  number is far more likely to be part of the product name or a code. */
    private const val MAX_QUANTITY = 99

    /**
     * Words that mark a line as part of the receipt's furniture rather than its contents.
     * Matched case-insensitively against the whole line, in the three languages the app ships
     * in — a receipt is in the language of the shop, not of the phone.
     */
    private val NON_PRODUCT_KEYWORDS = listOf(
        // Totals
        "total", "subtotal", "sum", "totalt", "delsum", "å betale", "a betale",
        "итого", "итог", "сумма", "к оплате",
        // Tax
        "vat", "mva", "tax", "ндс", "налог",
        // Payment and change
        "cash", "card", "visa", "mastercard", "debit", "credit", "contactless",
        "kontant", "kort", "bankaxept", "vipps",
        "наличные", "карта", "оплата", "сдача", "change", "veksel",
        // Receipt furniture
        "receipt", "kvittering", "чек", "кассир", "cashier", "terminal", "till",
        "thank you", "takk for", "спасибо", "welcome", "velkommen",
        "org.nr", "orgnr", "vat no", "инн", "ккт", "смена",
        "balance", "saldo", "bonus", "points", "poeng", "баллы",
        "discount", "rabatt", "скидка", "savings"
    )

    /** A price column at the end of a line: "1.99", "12,50", "£1.99", "*2.30", "1.99 A". */
    private val TRAILING_PRICE = Regex("""[*xX£€$₽]?\s*\d{1,4}[.,]\d{2}\s*[A-Za-zА-Яа-я]?\s*$""")

    /** "2 x ", "2x ", "3 @ " at the start of a line. */
    private val LEADING_QUANTITY = Regex("""^(\d{1,2})\s*[x×@]\s*""", RegexOption.IGNORE_CASE)

    /** " x2", " x 2" at the end of what's left after the price is stripped. */
    private val TRAILING_QUANTITY = Regex("""\s*[x×]\s*(\d{1,2})\s*$""", RegexOption.IGNORE_CASE)

    /**
     * A weighed-goods / unit-price continuation line: "0.412 kg @ 29.90/kg", "@ 2.99 each".
     *
     * These belong to the product line *above* them rather than being products themselves, and
     * they carry that product's price — which is why a name on its own line still counts as a
     * product when one of these follows it.
     *
     * The `[@x×]` marker is required, so an ordinary product whose name opens with a pack size
     * ("250 G BUTTER   2.10") is not mistaken for one.
     */
    private val CONTINUATION_LINE = Regex(
        """^\s*(\d+[.,]?\d*\s*(kg|g|l|ml|stk|pcs)\b\s*[@x×]|[@x×]\s*\d).*""",
        RegexOption.IGNORE_CASE
    )

    /** A date, time or a long bare code — never a product. */
    private val DATE_OR_CODE_LINE = Regex("""^\s*(\d{1,4}[-./]\d{1,2}[-./]\d{1,4}|\d{1,2}:\d{2}|[\d\s*#-]{6,})\s*$""")

    /**
     * Parses [ocrLines] into the product lines worth offering, in the order they appeared.
     *
     * Each line is decided on its own except for one lookahead: a product sold by weight prints
     * its name on one line and its "0.412 kg @ 29.90/kg" on the next, so a name with no price of
     * its own still counts when such a line follows it. Nothing else is stitched together —
     * trying to rejoin wrapped names produces worse rows than dropping the fragment does.
     */
    fun parseLines(ocrLines: List<String>): List<ReceiptLine> =
        ocrLines.indices
            .mapNotNull { index ->
                parseLine(ocrLines[index], nextLine = ocrLines.getOrNull(index + 1))
            }
            .filter { it.confidence >= MIN_CONFIDENCE }

    /** Visible for testing the per-line decision without the threshold applied. [nextLine] is
     *  only consulted to spot a weighed-goods continuation; see [parseLines]. */
    internal fun parseLine(rawLine: String, nextLine: String? = null): ReceiptLine? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null
        if (isNonProduct(trimmed)) return null

        // A product line carries a price. That single fact removes the store name, the address,
        // the date, the till number and the loyalty footer far more reliably than any keyword
        // list can, since those vary per retailer while the price column does not. The
        // exception is a weighed product, whose price sits on the continuation line below it.
        val hasOwnPrice = TRAILING_PRICE.containsMatchIn(trimmed)
        val pricedByNextLine = nextLine?.let { CONTINUATION_LINE.matches(it.trim()) } == true
        if (!hasOwnPrice && !pricedByNextLine) return null

        var working = TRAILING_PRICE.replace(trimmed, "").trim()

        var quantity = 1
        LEADING_QUANTITY.find(working)?.let { match ->
            val parsed = match.groupValues[1].toIntOrNull()
            if (parsed != null && parsed in 1..MAX_QUANTITY) {
                quantity = parsed
                working = working.removeRange(match.range).trim()
            }
        }
        if (quantity == 1) {
            TRAILING_QUANTITY.find(working)?.let { match ->
                val parsed = match.groupValues[1].toIntOrNull()
                if (parsed != null && parsed in 1..MAX_QUANTITY) {
                    quantity = parsed
                    working = working.removeRange(match.range).trim()
                }
            }
        }

        // Leftover separators and unit-price fragments the price strip didn't cover, e.g. the
        // "@ 1.99" in "2 @ 1.99 MILK" once the trailing total has already gone.
        working = working
            .replace(Regex("""^\s*[@*xX×-]\s*"""), "")
            .replace(Regex("""\s*\d{1,4}[.,]\d{2}\s*(/\s*\w+)?\s*$"""), "")
            .trim()
            .trim('-', '*', '.', ',', ':', ' ')

        val name = normalizeName(working)
        if (name.isEmpty()) return null

        return ReceiptLine(
            rawText = trimmed,
            name = name,
            quantity = quantity,
            confidence = confidenceFor(name)
        )
    }

    private fun isNonProduct(line: String): Boolean {
        val lowered = line.lowercase()
        if (NON_PRODUCT_KEYWORDS.any { lowered.contains(it) }) return true
        if (CONTINUATION_LINE.matches(line)) return true
        if (DATE_OR_CODE_LINE.matches(line)) return true
        return false
    }

    /**
     * Collapses runs of whitespace and drops the punctuation receipts pad names with, while
     * leaving the name otherwise as printed: `MLK SEMI 2PT` stays abbreviated rather than being
     * expanded into a guess. Matching it against products the app already knows is a separate
     * job (see `NameSimilarity`), and one the user gets to confirm.
     */
    private fun normalizeName(candidate: String): String =
        candidate.replace(Regex("""\s+"""), " ").trim().trim('.', ',', '-', '*', ':', ' ')

    /**
     * How product-shaped the recovered name looks.
     *
     * Letters are what distinguishes a product from a code, so the score is driven by how much
     * of the name is alphabetic and how much of it there is. A two-character remnant of a
     * wrapped line and a line that was mostly digits both land below [MIN_CONFIDENCE].
     */
    private fun confidenceFor(name: String): Float {
        val letters = name.count { it.isLetter() }
        if (letters < 2) return 0f

        val letterFraction = letters.toFloat() / name.length
        var score = 0.45f + 0.45f * letterFraction

        // Very short names are usually fragments; very long ones are usually prose from the
        // header or footer that dodged the keyword list.
        when {
            letters < 3 -> score -= 0.25f
            name.length > 40 -> score -= 0.20f
        }
        return score.coerceIn(0f, 1f)
    }
}
