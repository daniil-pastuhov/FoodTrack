package com.foodtracker.model

import com.foodtracker.util.DateUtils
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShelfLifeTest {

    private val today = LocalDate(2026, 9, 22)

    private fun daysFromToday(days: Int) = LocalDate.fromEpochDays(today.toEpochDays() + days)

    @Test
    fun learnsThePlainSpanBetweenAddAndExpiry() {
        assertEquals(14, ShelfLife.learnableShelfLife(today, daysFromToday(14)))
    }

    @Test
    fun learnsAZeroDaySpanForSomethingExpiringTheDayItIsAdded() {
        assertEquals(0, ShelfLife.learnableShelfLife(today, today))
    }

    @Test
    fun doesNotLearnFromAnItemAddedAlreadyExpired() {
        // Says nothing about how long a *fresh* one lasts, so it must not be remembered.
        assertNull(ShelfLife.learnableShelfLife(today, daysFromToday(-1)))
    }

    @Test
    fun doesNotLearnAnImplausiblyLongSpan() {
        assertEquals(
            ShelfLife.MAX_LEARNABLE_DAYS,
            ShelfLife.learnableShelfLife(today, daysFromToday(ShelfLife.MAX_LEARNABLE_DAYS))
        )
        assertNull(ShelfLife.learnableShelfLife(today, daysFromToday(ShelfLife.MAX_LEARNABLE_DAYS + 1)))
    }

    @Test
    fun aDateReadOffThePackagingBeatsEverySuggestion() {
        val detected = daysFromToday(3)
        val suggestion = ShelfLife.suggest(
            today = today,
            detectedDate = detected,
            rememberedDays = 30,
            categoryDefaultDays = 60
        )
        assertEquals(detected, suggestion.date)
        assertEquals(ShelfLifeSource.DETECTED, suggestion.source)
        assertNull(suggestion.days)
        assertFalse(suggestion.isInformative)
    }

    @Test
    fun theRememberedShelfLifeBeatsTheCategoryDefault() {
        val suggestion = ShelfLife.suggest(today = today, rememberedDays = 30, categoryDefaultDays = 60)
        assertEquals(daysFromToday(30), suggestion.date)
        assertEquals(ShelfLifeSource.REMEMBERED, suggestion.source)
        assertEquals(30, suggestion.days)
        assertTrue(suggestion.isInformative)
    }

    @Test
    fun theCategoryDefaultIsUsedForAProductNeverAddedBefore() {
        val suggestion = ShelfLife.suggest(today = today, rememberedDays = null, categoryDefaultDays = 60)
        assertEquals(daysFromToday(60), suggestion.date)
        assertEquals(ShelfLifeSource.CATEGORY_DEFAULT, suggestion.source)
        assertEquals(60, suggestion.days)
        assertTrue(suggestion.isInformative)
    }

    @Test
    fun fallsBackToTheFormsLongstandingDefaultWhenNothingIsKnown() {
        val suggestion = ShelfLife.suggest(today = today)
        assertEquals(daysFromToday(ShelfLife.FALLBACK_DAYS), suggestion.date)
        assertEquals(ShelfLifeSource.FALLBACK, suggestion.source)
        // The fallback is the +7 days the form has always started from, so captioning it would
        // be describing behaviour the user has never been told about before.
        assertFalse(suggestion.isInformative)
    }

    @Test
    fun anImplausibleStoredValueDegradesToTheNextSourceRatherThanBeingSuggested() {
        val tooLong = ShelfLife.MAX_LEARNABLE_DAYS + 1
        val suggestion = ShelfLife.suggest(today = today, rememberedDays = tooLong, categoryDefaultDays = 60)
        assertEquals(ShelfLifeSource.CATEGORY_DEFAULT, suggestion.source)

        val negative = ShelfLife.suggest(today = today, rememberedDays = -5, categoryDefaultDays = -5)
        assertEquals(ShelfLifeSource.FALLBACK, negative.source)
    }

    @Test
    fun theLearnableWindowMatchesThePlausibleDateWindowUsedElsewhere() {
        // The OCR parser and the add/edit form already reject dates outside
        // MAX_PLAUSIBLE_EXPIRY_YEARS; learning a longer shelf life than that would remember a
        // date the rest of the app warns about.
        assertEquals(DateUtils.MAX_PLAUSIBLE_EXPIRY_YEARS * 365, ShelfLife.MAX_LEARNABLE_DAYS)
    }
}
