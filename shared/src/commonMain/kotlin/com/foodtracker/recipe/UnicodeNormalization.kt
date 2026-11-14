package com.foodtracker.recipe

/**
 * Unicode NFKC (compatibility composition): fullwidth letters and digits become plain ones, `½`
 * becomes `1⁄2`, ligatures split, and a letter followed by a combining accent becomes one
 * character where Unicode has one. Common Kotlin has no normaliser, so each platform supplies its
 * own (java.text.Normalizer on Android, Foundation on iOS).
 */
internal expect fun normalizeNfkc(text: String): String
