package com.foodtracker.viewmodel

/**
 * The Calendar's agenda buckets, in display order. "This week" and "Next week" are Monday-start
 * calendar weeks, matching the week strip and month grid; [TODAY] and [TOMORROW] are checked
 * first, so on a Sunday tomorrow is still [TOMORROW] rather than [NEXT_WEEK].
 *
 * iOS reimplements this by hand in `Views/CalendarView.swift` (nothing under `iosApp/` consumes
 * this module), so a change to the bucket boundaries has to be ported there too.
 */
enum class AgendaBucket {
    OVERDUE,
    TODAY,
    TOMORROW,
    THIS_WEEK,
    NEXT_WEEK,
    LATER
}

/** One non-empty agenda section, its rows ordered by expiry date and then name. */
data class AgendaSection(
    val bucket: AgendaBucket,
    val rows: List<PantryRow>
)
