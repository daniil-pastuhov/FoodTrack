package com.foodtracker.recipe

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCompatibilityMapping

// Not used by the iOS app (it doesn't link `shared`); here so the iOS targets still compile.
internal actual fun normalizeNfkc(text: String): String =
    NSString.create(string = text).precomposedStringWithCompatibilityMapping
