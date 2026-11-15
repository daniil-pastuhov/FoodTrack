package com.foodtracker.android.ui.design

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.model.ExpiryStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Up to here a fresh item reads "In 4 days"; past it, the date itself says more. */
private const val RELATIVE_FRESH_DAYS = 7

/** Whether a chip counts the days rather than showing the date. */
internal fun showsDaysLeft(status: ExpiryStatus, daysRemaining: Int): Boolean =
    status != ExpiryStatus.FRESH || daysRemaining <= RELATIVE_FRESH_DAYS

/**
 * The words a status chip shows: "1 day over", "Today", "Tomorrow", "In 4 days", "19 Nov", and
 * the year too when it isn't this one. Factual on purpose; a passed date is never "Expired!".
 */
fun expiryStatusLabel(
    resources: Resources,
    locale: Locale,
    status: ExpiryStatus,
    daysRemaining: Int,
    expiryDate: LocalDate,
    today: LocalDate
): String = when {
    daysRemaining < 0 -> resources.getQuantityString(R.plurals.status_days_over, -daysRemaining, -daysRemaining)
    daysRemaining == 0 -> resources.getString(R.string.status_today)
    daysRemaining == 1 -> resources.getString(R.string.status_tomorrow)
    showsDaysLeft(status, daysRemaining) ->
        resources.getQuantityString(R.plurals.expires_in_days, daysRemaining, daysRemaining)
    else -> {
        val skeleton = if (expiryDate.year == today.year) "dMMM" else "dMMMy"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .format(expiryDate.toJavaLocalDate())
    }
}

/**
 * How a pantry item stands: colour only when food needs attention, always an icon and words.
 * Fresh has no tint. [contentDescription] replaces [text] for screen readers, for wording that
 * reads better aloud ("one day past its date").
 */
@Composable
fun StatusChip(
    status: ExpiryStatus,
    text: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val colors = LarderTheme.colors
    val (container, content) = when (status) {
        ExpiryStatus.EXPIRED -> colors.expiredContainer to colors.onExpiredContainer
        ExpiryStatus.EXPIRING_TODAY -> colors.todayContainer to colors.onTodayContainer
        ExpiryStatus.EXPIRING_SOON -> colors.soonContainer to colors.onSoonContainer
        ExpiryStatus.FRESH -> Color.Transparent to colors.onSurfaceVariant
    }
    val icon = when (status) {
        ExpiryStatus.EXPIRED -> LarderIcons.History
        ExpiryStatus.EXPIRING_TODAY, ExpiryStatus.EXPIRING_SOON -> LarderIcons.Clock
        ExpiryStatus.FRESH -> LarderIcons.Check
    }
    Row(
        modifier = modifier
            .background(container, RoundedCornerShape(LarderRadius.sm))
            .padding(
                start = if (status == ExpiryStatus.FRESH) 0.dp else LarderSpace.s1,
                end = LarderSpace.s2,
                top = 1.dp,
                bottom = 1.dp
            )
            .then(
                if (contentDescription != null) {
                    Modifier.clearAndSetSemantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(icon, contentDescription = null, size = LarderIconSize.Small, tint = content)
        // One line wherever it fits; at the largest font scales a long status ("Просрочено 1 день
        // назад") wraps rather than being cut off mid-word, as on iOS.
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** A [StatusChip] that words itself with [expiryStatusLabel]. */
@Composable
fun StatusChip(
    status: ExpiryStatus,
    daysRemaining: Int,
    expiryDate: LocalDate,
    today: LocalDate,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    // A resource lookup and possibly a CLDR pattern, on every row of the list.
    val text = remember(resources, locale, status, daysRemaining, expiryDate, today) {
        expiryStatusLabel(resources, locale, status, daysRemaining, expiryDate, today)
    }
    StatusChip(status, text, modifier, contentDescription)
}

@PreviewLightDark
@Composable
internal fun StatusChipPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        StatusChip(ExpiryStatus.EXPIRED, "1 day over")
        StatusChip(ExpiryStatus.EXPIRING_TODAY, "Today")
        StatusChip(ExpiryStatus.EXPIRING_SOON, "Tomorrow")
        StatusChip(ExpiryStatus.FRESH, "In 4 days")
        StatusChip(ExpiryStatus.FRESH, "19 Nov")
    }
}
