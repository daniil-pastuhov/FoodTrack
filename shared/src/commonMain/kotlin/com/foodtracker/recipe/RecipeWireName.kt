package com.foodtracker.recipe

/*
 * Pantry and allergen names as the server accepts them: functions/CONTRACT.md, "Name rules", and
 * functions/src/core/inputSafety.ts, which is authoritative. iOS mirrors this by hand.
 *
 * The server normalises a name and then refuses it whole if anything is out of place; the client
 * cleans it first so an honest name always passes and still reads as the person wrote it
 * ("Crème fraîche 15%", "Ben & Jerry's", "0,5"). Cleaning never tries to catch the server's
 * "suspicious" rules about meta words, encoded text or repetition: a name refused for those is
 * left out of the request (see RecipePaid), and nothing is rewritten to slip past them.
 */

/** Raw input past this many UTF-16 units is cut before any work is done on it. */
private const val RAW_INPUT_LIMIT = 400
const val MAX_WIRE_NAME_WORDS = 8
const val MAX_WIRE_NAME_PART_LENGTH = 28

/**
 * A name as the server accepts it, or null when nothing is left:
 * - NFKC, apostrophe and dash look-alikes folded to `'` and `-`, invisible and bidirectional
 *   characters removed, every other space or control turned into a space;
 * - letters of the Latin and Cyrillic scripts, ASCII digits, space and hyphen kept; any other
 *   letter or digit (Greek, Chinese, Arabic-Indic digits...) and any other character becomes a
 *   space; one combining mark per letter;
 * - `'` `%` `,` `.` `/` `&` kept only where the server allows them, a space otherwise;
 * - a word mixing Latin and Cyrillic letters (a look-alike typed on the other keyboard) folded to
 *   its main script, or dropped if it can't be;
 * - hyphen-separated parts over 28 characters dropped, at most 8 words, at most 40 characters
 *   (cut at a word end when there is one).
 */
fun cleanWireName(name: String): String? {
    var text = collapse(keepAllowed(normalizeWireText(name.take(RAW_INPUT_LIMIT))))
    // Each step can change what the others see (a cut can leave a "1," at the end); a few rounds
    // reach a name none of them changes.
    repeat(8) {
        val next = cut(shapeWords(collapse(fixPunctuation(text))))
        if (next == text) return text.takeIf { it.any { c -> c.isWireLetter() || c in '0'..'9' } }
        text = next
    }
    return text.takeIf { it.any { c -> c.isWireLetter() || c in '0'..'9' } }
}

/**
 * An allergen as sent, only when cleaning kept every letter and digit of it: null when a word
 * or a part was dropped (another script, a non-ASCII digit, a part over 28 characters, past 8
 * words or 40 characters). Unlike a pantry item, an allergen must never reach the server
 * shortened, since a recipe written without part of it could be unsafe.
 */
fun cleanAllergenName(name: String): String? {
    val cleaned = cleanWireName(name) ?: return null
    fun meaningful(text: String) = normalizeWireText(text).count { it.isLetterOrDigit() }
    return cleaned.takeIf { meaningful(name.take(RAW_INPUT_LIMIT)) == meaningful(it) && name.length <= RAW_INPUT_LIMIT }
}

/** The server's normalisation: NFKC, look-alikes folded, invisible characters removed, spaces. */
internal fun normalizeWireText(raw: String): String {
    // Folded before NFKC too: NFKC turns `´` into a space and a combining accent.
    val folded = normalizeNfkc(foldLookAlikes(raw)).replace('\u2044', '/')
    val visible = StringBuilder(folded.length)
    var i = 0
    while (i < folded.length) {
        val c = folded[i]
        val pair = c.isHighSurrogate() && i + 1 < folded.length && folded[i + 1].isLowSurrogate()
        val codePoint = if (pair) ((c.code - 0xD800) shl 10) + (folded[i + 1].code - 0xDC00) + 0x10000 else c.code
        when {
            isInvisible(codePoint, c) -> Unit
            !pair && c.category in SPACE_CATEGORIES -> visible.append(' ')
            pair -> visible.append(c).append(folded[i + 1])
            else -> visible.append(c)
        }
        i += if (pair) 2 else 1
    }
    // Again: removing an invisible character can bring a letter and its accent together.
    return foldLookAlikes(normalizeNfkc(visible.toString()))
}

private fun foldLookAlikes(text: String): String = buildString(text.length) {
    for (c in text) {
        append(
            when (c) {
                in APOSTROPHES -> '\''
                in '\u2010'..'\u2015', '\u2212', '\uFE58', '\uFE63', '\uFF0D' -> '-'
                else -> c
            }
        )
    }
}

private const val APOSTROPHES = "\u2018\u2019\u201B\u02BC\u02B9\u0060\u00B4\u2032"

private val SPACE_CATEGORIES = setOf(
    CharCategory.CONTROL, CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR
)

/** The server's INVISIBLE: format characters (zero-width spaces and joiners, bidi controls, soft
 *  hyphen, BOM, tags), variation selectors, Hangul fillers, the Mongolian selectors and the
 *  combining grapheme joiner. */
private fun isInvisible(codePoint: Int, char: Char): Boolean = when {
    codePoint < 0x10000 -> char.category == CharCategory.FORMAT ||
        codePoint in 0xFE00..0xFE0F || codePoint == 0x115F || codePoint == 0x1160 || codePoint == 0x3164 ||
        codePoint == 0xFFA0 || codePoint in 0x180B..0x180F || codePoint == 0x034F
    // The supplementary format characters and variation selectors.
    else -> codePoint in 0xE0000..0xE007F || codePoint in 0xE0100..0xE01EF || codePoint in 0x1D173..0x1D17A ||
        codePoint in 0x1BCA0..0x1BCA3 || codePoint in 0x13430..0x1343F || codePoint == 0x110BD || codePoint == 0x110CD
}

/** Latin letters as European languages write them: ASCII, Latin-1, Latin Extended-A and -B, and
 *  Latin Extended Additional (Vietnamese and the like). Narrower than the server's Latin script
 *  (which also has IPA and phonetic letters); those become a space here. */
internal fun Char.isLatinLetter(): Boolean = isLetter() && when (code) {
    in 0x41..0x5A, in 0x61..0x7A, 0xAA, 0xBA, in 0xC0..0x24F, in 0x1E00..0x1EFF -> true
    else -> false
}

internal fun Char.isCyrillicLetter(): Boolean = isLetter() && code in 0x400..0x52F

private fun Char.isWireLetter(): Boolean = isLatinLetter() || isCyrillicLetter()

private const val PUNCTUATION = "'%,./&"

/** Every character the server could accept kept, every other one a space; at most one combining
 *  mark, and only right after a letter. */
private fun keepAllowed(text: String): String = buildString(text.length) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            // Outside the Basic Multilingual Plane: emoji, rare CJK, maths letters. All refused.
            append(' ')
            i += 2
            continue
        }
        when {
            c.isWireLetter() || c in '0'..'9' || c == ' ' || c == '-' || c in PUNCTUATION -> append(c)
            c.category == CharCategory.NON_SPACING_MARK -> {
                // A stray mark, or a second one on the same letter ("Zalgo" text), is dropped.
                // Only the shared Combining Diacritical Marks: the server counts the Cyrillic
                // block's own marks (U+0483..., U+2DE0...) as Cyrillic, so one on a Latin letter
                // would be refused as MIXED_SCRIPT.
                if (c.code in 0x300..0x36F && isNotEmpty() && last().isWireLetter()) append(c)
            }
            else -> append(' ')
        }
        i++
    }
}

/** Runs of spaces and of hyphens collapsed to one, trimmed. */
private fun collapse(text: String): String = buildString(text.length) {
    for (c in text.trim()) {
        if ((c == ' ' || c == '-') && isNotEmpty() && last() == c) continue
        append(c)
    }
}

/** The server's position rules for `'` `%` `,` `.` `/` `&`: one out of place becomes a space. */
private fun fixPunctuation(text: String): String {
    val chars = text.toCharArray()
    fun at(i: Int): Char? = text.getOrNull(i)
    fun letter(c: Char?) = c != null && c.isLetter()
    fun digit(c: Char?) = c != null && c in '0'..'9'
    for (i in text.indices) {
        val c = text[i]
        if (c !in PUNCTUATION) continue
        val prev = at(i - 1)
        val next = at(i + 1)
        val allowed = when (c) {
            '\'' -> letter(prev) && letter(next)
            '&' -> i > 0 && i < text.length - 1 && (letter(prev) || prev == ' ') && (letter(next) || next == ' ')
            '.' -> (letter(prev) && (next == ' ' || digit(next))) || (digit(prev) && digit(next))
            '%' -> digit(prev) || (prev == ' ' && digit(at(i - 2)))
            else -> digit(prev) && digit(next)
        }
        if (!allowed) chars[i] = ' '
    }
    return chars.concatToString()
}

/** Mixed-script words folded, long parts dropped, at most [MAX_WIRE_NAME_WORDS] words. */
private fun shapeWords(text: String): String = text.split(' ')
    .mapNotNull { word ->
        word.split('-')
            .mapNotNull { part -> part.takeIf { it.length <= MAX_WIRE_NAME_PART_LENGTH }?.let(::singleScript) }
            .joinToString("-")
            .takeIf { joined -> joined.any { it != '-' } }
    }
    .take(MAX_WIRE_NAME_WORDS)
    .joinToString(" ")

/**
 * A part with both Latin and Cyrillic letters, as the server refuses it (MIXED_SCRIPT), folded
 * to the script most of its letters are in when the others all have a look-alike there
 * (`Мilk` → `Milk`, `Мoлоко` → `Молоко`); null when it can't be.
 */
private fun singleScript(part: String): String? {
    val latin = part.count { it.isLatinLetter() }
    val cyrillic = part.count { it.isCyrillicLetter() }
    if (latin == 0 || cyrillic == 0) return part
    val toLatin = { fold(part, CYRILLIC_TO_LATIN) { it.isCyrillicLetter() } }
    val toCyrillic = { fold(part, LATIN_TO_CYRILLIC) { it.isLatinLetter() } }
    return if (latin >= cyrillic) toLatin() ?: toCyrillic() else toCyrillic() ?: toLatin()
}

private fun fold(part: String, table: Map<Char, Char>, foreign: (Char) -> Boolean): String? = buildString(part.length) {
    for (c in part) {
        if (!foreign(c)) {
            append(c)
            continue
        }
        val lower = c.lowercaseChar()
        val mapped = table[lower] ?: return null
        append(if (c == lower) mapped else mapped.uppercaseChar())
    }
}

/** The server's HOMOGLYPHS, lower case. */
private val CYRILLIC_TO_LATIN = mapOf(
    'а' to 'a', 'в' to 'b', 'е' to 'e', 'к' to 'k', 'м' to 'm', 'н' to 'h', 'о' to 'o', 'р' to 'p', 'с' to 'c',
    'т' to 't', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j', 'ѕ' to 's', 'ԁ' to 'd', 'ӏ' to 'l', 'ԛ' to 'q',
    'ԝ' to 'w', 'ү' to 'y', 'һ' to 'h'
)

/** The look-alikes the other way, for a Cyrillic word with a Latin letter typed in it. */
private val LATIN_TO_CYRILLIC = mapOf(
    'a' to 'а', 'b' to 'в', 'e' to 'е', 'k' to 'к', 'm' to 'м', 'h' to 'н', 'o' to 'о', 'p' to 'р', 'c' to 'с',
    't' to 'т', 'y' to 'у', 'x' to 'х', 'i' to 'і'
)

/** At most [MAX_WIRE_NAME_LENGTH] characters, cut at a word end when the name has one. */
private fun cut(text: String): String {
    if (text.length <= MAX_WIRE_NAME_LENGTH) return text
    val head = text.take(MAX_WIRE_NAME_LENGTH)
    val atWordEnd = text[MAX_WIRE_NAME_LENGTH] == ' '
    val cut = if (!atWordEnd && head.contains(' ')) head.substringBeforeLast(' ') else head
    return cut.trim()
}

/** Where a server refusal of a name points: `details.field` ("ingredients[3].name",
 *  "allergens[0]") and `details.rule`. */
data class RefusedName(val list: RefusedList, val index: Int, val rule: String?)

enum class RefusedList { INGREDIENT, ALLERGEN }

private val INGREDIENT_FIELD = Regex("""^ingredients\[(\d{1,3})](?:\.name)?$""")
private val ALLERGEN_FIELD = Regex("""^allergens\[(\d{1,3})]$""")

/** The refused name a callable failure's `details` points at, or null when it names none. */
fun refusedNameOf(field: String?, rule: String?): RefusedName? {
    if (field == null) return null
    INGREDIENT_FIELD.matchEntire(field)?.let { return RefusedName(RefusedList.INGREDIENT, it.groupValues[1].toInt(), rule) }
    ALLERGEN_FIELD.matchEntire(field)?.let { return RefusedName(RefusedList.ALLERGEN, it.groupValues[1].toInt(), rule) }
    return null
}
