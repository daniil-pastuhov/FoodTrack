package com.foodtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.components.ExpiryBadge
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.android.ui.theme.expiredStatusColors
import com.foodtracker.android.ui.theme.expiringStatusColors
import com.foodtracker.android.ui.theme.freshStatusColors
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.android.util.formatMonthYear
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.util.CalendarDay
import com.foodtracker.util.DateUtils
import com.foodtracker.util.monthGrid
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryStateHolder
import java.time.format.FormatStyle
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

private val WEEKDAY_HEADERS = listOf("M", "T", "W", "T", "F", "S", "S")

/** Below this width the day list stacks under the grid instead of sitting beside it. */
private val WIDE_LAYOUT_BREAKPOINT = 600.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    stateHolder: PantryStateHolder,
    modifier: Modifier = Modifier,
    // See PantryDashboardScreen's parameter of the same name.
    bottomOverlayClearance: Dp = 160.dp
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val today = remember { DateUtils.today() }
    var displayedMonth by remember { mutableStateOf(today) }
    var selectedDay by remember { mutableStateOf(today) }

    // Grouping by expiry date, and reducing each day's items to its single most urgent status,
    // are both derived once per data change in PantryStateHolder. The grouping used to be
    // recomputed here, and the per-day status was rebuilt inside every one of the 42 day cells,
    // on every recomposition.
    val grouped = state.itemsByExpiryDate
    val statusByDate = state.statusByExpiryDate

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = formatMonthYear(displayedMonth),
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        // Bottom nav-bar inset is already baked into bottomOverlayClearance (see MainActivity),
        // so the Scaffold must not reserve it a second time as innerPadding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            CalendarSummaryRow(
                expiredCount = state.expiredCount,
                expiringSoonCount = state.expiringSoonCount
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val isWideLayout = maxWidth >= WIDE_LAYOUT_BREAKPOINT

                if (isWideLayout) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        CalendarGridSection(
                            displayedMonth = displayedMonth,
                            today = today,
                            grouped = grouped,
                            statusByDate = statusByDate,
                            selectedDay = selectedDay,
                            onPrevMonth = { displayedMonth = displayedMonth.minus(DatePeriod(months = 1)) },
                            onNextMonth = { displayedMonth = displayedMonth.plus(DatePeriod(months = 1)) },
                            onSelectDay = { selectedDay = it },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        Divider(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                        )
                        DayDetailSection(
                            selectedDay = selectedDay,
                            items = grouped[selectedDay].orEmpty(),
                            bottomOverlayClearance = bottomOverlayClearance,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CalendarGridSection(
                            displayedMonth = displayedMonth,
                            today = today,
                            grouped = grouped,
                            statusByDate = statusByDate,
                            selectedDay = selectedDay,
                            onPrevMonth = { displayedMonth = displayedMonth.minus(DatePeriod(months = 1)) },
                            onNextMonth = { displayedMonth = displayedMonth.plus(DatePeriod(months = 1)) },
                            onSelectDay = { selectedDay = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Divider()
                        DayDetailSection(
                            selectedDay = selectedDay,
                            items = grouped[selectedDay].orEmpty(),
                            bottomOverlayClearance = bottomOverlayClearance,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pantry-wide urgency summary (not scoped to the displayed month) so it stays meaningful
 * while paging through past/future months, matching the counts shown on the dashboard.
 */
@Composable
private fun CalendarSummaryRow(
    expiredCount: Int,
    expiringSoonCount: Int,
    modifier: Modifier = Modifier
) {
    if (expiredCount == 0 && expiringSoonCount == 0) {
        Text(
            text = stringResource(R.string.calendar_summary_all_fresh),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        return
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        if (expiredCount > 0) {
            CalendarSummaryChip(
                text = stringResource(R.string.calendar_summary_expired, expiredCount),
                color = expiredStatusColors().foreground
            )
        }
        if (expiringSoonCount > 0) {
            CalendarSummaryChip(
                text = stringResource(R.string.calendar_summary_expiring_soon, expiringSoonCount),
                color = expiringStatusColors().foreground
            )
        }
    }
}

@Composable
private fun CalendarSummaryChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun CalendarGridSection(
    displayedMonth: LocalDate,
    today: LocalDate,
    grouped: Map<LocalDate, List<PantryRow>>,
    statusByDate: Map<LocalDate, ExpiryStatus>,
    selectedDay: LocalDate,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.calendar_previous_month))
            }
            IconButton(onClick = onNextMonth) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.calendar_next_month))
            }
        }

        // Remembered on the month: monthGrid allocates 42 CalendarDay + 42 LocalDate objects,
        // and it was being rebuilt on every recomposition of this grid — as was a 42-element
        // set of String keys, since the key lambda called LocalDate.toString().
        val days = remember(displayedMonth) { monthGrid(displayedMonth) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth()
        ) {
            gridItems(WEEKDAY_HEADERS) { label ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(2.dp)
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            gridItems(days, key = { it.date.toEpochDays() }) { day ->
                CalendarDayCell(
                    day = day,
                    isToday = day.date == today,
                    isSelected = day.date == selectedDay,
                    hasItems = grouped[day.date] != null,
                    status = statusByDate[day.date],
                    onClick = { onSelectDay(day.date) }
                )
            }
        }
    }
}

@Composable
private fun DayDetailSection(
    selectedDay: LocalDate,
    items: List<PantryRow>,
    modifier: Modifier = Modifier,
    bottomOverlayClearance: Dp = 160.dp
) {
    Column(modifier = modifier) {
        Text(
            text = formatLocalDate(selectedDay, FormatStyle.LONG),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.calendar_no_items_for_day),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            // Bottom padding clears the floating pill nav bar and scan/add buttons that
            // overlay this screen (see MainActivity).
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = bottomOverlayClearance)
            ) {
                items(items, key = { it.id }) { row ->
                    DayDetailRow(row = row)
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    day: CalendarDay,
    isToday: Boolean,
    isSelected: Boolean,
    hasItems: Boolean,
    status: ExpiryStatus?,
    onClick: () -> Unit
) {
    val indicatorColor = if (hasItems) dayIndicatorColor(status) else null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(
                    when {
                        isToday -> Modifier.background(BrandPrimary)
                        isSelected -> Modifier.background(BrandPrimary.copy(alpha = 0.15f))
                        else -> Modifier
                    }
                )
        ) {
            Text(
                text = day.date.dayOfMonth.toString(),
                color = when {
                    isToday -> androidx.compose.ui.graphics.Color.White
                    !day.inCurrentMonth -> TextSecondary.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.onBackground
                },
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .size(5.dp)
                .then(
                    if (indicatorColor != null) {
                        Modifier
                            .clip(CircleShape)
                            .background(indicatorColor)
                    } else {
                        Modifier
                    }
                )
        )
    }
}

/** Color of a day's dot reflects the most urgent item expiring that day, if any.
 *
 *  [status] is that most-urgent status, already reduced in PantryStateHolder. This used to take
 *  the day's whole item list and allocate a status list from it to scan three times — per cell,
 *  per recomposition, across all 42 cells. */
@Composable
private fun dayIndicatorColor(status: ExpiryStatus?): androidx.compose.ui.graphics.Color? =
    when (status) {
        null -> null
        ExpiryStatus.EXPIRED -> expiredStatusColors().foreground
        ExpiryStatus.EXPIRING_TODAY, ExpiryStatus.EXPIRING_SOON -> expiringStatusColors().foreground
        ExpiryStatus.FRESH -> freshStatusColors().foreground
    }

@Composable
private fun DayDetailRow(row: PantryRow) {
    val item = row.item
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Fastfood,
            contentDescription = null,
            tint = BrandPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!item.brand.isNullOrBlank()) {
                Text(
                    text = item.brand!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Both values already derived in PantryStateHolder.
        ExpiryBadge(daysRemaining = row.daysRemaining, status = row.status)
    }
}

