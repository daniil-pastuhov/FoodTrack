package com.foodtracker.android.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.components.ExpiryBadge
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.android.ui.theme.expiredStatusColors
import com.foodtracker.android.ui.theme.expiringStatusColors
import com.foodtracker.android.ui.theme.freshStatusColors
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.android.util.formatMonthYear
import com.foodtracker.android.util.formatWeekdayDay
import com.foodtracker.android.util.narrowWeekdayNames
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.util.monthGrid
import com.foodtracker.util.weekDays
import com.foodtracker.util.weekStart
import com.foodtracker.viewmodel.AgendaBucket
import com.foodtracker.viewmodel.AgendaSection
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryStateHolder
import java.time.format.FormatStyle
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

/** Below this width the agenda stacks under the week strip / month grid instead of beside it. */
private val WIDE_LAYOUT_BREAKPOINT = 600.dp

/** The week strip pages over a fixed window of weeks centred on the current one — about ten
 *  years either way, which no pantry item plausibly outlives. */
private const val WEEK_PAGE_COUNT = 1040
private const val CURRENT_WEEK_PAGE = WEEK_PAGE_COUNT / 2

/** Rows under these headings span several days, so each row names its own date. */
private val MULTI_DAY_BUCKETS = setOf(
    AgendaBucket.OVERDUE,
    AgendaBucket.THIS_WEEK,
    AgendaBucket.NEXT_WEEK,
    AgendaBucket.LATER
)

/**
 * The Calendar tab: a swipeable week strip (or, from the title, the full month grid) over an
 * agenda of everything in the pantry bucketed by when it expires — Overdue first, then Today,
 * Tomorrow, the rest of this week, next week and later. Tapping a day narrows the agenda to that
 * day; tapping it again (or "Show all") widens it back.
 *
 * Agenda rows are the same actions as elsewhere: tap to edit, swipe right to use one, swipe left
 * to throw it away. Both swipes go through the root's handlers, so they get the same undo
 * snackbar, notification cancelling and essential-item shopping list behaviour as the dashboard
 * and scanner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    stateHolder: PantryStateHolder,
    onEditItem: (PantryItem) -> Unit,
    onUseOne: (PantryItem) -> Unit,
    onToss: (String) -> Unit,
    modifier: Modifier = Modifier,
    // See PantryDashboardScreen's parameter of the same name.
    bottomOverlayClearance: Dp = 160.dp
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    // From state, not remembered locally: it has to move at midnight together with the agenda.
    val today = state.today
    var showMonth by remember { mutableStateOf(false) }
    // Some date inside the week or month currently on screen; the title shows its month.
    var anchor by remember { mutableStateOf(today) }
    // Null means the whole agenda; a date narrows it to that day.
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    val onSelectDay: (LocalDate) -> Unit = { date ->
        selectedDay = if (date == selectedDay) null else date
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    MonthToggleTitle(
                        month = anchor,
                        showMonth = showMonth,
                        onToggle = {
                            // Open the other view on whatever the user is looking at.
                            selectedDay?.let { anchor = it }
                            showMonth = !showMonth
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        // Bottom nav-bar inset is already baked into bottomOverlayClearance (see FoodTrackerRoot),
        // so the Scaffold must not reserve it a second time as innerPadding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWideLayout = maxWidth >= WIDE_LAYOUT_BREAKPOINT

            val navigator: @Composable (Modifier) -> Unit = { navModifier ->
                AnimatedContent(
                    targetState = showMonth,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = navModifier,
                    label = "calendarNavigator"
                ) { month ->
                    if (month) {
                        MonthGrid(
                            month = anchor,
                            today = today,
                            selectedDay = selectedDay,
                            itemsByDate = state.itemsByExpiryDate,
                            statusByDate = state.statusByExpiryDate,
                            onPrevMonth = { anchor = firstOfMonth(anchor).plus(DatePeriod(months = -1)) },
                            onNextMonth = { anchor = firstOfMonth(anchor).plus(DatePeriod(months = 1)) },
                            onSelectDay = onSelectDay
                        )
                    } else {
                        WeekStrip(
                            today = today,
                            initialDate = anchor,
                            selectedDay = selectedDay,
                            itemsByDate = state.itemsByExpiryDate,
                            statusByDate = state.statusByExpiryDate,
                            onWeekShown = { anchor = it },
                            onSelectDay = onSelectDay
                        )
                    }
                }
            }
            val agenda: @Composable (Modifier) -> Unit = { agendaModifier ->
                AgendaList(
                    sections = state.agenda,
                    selectedDay = selectedDay,
                    selectedDayRows = selectedDay?.let { day ->
                        state.itemsByExpiryDate[day].orEmpty().sortedBy { it.sortName }
                    },
                    onShowAll = { selectedDay = null },
                    onEditItem = onEditItem,
                    onUseOne = onUseOne,
                    onToss = onToss,
                    bottomOverlayClearance = bottomOverlayClearance,
                    modifier = agendaModifier
                )
            }

            if (isWideLayout) {
                Row(modifier = Modifier.fillMaxSize()) {
                    navigator(Modifier.weight(1f).fillMaxHeight())
                    Divider(modifier = Modifier.fillMaxHeight().width(1.dp))
                    agenda(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    navigator(Modifier.fillMaxWidth())
                    Divider()
                    agenda(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

private fun firstOfMonth(date: LocalDate): LocalDate = LocalDate(date.year, date.month, 1)

@Composable
private fun MonthToggleTitle(month: LocalDate, showMonth: Boolean, onToggle: () -> Unit) {
    val toggleLabel = stringResource(if (showMonth) R.string.calendar_show_week else R.string.calendar_show_month)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = toggleLabel, onClick = onToggle)
            .semantics { contentDescription = toggleLabel }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(text = formatMonthYear(month), fontWeight = FontWeight.Bold)
        Icon(
            imageVector = if (showMonth) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null
        )
    }
}

/** One Monday-to-Sunday week per page; swiping pages a week at a time. */
@Composable
private fun WeekStrip(
    today: LocalDate,
    initialDate: LocalDate,
    selectedDay: LocalDate?,
    itemsByDate: Map<LocalDate, List<PantryRow>>,
    statusByDate: Map<LocalDate, ExpiryStatus>,
    onWeekShown: (LocalDate) -> Unit,
    onSelectDay: (LocalDate) -> Unit
) {
    val currentWeekStart = remember(today) { weekStart(today) }
    val pagerState = rememberPagerState(
        initialPage = CURRENT_WEEK_PAGE + currentWeekStart.daysUntil(weekStart(initialDate)) / 7
    ) { WEEK_PAGE_COUNT }
    val currentOnWeekShown by rememberUpdatedState(onWeekShown)
    LaunchedEffect(pagerState, currentWeekStart) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            // Thursday decides which month a week belongs to (ISO 8601), so a week split across
            // two months is titled by the one holding most of it.
            currentOnWeekShown(
                currentWeekStart.plus(DatePeriod(days = (page - CURRENT_WEEK_PAGE) * 7 + 3))
            )
        }
    }

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        WeekdayHeaderRow()
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            val days = remember(page, currentWeekStart) {
                weekDays(currentWeekStart.plus(DatePeriod(days = (page - CURRENT_WEEK_PAGE) * 7)))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                days.forEach { date ->
                    CalendarDayCell(
                        date = date,
                        dimmed = false,
                        isToday = date == today,
                        isSelected = date == selectedDay,
                        count = itemsByDate[date]?.size ?: 0,
                        status = statusByDate[date],
                        onClick = { onSelectDay(date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun MonthGrid(
    month: LocalDate,
    today: LocalDate,
    selectedDay: LocalDate?,
    itemsByDate: Map<LocalDate, List<PantryRow>>,
    statusByDate: Map<LocalDate, ExpiryStatus>,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDay: (LocalDate) -> Unit
) {
    // Remembered on the month: monthGrid allocates 42 CalendarDay + 42 LocalDate objects.
    val weeks = remember(month.year, month.month) { monthGrid(month).chunked(7) }
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.calendar_previous_month))
            }
            IconButton(onClick = onNextMonth) {
                Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.calendar_next_month))
            }
        }
        WeekdayHeaderRow()
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    CalendarDayCell(
                        date = day.date,
                        dimmed = !day.inCurrentMonth,
                        isToday = day.date == today,
                        isSelected = day.date == selectedDay,
                        count = itemsByDate[day.date]?.size ?: 0,
                        status = statusByDate[day.date],
                        onClick = { onSelectDay(day.date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun WeekdayHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth()) {
        narrowWeekdayNames().forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            )
        }
    }
}

/**
 * A day number over a count of the items expiring that day, coloured by the most urgent of them
 * ([status], already reduced in PantryStateHolder). A count rather than a dot, so a day with six
 * things to deal with doesn't look the same as a day with one.
 */
@Composable
private fun CalendarDayCell(
    date: LocalDate,
    dimmed: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    count: Int,
    status: ExpiryStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val countDescription = if (count > 0) {
        pluralStringResource(R.plurals.calendar_day_item_count, count, count)
    } else {
        null
    }
    val dateDescription = formatLocalDate(date, FormatStyle.LONG)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .aspectRatio(0.9f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(dateDescription, countDescription).joinToString(", ")
                selected = isSelected
            }
            .padding(vertical = 2.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .then(
                    when {
                        isToday -> Modifier.background(BrandPrimary)
                        isSelected -> Modifier.background(BrandPrimary.copy(alpha = 0.18f))
                        else -> Modifier
                    }
                )
        ) {
            Text(
                text = date.day.toString(),
                color = when {
                    isToday -> Color.White
                    dimmed -> TextSecondary.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.onBackground
                },
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        val badgeColor = statusColor(status)
        if (count > 0 && badgeColor != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(14.dp)
                    .widthIn(min = 14.dp)
                    .clip(CircleShape)
                    .background(if (dimmed) badgeColor.copy(alpha = 0.4f) else badgeColor)
                    .padding(horizontal = 3.dp)
            ) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    color = Color.White,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun statusColor(status: ExpiryStatus?): Color? =
    when (status) {
        null -> null
        ExpiryStatus.EXPIRED -> expiredStatusColors().foreground
        ExpiryStatus.EXPIRING_TODAY, ExpiryStatus.EXPIRING_SOON -> expiringStatusColors().foreground
        ExpiryStatus.FRESH -> freshStatusColors().foreground
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgendaList(
    sections: List<AgendaSection>,
    selectedDay: LocalDate?,
    selectedDayRows: List<PantryRow>?,
    onShowAll: () -> Unit,
    onEditItem: (PantryItem) -> Unit,
    onUseOne: (PantryItem) -> Unit,
    onToss: (String) -> Unit,
    bottomOverlayClearance: Dp,
    modifier: Modifier = Modifier
) {
    // Bottom padding clears the floating pill nav bar that overlays this screen (see
    // FoodTrackerRoot).
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = bottomOverlayClearance)
    ) {
        val rowItems: LazyListScope.(List<PantryRow>, Boolean) -> Unit = { rows, showDate ->
            items(rows, key = { it.id }) { row ->
                AgendaRow(
                    row = row,
                    showDate = showDate,
                    onClick = { onEditItem(row.item) },
                    onUseOne = { onUseOne(row.item) },
                    onToss = { onToss(row.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }

        if (selectedDay != null) {
            item(key = "dayHeader") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp)
                ) {
                    Text(
                        text = formatLocalDate(selectedDay, FormatStyle.FULL),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onShowAll) {
                        Text(stringResource(R.string.calendar_show_all))
                    }
                }
            }
            if (selectedDayRows.isNullOrEmpty()) {
                item(key = "dayEmpty") { EmptyMessage(stringResource(R.string.calendar_no_items_for_day)) }
            } else {
                rowItems(selectedDayRows, false)
            }
            return@LazyColumn
        }

        if (sections.isEmpty()) {
            item(key = "agendaEmpty") { EmptyMessage(stringResource(R.string.calendar_agenda_empty)) }
            return@LazyColumn
        }
        sections.forEach { section ->
            stickyHeader(key = "header_${section.bucket}") {
                AgendaSectionHeader(bucket = section.bucket, count = section.rows.size)
            }
            rowItems(section.rows, section.bucket in MULTI_DAY_BUCKETS)
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun AgendaSectionHeader(bucket: AgendaBucket, count: Int) {
    val title = stringResource(
        when (bucket) {
            AgendaBucket.OVERDUE -> R.string.calendar_section_overdue
            AgendaBucket.TODAY -> R.string.calendar_section_today
            AgendaBucket.TOMORROW -> R.string.calendar_section_tomorrow
            AgendaBucket.THIS_WEEK -> R.string.calendar_section_this_week
            AgendaBucket.NEXT_WEEK -> R.string.calendar_section_next_week
            AgendaBucket.LATER -> R.string.calendar_section_later
        }
    )
    val color = when (bucket) {
        AgendaBucket.OVERDUE -> expiredStatusColors().foreground
        AgendaBucket.TODAY, AgendaBucket.TOMORROW -> expiringStatusColors().foreground
        else -> TextSecondary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
    }
}

/**
 * One agenda item. Swipe right uses one unit, swipe left throws the whole item away; both are
 * also offered as accessibility actions, since a swipe alone isn't reachable from TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaRow(
    row: PantryRow,
    showDate: Boolean,
    onClick: () -> Unit,
    onUseOne: () -> Unit,
    onToss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val item = row.item
    // A plain `remember`, not `rememberSwipeToDismissBoxState` — see ItemCard for why a saveable
    // state brings an undone row back already dismissed.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionalThreshold
        )
    }
    val scope = rememberCoroutineScope()
    val currentItem by rememberUpdatedState(item)
    val currentOnUseOne by rememberUpdatedState(onUseOne)
    val currentOnToss by rememberUpdatedState(onToss)
    // Stable identity, as in ItemCard: SwipeToDismissBox keys its dismissal effect on it.
    val onDismiss = remember {
        { value: SwipeToDismissBoxValue ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    // Using one of several leaves the row in place with a lower count, so it
                    // has to slide back rather than stay swiped away.
                    val staysInList = currentItem.quantity > 1
                    currentOnUseOne()
                    if (staysInList) scope.launch { dismissState.reset() }
                }
                SwipeToDismissBoxValue.EndToStart -> currentOnToss()
                SwipeToDismissBoxValue.Settled -> {}
            }
        }
    }
    val usedLabel = stringResource(R.string.calendar_swipe_used)
    val tossedLabel = stringResource(R.string.calendar_swipe_tossed)

    SwipeToDismissBox(
        state = dismissState,
        onDismiss = onDismiss,
        backgroundContent = { AgendaSwipeBackground(dismissState.dismissDirection) },
        modifier = modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(usedLabel) { currentOnUseOne(); true },
                CustomAccessibilityAction(tossedLabel) { currentOnToss(); true }
            )
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.quantity > 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.quantity_format, item.quantity),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    }
                }
                val details = listOfNotNull(
                    if (showDate) formatWeekdayDay(item.expiryDate) else null,
                    item.storageLocation.takeIf { it.isNotBlank() },
                    item.brand?.takeIf { it.isNotBlank() }
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            ExpiryBadge(daysRemaining = row.daysRemaining, status = row.status)
        }
    }
}

/** Green + check leading (swipe right) for "used", red + trash trailing (swipe left) for
 *  "tossed". Blank while settled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgendaSwipeBackground(direction: SwipeToDismissBoxValue) {
    val (color, alignment, icon) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(freshStatusColors().foreground, Alignment.CenterStart, Icons.Default.Check)
        SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.error, Alignment.CenterEnd, Icons.Default.DeleteOutline)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, Alignment.Center, null)
    }
    Box(
        contentAlignment = alignment,
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp)
    ) {
        icon?.let { Icon(imageVector = it, contentDescription = null, tint = Color.White) }
    }
}
