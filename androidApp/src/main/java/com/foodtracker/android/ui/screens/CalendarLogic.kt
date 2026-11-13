package com.foodtracker.android.ui.screens

import androidx.annotation.StringRes
import com.foodtracker.android.R
import com.foodtracker.model.ExpiryStatus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** The mark's cap, so a busy day stays inside its cell. The section heading gives the true count. */
private const val MAX_DAY_COUNT = 9

/** The week holding the 4th always has its Thursday in that month, so its title stays put. */
private const val MONTH_ANCHOR_DAY = 4

/** What sits beside a day's count. The shape carries the status, so it reads without colour;
 *  fresh has no shape, only the plain count. */
internal enum class DayMarkShape { Square, Circle, Diamond, None }

internal fun dayMarkShape(status: ExpiryStatus): DayMarkShape = when (status) {
    ExpiryStatus.EXPIRED -> DayMarkShape.Square
    ExpiryStatus.EXPIRING_TODAY -> DayMarkShape.Circle
    ExpiryStatus.EXPIRING_SOON -> DayMarkShape.Diamond
    ExpiryStatus.FRESH -> DayMarkShape.None
}

internal fun dayCountLabel(count: Int): String =
    if (count > MAX_DAY_COUNT) "$MAX_DAY_COUNT+" else count.toString()

/** Tapping the selected day widens back to the whole agenda. */
internal fun toggledDay(selected: LocalDate?, tapped: LocalDate): LocalDate? =
    tapped.takeUnless { it == selected }

/**
 * Where the calendar rests after paging [months] from [anchor]: today in today's month, so the
 * week strip reopens on it, and otherwise a day whose week carries that month's title.
 */
internal fun pagedAnchor(anchor: LocalDate, months: Int, today: LocalDate): LocalDate {
    val target = LocalDate(anchor.year, anchor.month, MONTH_ANCHOR_DAY).plus(DatePeriod(months = months))
    return if (target.year == today.year && target.month == today.month) today else target
}

/** Swiping away the last unit of an essential item puts it on the shopping list, and says so. */
@StringRes
internal fun usedSnackbarText(addedToShoppingList: Boolean): Int =
    if (addedToShoppingList) R.string.item_added_to_shopping_list_snackbar else R.string.calendar_used_snackbar
