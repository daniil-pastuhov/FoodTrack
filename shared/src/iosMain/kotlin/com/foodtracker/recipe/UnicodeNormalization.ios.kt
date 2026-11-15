package com.foodtracker.recipe

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCompatibilityMapping

// Not used by the iOS app (it doesn't link `shared`, and has its own cleaner in RecipeWire.swift);
// here so the iOS targets still compile. Kotlin/Native has no Unicode script data and only BMP
// categories, so these are approximations by block; the JVM implementation is the tested one.

internal actual fun normalizeNfkc(text: String): String =
    NSString.create(string = text).precomposedStringWithCompatibilityMapping

internal actual fun wireCategory(codePoint: Int): WireCategory {
    if (codePoint >= 0x10000) {
        return when (codePoint) {
            in 0x20000..0x3FFFF -> WireCategory.LETTER // CJK extensions
            in 0xE0000..0xE007F -> WireCategory.FORMAT // tags
            else -> WireCategory.OTHER
        }
    }
    return when (codePoint.toChar().category) {
        CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER,
        CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER -> WireCategory.LETTER
        CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.COMBINING_SPACING_MARK -> WireCategory.MARK
        CharCategory.DECIMAL_DIGIT_NUMBER -> WireCategory.DIGIT
        CharCategory.CONTROL, CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR -> WireCategory.SPACE
        CharCategory.FORMAT -> WireCategory.FORMAT
        else -> WireCategory.OTHER
    }
}

internal actual fun wireScript(codePoint: Int): WireScript = when (codePoint) {
    in 0x41..0x5A, in 0x61..0x7A, 0xAA, 0xBA, in 0xC0..0x24F, in 0x250..0x2AF, in 0x1D00..0x1D25,
    in 0x1E00..0x1EFF, in 0x2C60..0x2C7F, in 0xA722..0xA7FF, in 0xAB30..0xAB64, in 0xFF21..0xFF5A -> WireScript.LATIN
    in 0x400..0x52F, in 0x1C80..0x1C88, in 0x2DE0..0x2DFF, in 0xA640..0xA69F -> WireScript.CYRILLIC
    in 0x370..0x3FF, in 0x1F00..0x1FFF -> WireScript.GREEK
    in 0x3040..0x309F, in 0x30A0..0x30FF, in 0x31F0..0x31FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF,
    in 0xF900..0xFAFF, in 0xFF66..0xFF9F, in 0x20000..0x3FFFF, in 0x0E00..0x0EFF, in 0x1780..0x17FF,
    in 0x1000..0x109F -> WireScript.UNSPACED
    else -> WireScript.OTHER
}
