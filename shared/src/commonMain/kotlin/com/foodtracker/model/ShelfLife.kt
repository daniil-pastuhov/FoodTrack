package com.foodtracker.model

import com.foodtracker.util.DateUtils
import kotlinx.datetime.LocalDate

/** Where the expiry date pre-filled on a new item came from. Drives the caption shown under
 *  the date field, and distinguishes "the app guessed this" from "the packaging said so". */
enum class ShelfLifeSource {
    /** Read off the packaging - a GS1 AI(17)/(15) segment or the date OCR. Not a guess. */
    DETECTED,

    /** The shelf life this exact barcode was given the last time it was added. */
    REMEMBERED,

    /** The default configured for the product's category in Settings. */
    CATEGORY_DEFAULT,

    /** Nothing was known - [ShelfLife.FALLBACK_DAYS] from today. */
    FALLBACK
}

/** [days] is the shelf life the suggestion was built from, for the caption ("14 days"). It is
 *  null for [ShelfLifeSource.DETECTED], which is a date read off the packaging rather than a
 *  span, and so says nothing about how long the product keeps. */
data class ShelfLifeSuggestion(
    val date: LocalDate,
    val source: ShelfLifeSource,
    val days: Int? = null
) {
    /** True when this is a guess worth captioning. A detected date needs no explanation, and
     *  the fallback is the same +7 days the form has always started from. */
    val isInformative: Boolean
        get() = source == ShelfLifeSource.REMEMBERED || source == ShelfLifeSource.CATEGORY_DEFAULT
}

/**
 * Resolves which expiry date a freshly scanned/added item should start out with, and learns
 * shelf lives from items the user saves.
 *
 * Kept as a pure object with no repository access so both platforms can agree on the rule:
 * iOS reimplements this by hand in `Models/ShelfLife.swift` (nothing under `iosApp/` consumes
 * this module), so any change here has to be mirrored there.
 */
object ShelfLife {

    /** Upper bound on a shelf life the app will learn or suggest. Anything longer is almost
     *  certainly a mis-read year or a lot number rather than a real date, so it is ignored
     *  rather than remembered - one bad OCR read must not poison every future scan of that
     *  product. Matches the window [DateUtils.MAX_PLAUSIBLE_EXPIRY_YEARS] already applies to
     *  dates in the OCR parser and the add/edit form. */
    const val MAX_LEARNABLE_DAYS = DateUtils.MAX_PLAUSIBLE_EXPIRY_YEARS * 365

    /** Used when nothing at all is known about a product. This is the default the add/edit
     *  form has always started from. */
    const val FALLBACK_DAYS = 7

    /**
     * The shelf life to remember for a product the user just added, or null when the span is
     * not worth learning: a negative one (the item was added already expired, which says
     * nothing about how long a fresh one lasts) or an implausibly long one (see
     * [MAX_LEARNABLE_DAYS]).
     */
    fun learnableShelfLife(addedDate: LocalDate, expiryDate: LocalDate): Int? {
        val days = (expiryDate.toEpochDays() - addedDate.toEpochDays()).toInt()
        return if (days in 0..MAX_LEARNABLE_DAYS) days else null
    }

    /**
     * The expiry date to pre-fill, in precedence order:
     *
     *  1. [detectedDate] - read off the packaging by the GS1 parser or the date OCR. Always
     *     wins: it is the actual printed date, not a guess.
     *  2. [rememberedDays] - what this exact barcode was given last time.
     *  3. [categoryDefaultDays] - the default configured for its category in Settings.
     *  4. [FALLBACK_DAYS] from today.
     *
     * Implausible remembered/default values are skipped rather than used, so a bad value that
     * somehow reached storage degrades to the next source instead of suggesting nonsense.
     */
    fun suggest(
        today: LocalDate,
        detectedDate: LocalDate? = null,
        rememberedDays: Int? = null,
        categoryDefaultDays: Int? = null
    ): ShelfLifeSuggestion {
        if (detectedDate != null) {
            return ShelfLifeSuggestion(detectedDate, ShelfLifeSource.DETECTED)
        }
        rememberedDays?.takeIf { it.isUsable() }?.let {
            return ShelfLifeSuggestion(today.plusDays(it), ShelfLifeSource.REMEMBERED, it)
        }
        categoryDefaultDays?.takeIf { it.isUsable() }?.let {
            return ShelfLifeSuggestion(today.plusDays(it), ShelfLifeSource.CATEGORY_DEFAULT, it)
        }
        return ShelfLifeSuggestion(today.plusDays(FALLBACK_DAYS), ShelfLifeSource.FALLBACK, FALLBACK_DAYS)
    }

    private fun Int.isUsable(): Boolean = this in 0..MAX_LEARNABLE_DAYS

    private fun LocalDate.plusDays(days: Int): LocalDate =
        LocalDate.fromEpochDays(toEpochDays() + days)
}
