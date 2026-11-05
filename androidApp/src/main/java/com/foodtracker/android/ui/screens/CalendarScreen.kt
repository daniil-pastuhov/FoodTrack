package com.foodtracker.android.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.notification.NotificationHelper
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderArt
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderSwipeAction
import com.foodtracker.android.ui.design.LarderSwipeRow
import com.foodtracker.android.ui.design.LarderTabularNumbers
import com.foodtracker.android.ui.design.PantryRow as PantryRowView
import com.foodtracker.android.ui.design.ShellChromeEffect
import com.foodtracker.android.ui.design.categoryIcon
import com.foodtracker.android.ui.design.expiryStatusLabel
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.design.larderGroupItem
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.formatDayHeading
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.android.util.formatMonthYear
import com.foodtracker.android.util.formatWeekdayDay
import com.foodtracker.android.util.narrowWeekdayNames
import com.foodtracker.android.util.storageLocationLabel
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus

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

/** The month grid's pane on expanded windows. */
private val NavigatorPaneWidth = 360.dp

private val LocalDateSaver = Saver<LocalDate?, String>(
    save = { it?.toString() ?: "" },
    restore = { saved -> saved.takeIf { it.isNotEmpty() }?.let(LocalDate::parse) }
)

/**
 * The Calendar tab. On phones a swipeable week strip, or from the title the month grid, over an
 * agenda of everything in the pantry bucketed by when it's due. On expanded windows the month
 * grid sits beside the agenda and there is no week strip. Tapping a day narrows the agenda to
 * that day; tapping it again, or Show all, widens it back.
 *
 * A row opens the product detail, swipe right uses one and swipe left tosses the whole item, each
 * with Undo. Those two say "used" and "tossed" rather than the "removed" of the Root's handlers,
 * so the last unit and the toss run here; a use that leaves the row in place goes through
 * [onUseOne], which shares its wording with Home.
 *
 * [onEditItem] and [onToss] are unused now, until the Root stops passing them.
 */
@Suppress("UNUSED_PARAMETER")
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
    val context = LocalContext.current
    val navigator = LocalLarderNavigator.current
    val snackbars = LocalLarderSnackbars.current
    val expanded = isExpanded()
    // From state, not remembered locally: it has to move at midnight together with the agenda.
    val today = state.today
    var showMonth by rememberSaveable { mutableStateOf(false) }
    // Some date inside the week or month currently on screen; the title shows its month.
    var anchor by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf<LocalDate?>(today) }
    // Null means the whole agenda; a date narrows it to that day.
    var pickedDay by rememberSaveable(stateSaver = LocalDateSaver) { mutableStateOf<LocalDate?>(null) }

    val pantryEmpty = !state.isLoading && state.items.isEmpty()
    val selectedDay = pickedDay.takeUnless { pantryEmpty }
    val shownDate = anchor ?: today
    val onSelectDay: (LocalDate) -> Unit = { date ->
        if (!pantryEmpty) pickedDay = toggledDay(pickedDay, date)
    }
    val onPageMonth: (Int) -> Unit = { months -> anchor = pagedAnchor(shownDate, months, today) }

    ShellChromeEffect(hideScan = pantryEmpty)

    val onUsed: (PantryItem) -> Unit = { item ->
        if (item.quantity > 1) {
            onUseOne(item)
        } else {
            stateHolder.decrementQuantity(item.id) { removed ->
                NotificationHelper.cancelNotification(context, removed.id)
                snackbars.show(
                    context.getString(usedSnackbarText(removed.essential), removed.name),
                    context.getString(R.string.undo_action)
                ) {
                    stateHolder.restoreItem(removed, removedFromShoppingList = removed.essential)
                    NotificationHelper.scheduleNotification(context, removed)
                }
            }
        }
    }
    val onTossed: (PantryItem) -> Unit = { item ->
        stateHolder.deleteItem(item.id) { removed ->
            NotificationHelper.cancelNotification(context, removed.id)
            snackbars.show(
                context.getString(R.string.calendar_tossed_snackbar, removed.name),
                context.getString(R.string.undo_action)
            ) {
                stateHolder.restoreItem(removed)
                NotificationHelper.scheduleNotification(context, removed)
            }
        }
    }

    val monthPane: @Composable () -> Unit = {
        MonthGrid(
            month = shownDate,
            today = today,
            selectedDay = selectedDay,
            itemsByDate = state.itemsByExpiryDate,
            statusByDate = state.statusByExpiryDate,
            expanded = expanded,
            showKey = !pantryEmpty,
            onSelectDay = onSelectDay
        )
    }
    val agenda: @Composable (Modifier, (@Composable () -> Unit)?) -> Unit = { agendaModifier, leading ->
        when {
            state.isLoading -> Unit
            pantryEmpty -> EmptyAgenda(
                leading = leading,
                onScan = { navigator.navigate(LarderRoute.Scanner()) },
                bottomOverlayClearance = bottomOverlayClearance,
                modifier = agendaModifier
            )
            else -> AgendaList(
                sections = state.agenda,
                today = today,
                selectedDay = selectedDay,
                selectedDayRows = selectedDay?.let { day ->
                    state.itemsByExpiryDate[day].orEmpty().sortedBy { it.sortName }
                },
                leading = leading,
                onShowAll = { pickedDay = null },
                onOpen = { navigator.navigate(LarderRoute.ProductDetail(it.id)) },
                onUsed = onUsed,
                onTossed = onTossed,
                bottomOverlayClearance = bottomOverlayClearance,
                modifier = agendaModifier
            )
        }
    }

    val background = MaterialTheme.colorScheme.background
    if (expanded) {
        Row(modifier = modifier.fillMaxSize().background(background)) {
            Column(
                modifier = Modifier
                    .width(NavigatorPaneWidth)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState())
            ) {
                CalendarHeader(
                    month = shownDate,
                    expanded = true,
                    showMonth = true,
                    onToggle = {},
                    onPageMonth = onPageMonth
                )
                monthPane()
                // The Scan button and pill sit over the agenda's side; this pane only needs to
                // clear the pill when the grid runs long.
                Spacer(modifier = Modifier.height(bottomOverlayClearance))
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                if (selectedDay == null && !pantryEmpty) {
                    Text(
                        text = stringResource(R.string.calendar_pane_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .padding(start = LarderSpace.s4, end = LarderSpace.s4, top = LarderSpace.s5)
                            .heightIn(min = LarderSize.touch)
                            .semantics { heading() }
                    )
                }
                agenda(Modifier.weight(1f).fillMaxWidth(), null)
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(background)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            CalendarHeader(
                month = shownDate,
                expanded = false,
                showMonth = showMonth,
                onToggle = {
                    // Open the other view on whatever the user is looking at.
                    selectedDay?.let { anchor = it }
                    showMonth = !showMonth
                },
                onPageMonth = onPageMonth
            )
            if (showMonth) {
                // Taller than a short phone has room for beside a list, so it scrolls with the
                // agenda rather than squeezing it behind the pill and Scan.
                agenda(Modifier.weight(1f).fillMaxWidth()) {
                    Column {
                        monthPane()
                        LarderDivider()
                    }
                }
            } else {
                WeekStrip(
                    today = today,
                    initialDate = shownDate,
                    selectedDay = selectedDay,
                    itemsByDate = state.itemsByExpiryDate,
                    statusByDate = state.statusByExpiryDate,
                    onWeekShown = { anchor = it },
                    onSelectDay = onSelectDay
                )
                LarderDivider()
                agenda(Modifier.weight(1f).fillMaxWidth(), null)
            }
        }
    }
}

/** The month, which is also the week and month switch on phones. Expanded windows always show
 *  the month, so there it's a plain title. The chevrons go wherever the month grid is showing. */
@Composable
private fun CalendarHeader(
    month: LocalDate,
    expanded: Boolean,
    showMonth: Boolean,
    onToggle: () -> Unit,
    onPageMonth: (Int) -> Unit
) {
    val title = formatMonthYear(month)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = LarderSpace.s4,
                end = LarderSpace.s2,
                top = if (expanded) LarderSpace.s5 else LarderSpace.s3
            )
            .heightIn(min = LarderSize.touch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (expanded) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }
            )
        } else {
            val toggleLabel = stringResource(if (showMonth) R.string.calendar_show_week else R.string.calendar_show_month)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(LarderRadius.sm))
                    .clickable(onClickLabel = toggleLabel, onClick = onToggle)
                    .clearAndSetSemantics {
                        contentDescription = "$title, $toggleLabel"
                        role = Role.Button
                    }
                    .heightIn(min = LarderSize.touch),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LarderIcon(
                    LarderIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (showMonth) 180f else 0f)
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (showMonth) {
            LarderIconButton(
                LarderIcons.ChevronLeft,
                stringResource(R.string.calendar_previous_month),
                onClick = { onPageMonth(-1) }
            )
            LarderIconButton(
                LarderIcons.ChevronRight,
                stringResource(R.string.calendar_next_month),
                onClick = { onPageMonth(1) }
            )
        }
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

    Column(modifier = Modifier.padding(start = LarderSpace.s2, end = LarderSpace.s2, bottom = LarderSpace.s2)) {
        WeekdayHeaderRow()
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            val days = remember(page, currentWeekStart) {
                weekDays(currentWeekStart.plus(DatePeriod(days = (page - CURRENT_WEEK_PAGE) * 7)))
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                days.forEach { date ->
                    CalendarDayCell(
                        date = date,
                        outsideMonth = false,
                        isToday = date == today,
                        isSelected = date == selectedDay,
                        count = itemsByDate[date]?.size ?: 0,
                        status = statusByDate[date],
                        expanded = false,
                        onClick = { onSelectDay(date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Six rows always, so the height holds when paging months. */
@Composable
private fun MonthGrid(
    month: LocalDate,
    today: LocalDate,
    selectedDay: LocalDate?,
    itemsByDate: Map<LocalDate, List<PantryRow>>,
    statusByDate: Map<LocalDate, ExpiryStatus>,
    expanded: Boolean,
    showKey: Boolean,
    onSelectDay: (LocalDate) -> Unit
) {
    // Remembered on the month: monthGrid allocates 42 CalendarDay + 42 LocalDate objects.
    val weeks = remember(month.year, month.month) { monthGrid(month).chunked(7) }
    Column(
        modifier = Modifier.padding(
            start = LarderSpace.s2,
            end = LarderSpace.s2,
            top = if (expanded) LarderSpace.s2 else 0.dp,
            bottom = if (expanded) LarderSpace.s4 else LarderSpace.s2
        )
    ) {
        WeekdayHeaderRow()
        weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    CalendarDayCell(
                        date = day.date,
                        outsideMonth = !day.inCurrentMonth,
                        isToday = day.date == today,
                        isSelected = day.date == selectedDay,
                        count = itemsByDate[day.date]?.size ?: 0,
                        status = statusByDate[day.date],
                        expanded = expanded,
                        onClick = { onSelectDay(day.date) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (showKey) {
            CalendarKey(
                modifier = Modifier.padding(top = if (expanded) LarderSpace.s4 else LarderSpace.s1)
            )
        }
    }
}

@Composable
private fun WeekdayHeaderRow() {
    Row(modifier = Modifier.fillMaxWidth()) {
        narrowWeekdayNames().forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = LarderSpace.s1)
            )
        }
    }
}

/**
 * One day, for the week strip and the month grid alike: its number over a mark. [count] is pantry
 * rows, not units, and is read aloud with the date. The selected day gets a ring, and today keeps
 * its fill with a second ring outside it.
 */
@Composable
private fun CalendarDayCell(
    date: LocalDate,
    outsideMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    count: Int,
    status: ExpiryStatus?,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val countDescription = if (count > 0) {
        pluralStringResource(R.plurals.calendar_day_item_count, count, count)
    } else {
        null
    }
    val dateDescription = formatLocalDate(date, FormatStyle.FULL)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (expanded) LarderSpace.s1 else 2.dp),
        modifier = modifier
            .clip(RoundedCornerShape(LarderRadius.md))
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(dateDescription, countDescription).joinToString(", ")
                selected = isSelected
                role = Role.Button
            }
            .heightIn(min = if (expanded) 84.dp else 56.dp)
            // Room for today's outer ring, which reaches 4dp past the number.
            .padding(top = if (expanded) LarderSpace.s2 else LarderSpace.s1, bottom = 2.dp)
    ) {
        DayNumber(date.day, outsideMonth, isToday, isSelected, if (expanded) 36.dp else 32.dp)
        Box(modifier = Modifier.heightIn(min = 16.dp), contentAlignment = Alignment.Center) {
            if (count > 0 && status != null) DayMark(dayMarkShape(status), dayCountLabel(count))
        }
    }
}

@Composable
private fun DayNumber(day: Int, outsideMonth: Boolean, isToday: Boolean, isSelected: Boolean, size: Dp) {
    val scheme = MaterialTheme.colorScheme
    val ring = scheme.primary
    val fill = when {
        isToday -> scheme.primary
        isSelected -> scheme.primaryContainer
        else -> Color.Transparent
    }
    val text = when {
        isToday -> scheme.onPrimary
        isSelected -> scheme.onPrimaryContainer
        outsideMonth -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .drawBehind {
                if (isSelected) {
                    val stroke = 2.dp.toPx()
                    // Sits outside the fill, and a ring's width further out around today's.
                    val gap = if (isToday) stroke else 0f
                    drawCircle(
                        color = ring,
                        radius = this.size.minDimension / 2 + gap + stroke / 2,
                        center = Offset(this.size.width / 2, this.size.height / 2),
                        style = Stroke(stroke)
                    )
                }
            }
            .background(fill, CircleShape)
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.labelLarge.merge(LarderTabularNumbers),
            fontWeight = if (outsideMonth && !isToday && !isSelected) FontWeight.Normal else null,
            color = text
        )
    }
}

/** A shape and a count, or only a count for a fresh day. */
@Composable
private fun DayMark(shape: DayMarkShape, count: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (shape != DayMarkShape.None) MarkShape(shape)
        Text(
            text = count,
            style = MaterialTheme.typography.labelMedium.merge(LarderTabularNumbers),
            color = if (shape == DayMarkShape.None) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun MarkShape(shape: DayMarkShape) {
    val colors = LarderTheme.colors
    val square = RoundedCornerShape(2.dp)
    when (shape) {
        DayMarkShape.Square -> MarkOutline(11.dp, square, colors.expiredContainer, colors.onExpiredContainer)
        DayMarkShape.Circle -> MarkOutline(11.dp, CircleShape, colors.todayContainer, colors.onTodayContainer)
        DayMarkShape.Diamond -> MarkOutline(
            size = 9.dp,
            shape = RoundedCornerShape(0.dp),
            fill = colors.soonContainer,
            line = colors.onSoonContainer,
            modifier = Modifier.padding(1.dp).rotate(45f)
        )
        DayMarkShape.None -> Unit
    }
}

@Composable
private fun MarkOutline(size: Dp, shape: Shape, fill: Color, line: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .background(fill, shape)
            .border(1.5.dp, line, shape)
    )
}

/** What the marks mean. Hidden on an empty pantry, when there is nothing to explain. */
@Composable
private fun CalendarKey(modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s4, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
    ) {
        KeyEntry(stringResource(R.string.metric_expired)) { MarkShape(DayMarkShape.Square) }
        KeyEntry(stringResource(R.string.calendar_section_today)) { MarkShape(DayMarkShape.Circle) }
        KeyEntry(stringResource(R.string.metric_soon)) { MarkShape(DayMarkShape.Diamond) }
        KeyEntry(stringResource(R.string.metric_fresh)) { DayMark(DayMarkShape.None, "1") }
    }
}

@Composable
private fun KeyEntry(label: String, mark: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        mark()
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** First use. [leading] is the month grid when it scrolls with the page; the body offers Scan,
 *  so the floating one steps aside and the screen keeps one primary action. */
@Composable
private fun EmptyAgenda(
    leading: (@Composable () -> Unit)?,
    onScan: () -> Unit,
    bottomOverlayClearance: Dp,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val available = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomOverlayClearance)
        ) {
            leading?.invoke()
            EmptyState(
                title = stringResource(R.string.calendar_agenda_empty),
                message = stringResource(R.string.calendar_empty_message),
                art = LarderArt.Shelf,
                modifier = Modifier.heightIn(min = (available - bottomOverlayClearance).coerceAtLeast(0.dp))
            ) {
                LarderButton(text = stringResource(R.string.home_empty_scan), onClick = onScan)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgendaList(
    sections: List<AgendaSection>,
    today: LocalDate,
    selectedDay: LocalDate?,
    selectedDayRows: List<PantryRow>?,
    leading: (@Composable () -> Unit)?,
    onShowAll: () -> Unit,
    onOpen: (PantryItem) -> Unit,
    onUsed: (PantryItem) -> Unit,
    onTossed: (PantryItem) -> Unit,
    bottomOverlayClearance: Dp,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            top = if (leading == null) LarderSpace.s2 else 0.dp,
            bottom = bottomOverlayClearance
        )
    ) {
        val rowItems: LazyListScope.(List<PantryRow>, Boolean) -> Unit = { rows, showDate ->
            itemsIndexed(rows, key = { _, row -> row.id }, contentType = { _, _ -> "row" }) { index, row ->
                AgendaRow(
                    row = row,
                    today = today,
                    showDate = showDate,
                    position = LarderGroupPosition.of(index, rows.size),
                    onOpen = { onOpen(row.item) },
                    onUsed = { onUsed(row.item) },
                    onTossed = { onTossed(row.item) },
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = LarderSpace.s4)
                )
            }
        }

        leading?.let { content ->
            item(key = "navigator", contentType = "navigator") {
                Column(modifier = Modifier.padding(bottom = LarderSpace.s2)) { content() }
            }
        }

        if (selectedDay != null) {
            item(key = "dayHeading", contentType = "dayHeading") {
                DayHeading(selectedDay, today, onShowAll)
            }
            if (selectedDayRows.isNullOrEmpty()) {
                item(key = "dayEmpty", contentType = "dayEmpty") {
                    Text(
                        text = stringResource(R.string.calendar_no_items_for_day),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2)
                    )
                }
            } else {
                rowItems(selectedDayRows, false)
            }
            return@LazyColumn
        }

        sections.forEachIndexed { index, section ->
            stickyHeader(key = "header_${section.bucket}", contentType = "header") {
                AgendaHeading(bucket = section.bucket, count = section.rows.size, first = index == 0)
            }
            rowItems(section.rows, section.bucket in MULTI_DAY_BUCKETS)
        }
    }
}

@Composable
private fun DayHeading(day: LocalDate, today: LocalDate, onShowAll: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LarderSpace.s4, end = LarderSpace.s1, bottom = LarderSpace.s2)
            .heightIn(min = LarderSize.touch)
    ) {
        Text(
            text = formatDayHeading(day, today.year),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() }
        )
        LarderButton(
            text = stringResource(R.string.calendar_show_all),
            onClick = onShowAll,
            style = LarderButtonStyle.Text
        )
    }
}

/** Sticks to the top of the list, so it paints the page behind itself. The count is rows. */
@Composable
private fun AgendaHeading(bucket: AgendaBucket, count: Int, first: Boolean) {
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .semantics(mergeDescendants = true) { heading() }
            .padding(
                start = LarderSpace.s4,
                end = LarderSpace.s4,
                top = if (first) LarderSpace.s2 else LarderSpace.s4,
                bottom = LarderSpace.s2
            )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelLarge.merge(LarderTabularNumbers),
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One item. Rows under a heading of several days lead with their date; a row under one day drops
 * it, since the heading gives it. The chip stays relative at any distance for the same reason:
 * the date is already there.
 *
 * Swipe right uses one, swipe left tosses the item. Both are accessibility actions too, and on the
 * detail sheet, so nothing depends on the gesture.
 */
@Composable
private fun AgendaRow(
    row: PantryRow,
    today: LocalDate,
    showDate: Boolean,
    position: LarderGroupPosition,
    onOpen: () -> Unit,
    onUsed: () -> Unit,
    onTossed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val item = row.item
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val statusText = remember(resources, locale, row.daysRemaining, item.expiryDate, today) {
        // Any status but fresh counts the days, whatever the distance.
        expiryStatusLabel(resources, locale, ExpiryStatus.EXPIRING_SOON, row.daysRemaining, item.expiryDate, today)
    }
    val location = item.storageLocation.takeIf { it.isNotBlank() }?.let { storageLocationLabel(it) }
    val place = if (showDate) {
        listOfNotNull(formatWeekdayDay(item.expiryDate), location)
    } else {
        listOfNotNull(location, row.categoryDisplayName?.takeIf { it.isNotBlank() }?.let { categoryLabel(it) })
    }.joinToString(" · ").ifEmpty { null }
    val usedLabel = stringResource(R.string.calendar_swipe_used)
    val tossedLabel = stringResource(R.string.calendar_swipe_tossed)
    val currentOnUsed by rememberUpdatedState(onUsed)
    val currentOnTossed by rememberUpdatedState(onTossed)

    LarderSwipeRow(
        startAction = LarderSwipeAction(
            icon = LarderIcons.Check,
            label = usedLabel,
            quiet = true,
            keepsRow = item.quantity > 1,
            onSwipe = onUsed
        ),
        endAction = LarderSwipeAction(LarderIcons.Trash2, tossedLabel, danger = true, onSwipe = onTossed),
        modifier = modifier.larderGroupItem(position)
    ) {
        PantryRowView(
            name = item.name,
            status = row.status,
            statusText = statusText,
            place = place,
            quantity = item.quantity,
            onClick = onOpen,
            // The swipe slides this over its reveal, so it can't be see-through.
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(usedLabel) { currentOnUsed(); true },
                        CustomAccessibilityAction(tossedLabel) { currentOnTossed(); true }
                    )
                },
            imageUrl = item.imageUrl?.takeIf { it.isNotBlank() },
            fallbackIcon = categoryIcon(row.categoryDisplayName ?: item.category)
        )
    }
}

@PreviewLightDark
@Composable
internal fun CalendarDayCellPreview() = LarderPreview {
    val day = LocalDate(2026, 11, 4)
    Row(modifier = Modifier.fillMaxWidth()) {
        val cell: @Composable (Boolean, Boolean, Boolean, Int, ExpiryStatus?) -> Unit =
            { outside, today, selected, count, status ->
                CalendarDayCell(day, outside, today, selected, count, status, false, {}, Modifier.weight(1f))
            }
        cell(false, false, false, 0, null)
        cell(false, false, false, 1, ExpiryStatus.EXPIRED)
        cell(false, true, false, 1, ExpiryStatus.EXPIRING_TODAY)
        cell(false, false, false, 12, ExpiryStatus.EXPIRING_SOON)
        cell(false, false, false, 2, ExpiryStatus.FRESH)
        cell(false, false, true, 1, ExpiryStatus.EXPIRING_SOON)
        cell(false, true, true, 1, ExpiryStatus.EXPIRING_TODAY)
        cell(true, false, false, 0, null)
    }
}

@PreviewLightDark
@Composable
internal fun CalendarKeyPreview() = LarderPreview { CalendarKey() }

@PreviewLightDark
@Composable
internal fun EmptyAgendaPreview() = LarderPreview {
    EmptyAgenda(leading = null, onScan = {}, bottomOverlayClearance = 0.dp, modifier = Modifier.height(420.dp))
}
