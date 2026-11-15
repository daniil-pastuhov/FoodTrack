package com.foodtracker.recipe

/**
 * Unicode NFKC (compatibility composition): fullwidth letters and digits become plain ones, `½`
 * becomes `1⁄2`, ligatures split, and a letter followed by a combining accent becomes one
 * character where Unicode has one. Common Kotlin has no normaliser, so each platform supplies its
 * own (java.text.Normalizer on Android, Foundation on iOS).
 */
internal expect fun normalizeNfkc(text: String): String

/** The general categories the name rule tells apart. */
internal enum class WireCategory { LETTER, MARK, DIGIT, SPACE, FORMAT, OTHER }

/**
 * The scripts the name rule tells apart: the three whose letters look alike (one per word part),
 * the scripts written without spaces (no per-part length limit), and everything else.
 */
internal enum class WireScript { LATIN, CYRILLIC, GREEK, UNSPACED, OTHER }

/**
 * A code point's general category: letter (L*), mark (M*), decimal digit (Nd), space or control
 * (Cc, Zs, Zl, Zp), format (Cf), or other. Common Kotlin's `Char.category` only covers the BMP,
 * so each platform supplies it (java.lang.Character on Android).
 */
internal expect fun wireCategory(codePoint: Int): WireCategory

/**
 * A code point's Unicode Script property, as far as the name rule needs it: Latin, Cyrillic,
 * Greek; Han, Hiragana, Katakana, Thai, Lao, Khmer or Myanmar as UNSPACED; anything else OTHER.
 * Android uses java.lang.Character.UnicodeScript (the Unicode data of the device).
 */
internal expect fun wireScript(codePoint: Int): WireScript
