package com.foodtracker.recipe

import java.text.Normalizer

internal actual fun normalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
