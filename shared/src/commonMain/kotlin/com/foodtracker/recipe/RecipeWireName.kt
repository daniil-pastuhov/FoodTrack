package com.foodtracker.recipe

/*
 * Pantry and allergen names as the server accepts them: functions/CONTRACT.md, "Name rules", and
 * functions/src/core/inputSafety.ts, which is authoritative. iOS mirrors this by hand
 * (RecipeWire.cleanName); functions/test/fixtures/names.json is the table all three are checked on.
 *
 * The server normalises a name and then refuses it whole if anything is out of place; the client
 * cleans it first so an honest name always passes and still reads as the person wrote it
 * ("Crème fraîche 15%", "Ben & Jerry's", "0,5", "Φέτα", "可口可乐 Zero", "חלב 3%"). Cleaning never
 * tries to catch the server's "suspicious" rules about meta words, encoded text or repetition: a
 * name refused for those is left out of the request (see RecipePaid), and nothing is rewritten to
 * slip past them.
 *
 * Everything here works on code points, as the server does: lengths are code points, not UTF-16
 * units.
 */

/** Raw input past this many UTF-16 units is cut before any work is done on it. */
private const val RAW_INPUT_LIMIT = 400
const val MAX_WIRE_NAME_WORDS = 8
const val MAX_WIRE_NAME_PART_LENGTH = 28
/** Combining marks kept on one letter; on a Latin, Cyrillic or Greek letter only one. */
const val MAX_WIRE_MARKS_PER_LETTER = 4
const val MAX_WIRE_MARKS_PER_CASED_LETTER = 1

private const val SPACE = 0x20
private const val HYPHEN = 0x2D
private const val APOSTROPHE = 0x27
private const val AMPERSAND = 0x26
private const val FULL_STOP = 0x2E
private const val PERCENT = 0x25
private val PUNCTUATION = setOf(APOSTROPHE, PERCENT, 0x2C, FULL_STOP, 0x2F, AMPERSAND)

/**
 * A name as the server accepts it, or null when nothing is left:
 * - NFKC, apostrophe and dash look-alikes folded to `'` and `-`, the Arabic percent sign and
 *   decimal separator to `%` and `.`, invisible and bidirectional characters removed, every other
 *   space or control turned into a space;
 * - letters of every script, decimal digits of every script, space and hyphen kept; any other
 *   character (emoji, symbols, other punctuation) becomes a space; combining marks kept on a
 *   letter, at most 4 (1 on a Latin, Cyrillic or Greek letter), never the same one twice in a row;
 * - `'` `%` `,` `.` `/` `&` kept only where the server allows them, a space otherwise;
 * - a word part mixing Latin, Cyrillic and Greek letters (a look-alike typed on another keyboard)
 *   folded to its main script, or dropped if it can't be;
 * - hyphen-separated parts over 28 code points dropped (except in a script written without
 *   spaces: Chinese, Japanese, Thai...), at most 8 words, at most 40 code points (cut at a word end
 *   when there is one);
 * - null if marks are more than 60% of the letters and marks, as the server refuses that.
 */
fun cleanWireName(name: String): String? {
    var text = collapse(keepAllowed(codePointsOf(normalizeWireText(name.take(RAW_INPUT_LIMIT)))))
    // Each step can change what the others see (a cut can leave a "1," at the end); a few rounds
    // reach a name none of them changes.
    repeat(8) {
        val next = cut(shapeWords(collapse(fixPunctuation(text))))
        if (next == text) return finished(text)
        text = next
    }
    return finished(text)
}

private fun finished(text: List<Int>): String? {
    if (text.none { isLetter(it) || isDigit(it) }) return null
    val letters = text.count(::isLetter)
    val marks = text.count(::isMark)
    if (marks * 5 > (letters + marks) * 3) return null
    return stringOf(text)
}

/**
 * An allergen as sent, only when cleaning kept every letter and digit of it: null when a word
 * or a part was dropped (a part over 28 characters, a word mixing look-alike scripts that can't be
 * folded, past 8 words or 40 characters, nothing left). Unlike a pantry item, an allergen must
 * never reach the server shortened, since a recipe written without part of it could be unsafe.
 */
fun cleanAllergenName(name: String): String? {
    val cleaned = cleanWireName(name) ?: return null
    fun meaningful(text: String) = codePointsOf(normalizeWireText(text)).count { isLetter(it) || isDigit(it) }
    return cleaned.takeIf { meaningful(name.take(RAW_INPUT_LIMIT)) == meaningful(it) && name.length <= RAW_INPUT_LIMIT }
}

/** The server's normalisation: NFKC, look-alikes folded, invisible characters removed, spaces. */
internal fun normalizeWireText(raw: String): String {
    // Folded before NFKC too: NFKC turns `´` into a space and a combining accent.
    val folded = codePointsOf(normalizeNfkc(foldLookAlikes(raw)).replace('⁄', '/'))
    val visible = ArrayList<Int>(folded.size)
    for (cp in folded) {
        when {
            isInvisible(cp) -> Unit
            wireCategory(cp) == WireCategory.SPACE -> visible.add(SPACE)
            else -> visible.add(cp)
        }
    }
    // Again: removing an invisible character can bring a letter and its accent together.
    return foldLookAlikes(normalizeNfkc(stringOf(visible)))
}

private fun foldLookAlikes(text: String): String = buildString(text.length) {
    for (c in text) {
        append(
            when (c) {
                in APOSTROPHES -> '\''
                in '‐'..'―', '−', '﹘', '﹣', '－' -> '-'
                '٪' -> '%' // Arabic percent sign
                '٫' -> '.' // Arabic decimal separator
                else -> c
            }
        )
    }
}

private const val APOSTROPHES = "‘’‛ʼʹ`´′"

/** The server's INVISIBLE: format characters (zero-width spaces and joiners, bidi controls, soft
 *  hyphen, BOM, tags), variation selectors, Hangul fillers, the Mongolian selectors and the
 *  combining grapheme joiner. */
private fun isInvisible(codePoint: Int): Boolean =
    wireCategory(codePoint) == WireCategory.FORMAT ||
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF || codePoint == 0x115F || codePoint == 0x1160 ||
        codePoint == 0x3164 || codePoint == 0xFFA0 || codePoint in 0x180B..0x180F || codePoint == 0x034F

private fun isLetter(cp: Int?) = cp != null && wireCategory(cp) == WireCategory.LETTER
private fun isMark(cp: Int?) = cp != null && wireCategory(cp) == WireCategory.MARK
private fun isDigit(cp: Int?) = cp != null && wireCategory(cp) == WireCategory.DIGIT

/** A letter or a combining mark (which only ever follows one): what `'` `&` `.` may follow. */
private fun isWordy(cp: Int?) = isLetter(cp) || isMark(cp)

private fun confusable(cp: Int): Int = if (!isLetter(cp)) -1 else when (wireScript(cp)) {
    WireScript.LATIN -> LATIN
    WireScript.CYRILLIC -> CYRILLIC
    WireScript.GREEK -> GREEK
    else -> -1
}

/** Every character the server could accept kept, every other one a space; combining marks only
 *  on a letter, a few per letter, never the same one twice in a row. */
private fun keepAllowed(text: List<Int>): List<Int> {
    val out = ArrayList<Int>(text.size)
    var base = -1
    var run = 0
    for (cp in text) {
        when (wireCategory(cp)) {
            WireCategory.LETTER -> {
                out.add(cp)
                base = cp
                run = 0
            }
            WireCategory.MARK -> {
                // A stray mark, one past the limit, or a repeat ("Zalgo" text) is dropped.
                val max = if (base >= 0 && confusable(base) >= 0) MAX_WIRE_MARKS_PER_CASED_LETTER else MAX_WIRE_MARKS_PER_LETTER
                if (base >= 0 && run < max && out.lastOrNull() != cp) {
                    out.add(cp)
                    run++
                }
            }
            else -> {
                base = -1
                run = 0
                val kept = isDigit(cp) || cp == SPACE || cp == HYPHEN || cp in PUNCTUATION
                out.add(if (kept) cp else SPACE)
            }
        }
    }
    return out
}

/** Runs of spaces and of hyphens collapsed to one, spaces trimmed. */
private fun collapse(text: List<Int>): List<Int> {
    val out = ArrayList<Int>(text.size)
    for (cp in text) {
        if ((cp == SPACE || cp == HYPHEN) && out.lastOrNull() == cp) continue
        out.add(cp)
    }
    while (out.lastOrNull() == SPACE) out.removeAt(out.lastIndex)
    val start = out.indexOfFirst { it != SPACE }
    return if (start <= 0) out else out.subList(start, out.size).toList()
}

/** The server's position rules for `'` `%` `,` `.` `/` `&`: one out of place becomes a space. */
private fun fixPunctuation(text: List<Int>): List<Int> {
    val out = text.toMutableList()
    for (i in text.indices) {
        val c = text[i]
        if (c !in PUNCTUATION) continue
        val prev = text.getOrNull(i - 1)
        val next = text.getOrNull(i + 1)
        val allowed = when (c) {
            APOSTROPHE -> isWordy(prev) && isLetter(next)
            AMPERSAND -> i > 0 && i < text.size - 1 && (isWordy(prev) || prev == SPACE) && (isLetter(next) || next == SPACE)
            FULL_STOP -> (isWordy(prev) && (next == SPACE || isDigit(next))) || (isDigit(prev) && isDigit(next))
            PERCENT -> isDigit(prev) || (prev == SPACE && isDigit(text.getOrNull(i - 2)))
            else -> isDigit(prev) && isDigit(next)
        }
        if (!allowed) out[i] = SPACE
    }
    return out
}

private fun List<Int>.splitOn(separator: Int): List<List<Int>> {
    val out = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    for (cp in this) {
        if (cp == separator) {
            out.add(current)
            current = mutableListOf()
        } else {
            current.add(cp)
        }
    }
    out.add(current)
    return out
}

private fun List<List<Int>>.joinedWith(separator: Int): List<Int> {
    val out = mutableListOf<Int>()
    forEachIndexed { i, part ->
        if (i > 0) out.add(separator)
        out.addAll(part)
    }
    return out
}

/** Whether a part has a letter of a script written without spaces: no per-part length limit. */
private fun isUnspaced(part: List<Int>) = part.any { isLetter(it) && wireScript(it) == WireScript.UNSPACED }

/** Mixed-script parts folded, long parts dropped, at most [MAX_WIRE_NAME_WORDS] words. */
private fun shapeWords(text: List<Int>): List<Int> = text.splitOn(SPACE)
    .mapNotNull { word ->
        word.splitOn(HYPHEN)
            .mapNotNull { part -> part.takeIf { it.size <= MAX_WIRE_NAME_PART_LENGTH || isUnspaced(it) }?.let(::singleScript) }
            .joinedWith(HYPHEN)
            .takeIf { joined -> joined.any { it != HYPHEN } }
    }
    .take(MAX_WIRE_NAME_WORDS)
    .joinedWith(SPACE)

private const val LATIN = 0
private const val CYRILLIC = 1
private const val GREEK = 2

/**
 * A part with letters of more than one of Latin, Cyrillic and Greek, as the server refuses it
 * (MIXED_SCRIPT), folded to the script most of its letters are in (ties: Latin, Cyrillic, Greek)
 * when every other one has a look-alike there (`Мilk` → `Milk`, `Мoлоко` → `Молоко`), else to the
 * next script that works; null when none does.
 */
private fun singleScript(part: List<Int>): List<Int>? {
    val counts = IntArray(3)
    for (cp in part) confusable(cp).takeIf { it >= 0 }?.let { counts[it]++ }
    if (counts.count { it > 0 } < 2) return part
    val order = listOf(LATIN, CYRILLIC, GREEK).filter { counts[it] > 0 }.sortedByDescending { counts[it] }
    for (target in order) foldTo(part, target)?.let { return it }
    return null
}

private fun foldTo(part: List<Int>, target: Int): List<Int>? = part.map { cp ->
    val from = confusable(cp)
    if (from < 0 || from == target) cp else LOOK_ALIKES[from][target][cp] ?: return null
}

private fun table(vararg pairs: String): Map<Int, Int> = pairs.associate { it[0].code to it[1].code }

/**
 * Look-alike letters, [from][to], both cases written out (no case mapping at run time, so every
 * platform folds the same way). Cyrillic to Latin is the server's HOMOGLYPHS.
 */
private val LOOK_ALIKES: Array<Array<Map<Int, Int>>> = arrayOf(
    arrayOf(
        emptyMap(),
        table(
            "aа", "bв", "eе", "kк", "mм", "hн", "oо", "pр", "cс", "tт", "yу", "xх", "iі",
            "AА", "BВ", "EЕ", "KК", "MМ", "HН", "OО", "PР", "CС", "TТ", "YУ", "XХ", "IІ"
        ),
        table(
            "aα", "eε", "iι", "kκ", "vν", "oο", "pρ", "tτ", "uυ", "xχ",
            "AΑ", "BΒ", "EΕ", "ZΖ", "HΗ", "IΙ", "KΚ", "MΜ", "NΝ", "OΟ", "PΡ", "TΤ", "YΥ", "XΧ"
        )
    ),
    arrayOf(
        table(
            "аa", "вb", "еe", "кk", "мm", "нh", "оo", "рp", "сc", "тt", "уy", "хx", "іi", "јj", "ѕs", "ԁd", "ӏl", "ԛq", "ԝw", "үy", "һh",
            "АA", "ВB", "ЕE", "КK", "МM", "НH", "ОO", "РP", "СC", "ТT", "УY", "ХX", "ІI", "ЈJ", "ЅS", "ԀD", "ӀI", "ԚQ", "ԜW", "ҮY", "ҺH"
        ),
        emptyMap(),
        table(
            "аα", "еε", "кκ", "оο", "рρ", "тτ", "хχ", "іι",
            "АΑ", "ВΒ", "ЕΕ", "НΗ", "ІΙ", "КΚ", "МΜ", "ОΟ", "РΡ", "ТΤ", "ХΧ"
        )
    ),
    arrayOf(
        table(
            "αa", "εe", "ιi", "κk", "νv", "οo", "ρp", "τt", "υu", "χx",
            "ΑA", "ΒB", "ΕE", "ΖZ", "ΗH", "ΙI", "ΚK", "ΜM", "ΝN", "ΟO", "ΡP", "ΤT", "ΥY", "ΧX"
        ),
        table(
            "αа", "εе", "κк", "οо", "ρр", "τт", "χх", "ιі",
            "ΑА", "ΒВ", "ΕЕ", "ΗН", "ΙІ", "ΚК", "ΜМ", "ΟО", "ΡР", "ΤТ", "ΧХ"
        ),
        emptyMap()
    )
)

/** At most [MAX_WIRE_NAME_LENGTH] code points, cut at a word end when the name has one. */
private fun cut(text: List<Int>): List<Int> {
    if (text.size <= MAX_WIRE_NAME_LENGTH) return text
    val head = text.subList(0, MAX_WIRE_NAME_LENGTH)
    val atWordEnd = text[MAX_WIRE_NAME_LENGTH] == SPACE
    val lastSpace = head.lastIndexOf(SPACE)
    val cut = if (!atWordEnd && lastSpace >= 0) head.subList(0, lastSpace) else head
    return collapse(cut)
}

internal fun codePointsOf(text: String): List<Int> {
    val out = ArrayList<Int>(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            out.add(((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + 0x10000)
            i += 2
        } else {
            out.add(c.code)
            i++
        }
    }
    return out
}

internal fun stringOf(codePoints: List<Int>): String = buildString(codePoints.size) {
    for (cp in codePoints) {
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            append((0xD800 + (v shr 10)).toChar())
            append((0xDC00 + (v and 0x3FF)).toChar())
        } else {
            append(cp.toChar())
        }
    }
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
