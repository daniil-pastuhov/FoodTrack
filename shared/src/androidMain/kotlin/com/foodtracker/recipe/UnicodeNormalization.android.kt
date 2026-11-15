package com.foodtracker.recipe

import java.text.Normalizer

internal actual fun normalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

internal actual fun wireCategory(codePoint: Int): WireCategory = when (Character.getType(codePoint).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
    Character.MODIFIER_LETTER, Character.OTHER_LETTER -> WireCategory.LETTER
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> WireCategory.MARK
    Character.DECIMAL_DIGIT_NUMBER -> WireCategory.DIGIT
    Character.CONTROL, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> WireCategory.SPACE
    Character.FORMAT -> WireCategory.FORMAT
    else -> WireCategory.OTHER
}

internal actual fun wireScript(codePoint: Int): WireScript {
    if (!Character.isValidCodePoint(codePoint)) return WireScript.OTHER
    return when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.LATIN -> WireScript.LATIN
        Character.UnicodeScript.CYRILLIC -> WireScript.CYRILLIC
        Character.UnicodeScript.GREEK -> WireScript.GREEK
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR -> WireScript.UNSPACED
        else -> WireScript.OTHER
    }
}
