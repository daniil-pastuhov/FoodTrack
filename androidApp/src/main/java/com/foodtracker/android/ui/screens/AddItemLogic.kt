package com.foodtracker.android.ui.screens

import android.content.res.Resources
import androidx.annotation.StringRes
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.expiryStatusLabel
import com.foodtracker.android.util.daysRemainingLabel
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.ProductInfo
import kotlinx.datetime.LocalDate
import java.util.Locale

/** How many matching categories the Category field offers. */
private const val MAX_CATEGORY_SUGGESTIONS = 5

/** The quick dates under the date row, counted from today. */
internal enum class QuickDate(val days: Int, @param:StringRes val label: Int) {
    ThreeDays(3, R.string.quick_date_3_days),
    OneWeek(7, R.string.quick_date_1_week),
    TwoWeeks(14, R.string.quick_date_2_weeks),
    OneMonth(30, R.string.quick_date_1_month);

    fun date(today: LocalDate): LocalDate = LocalDate.fromEpochDays(today.toEpochDays() + days)

    /** Follows the date rather than the last tap, so a date picked by hand that lands on the same
     *  day explains itself the same way, and a new form opens with +1 Week on. */
    fun matches(date: LocalDate, today: LocalDate): Boolean = date == date(today)
}

/** Everything the form saves, to tell whether leaving it would throw anything away. */
internal data class AddItemFields(
    val name: String,
    val brand: String,
    val barcode: String,
    val imageUrl: String?,
    val expiryDate: LocalDate,
    val storageLocation: String,
    val category: String,
    val quantity: Int,
    val notes: String,
    val essential: Boolean,
    val notificationsEnabled: Boolean
)

/** The photo and name header appears once there is a photo or a barcode to show. */
internal fun showsAddItemHero(imageUrl: String?, barcode: String): Boolean =
    !imageUrl.isNullOrBlank() || barcode.isNotBlank()

/** Display names that continue what's typed, without the one it already says. Free text stays
 *  allowed, so an empty result is not a problem to report. */
internal fun categorySuggestions(typed: String, labels: List<String>): List<String> {
    if (typed.isBlank()) return emptyList()
    return labels
        .distinct()
        .filter { it.contains(typed, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
        .take(MAX_CATEGORY_SUGGESTIONS)
}

/** The scanned product when it is the one on this form. Type it in on the scanner opens a blank
 *  form with the last scan still published, and that product's allergens aren't this item's. */
internal fun scannedProductFor(barcode: String?, product: ProductInfo?): ProductInfo? =
    product?.takeIf { !barcode.isNullOrBlank() && it.barcode == barcode }

/** The words beside the date: "Tomorrow", then "In 20 days", "In 2 months", "In 36 years". Unlike
 *  a pantry row this never falls back to the date, which is already on the row. */
internal fun addItemDateLabel(
    resources: Resources,
    locale: Locale,
    status: ExpiryStatus,
    daysRemaining: Int,
    expiryDate: LocalDate,
    today: LocalDate
): String =
    if (daysRemaining >= 2) {
        daysRemainingLabel(resources, daysRemaining)
    } else {
        expiryStatusLabel(resources, locale, status, daysRemaining, expiryDate, today)
    }
