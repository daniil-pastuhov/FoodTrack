package com.foodtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.components.ExpiryBadge
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.model.PantryItem
import com.foodtracker.android.util.formatMonthYear
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.util.CalendarDay
import com.foodtracker.util.DateUtils
import com.foodtracker.util.groupItemsByExpiryDate
import com.foodtracker.util.monthGrid
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
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsState()
    val today = remember { DateUtils.today() }
    var displayedMonth by remember { mutableStateOf(today) }
    var selectedDay by remember { mutableStateOf(today) }

    val grouped = remember(state.items) {
        groupItemsByExpiryDate(state.items)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = formatMonthYear(displayedMonth),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideLayout = maxWidth >= WIDE_LAYOUT_BREAKPOINT

            if (isWideLayout) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CalendarGridSection(
                        displayedMonth = displayedMonth,
                        today = today,
                        grouped = grouped,
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
                        today = today,
                        expiringSoonDays = state.settings.expiringSoonDays,
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
                        today = today,
                        expiringSoonDays = state.settings.expiringSoonDays,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarGridSection(
    displayedMonth: LocalDate,
    today: LocalDate,
    grouped: Map<LocalDate, List<PantryItem>>,
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
            gridItems(monthGrid(displayedMonth), key = { it.date.toString() }) { day ->
                CalendarDayCell(
                    day = day,
                    isToday = day.date == today,
                    isSelected = day.date == selectedDay,
                    itemCount = grouped[day.date]?.size ?: 0,
                    onClick = { onSelectDay(day.date) }
                )
            }
        }
    }
}

@Composable
private fun DayDetailSection(
    selectedDay: LocalDate,
    items: List<PantryItem>,
    today: LocalDate,
    expiringSoonDays: Int,
    modifier: Modifier = Modifier
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
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items, key = { it.id }) { item ->
                    DayDetailRow(item = item, today = today, expiringSoonDays = expiringSoonDays)
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
    itemCount: Int,
    onClick: () -> Unit
) {
    val hasItems = itemCount > 0
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
                    if (hasItems) {
                        Modifier
                            .clip(CircleShape)
                            .background(BrandPrimary)
                    } else {
                        Modifier
                    }
                )
        )
    }
}

@Composable
private fun DayDetailRow(item: PantryItem, today: LocalDate, expiringSoonDays: Int) {
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
        ExpiryBadge(
            daysRemaining = item.daysRemaining(today),
            status = item.expiryStatus(today, expiringSoonDays)
        )
    }
}

