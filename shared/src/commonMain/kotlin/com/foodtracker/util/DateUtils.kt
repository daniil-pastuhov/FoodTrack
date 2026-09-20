package com.foodtracker.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

object DateUtils {
    /** How far from the current year an expiry date can be before it is considered implausible
     *  (a mis-read year, a lot number mistaken for a date, ...). Shared by the OCR parser, which
     *  drops candidates outside it, and the add/edit form, which warns the user about them.
     *  iOS mirrors this in `ExpiryDatePlausibility` (LocalModels.swift). */
    const val MAX_PLAUSIBLE_EXPIRY_YEARS = 10

    @OptIn(ExperimentalTime::class)
    fun today(): LocalDate {
        return Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    }

    fun isPlausibleExpiryDate(date: LocalDate, today: LocalDate = today()): Boolean =
        isPlausibleExpiryYear(date.year, today.year)

    fun isPlausibleExpiryYear(year: Int, referenceYear: Int): Boolean =
        year in (referenceYear - MAX_PLAUSIBLE_EXPIRY_YEARS)..(referenceYear + MAX_PLAUSIBLE_EXPIRY_YEARS)
}
