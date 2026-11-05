package com.foodtracker.android.ui.screens

import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.notification.NotificationHelper
import com.foodtracker.android.ui.components.ItemCard
import com.foodtracker.android.ui.components.fullBleed
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderArt
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderChip
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderTextField
import com.foodtracker.android.ui.design.LedeHeader
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.design.ShellChromeEffect
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.navigation.AddItemMode
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.shareRecipePrompt
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.model.PantryItem
import com.foodtracker.model.SortOption
import com.foodtracker.viewmodel.HomeLede
import com.foodtracker.viewmodel.HomeSection
import com.foodtracker.viewmodel.HomeSectionTitle
import com.foodtracker.viewmodel.HomeSummary
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.PantryUiState
import com.foodtracker.viewmodel.StatusFilter
import kotlinx.coroutines.launch
import kotlinx.datetime.isoDayNumber
import kotlin.math.roundToInt

/** The list's width when the product detail sits beside it. The root lines snackbars up with it. */
val HomeListPaneWidth = 380.dp

/** Numbers up to ten are words, because the sentence should read like one. */
internal fun ledeNumber(resources: Resources, count: Int): String {
    val words = resources.getStringArray(R.array.lede_number_words)
    return words.getOrNull(count - 1) ?: count.toString()
}

/** Words the sentence at the top of Home, or null when there isn't one. */
internal fun homeLedeText(resources: Resources, lede: HomeLede): String? = when (lede) {
    HomeLede.None -> null
    is HomeLede.UseByTomorrow -> resources.getQuantityString(
        R.plurals.lede_use_by_tomorrow, lede.count, ledeNumber(resources, lede.count)
    )
    is HomeLede.PastDate -> resources.getQuantityString(
        R.plurals.lede_past_date, lede.count, ledeNumber(resources, lede.count)
    )
    // The weekday carries its preposition because Russian changes both: "в среду", "во вторник".
    is HomeLede.NextUp -> resources.getString(
        R.string.lede_next_up,
        lede.name,
        resources.getStringArray(R.array.lede_on_weekday)[lede.date.dayOfWeek.isoDayNumber - 1]
    )
    HomeLede.NothingThisWeek -> resources.getString(R.string.lede_nothing_this_week)
}

/** "A and B", "A, B and C", then "A, B and 3 others", so the dialog stays short. */
internal fun recipeNames(resources: Resources, names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> resources.getString(R.string.recipe_names_two, names[0], names[1])
    3 -> resources.getString(R.string.recipe_names_three, names[0], names[1], names[2])
    else -> resources.getQuantityString(R.plurals.recipe_names_more, names.size - 2, names[0], names[1], names.size - 2)
}

/**
 * Which product the right-hand pane shows: the one picked, while it is still in the list, else the
 * first row. Nothing is picked for you while searching, and selecting always leaves the pane blank.
 */
internal fun homePaneItemId(
    pickedId: String?,
    sections: List<HomeSection>,
    searching: Boolean,
    selecting: Boolean
): String? {
    if (selecting) return null
    if (pickedId != null && sections.any { section -> section.rows.any { it.id == pickedId } }) return pickedId
    if (searching) return null
    return sections.firstOrNull()?.rows?.firstOrNull()?.id
}

@Composable
fun PantryDashboardScreen(
    stateHolder: PantryStateHolder,
    onUseOne: (PantryItem) -> Unit,
    onDeleteItem: (String) -> Unit,
    onDeleteItems: (Set<String>) -> Unit,
    onMoveToShoppingList: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    // Distance content must clear at the bottom so it can scroll out from under the floating
    // pill nav bar and Scan. Defaults to a rough guess for previews and standalone use.
    bottomOverlayClearance: Dp = 160.dp,
    /** The row picked for the right-hand pane on expanded windows. */
    pickedItemId: String? = null,
    /** Held by the root, so a dismissal outlasts a trip to another tab. */
    remindersCalloutDismissed: Boolean = false,
    onDismissRemindersCallout: () -> Unit = {},
    /** Draws the product detail in the right-hand pane on expanded windows. */
    detailPane: @Composable (itemId: String) -> Unit = {}
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = context.resources
    val navigator = LocalLarderNavigator.current
    val scope = rememberCoroutineScope()
    val expanded = isExpanded()

    var filterSheet by remember { mutableStateOf<FilterSheetPage?>(null) }
    var showRecipeDialog by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // Opens with the query still set when coming back from another tab, so the list is never
    // filtered by a search nobody can see.
    var searching by remember { mutableStateOf(state.searchQuery.isNotEmpty()) }
    var blockedNotificationsIntent by remember { mutableStateOf<Intent?>(null) }

    val selecting = selectedIds.isNotEmpty()
    val loaded = !state.isLoading
    val pantryEmpty = loaded && state.items.isEmpty()
    val query = state.searchQuery.trim()
    val showingResults = searching && query.isNotEmpty()
    val filtersActive = state.selectedLocation != null || state.selectedCategory != null
    val sortOption = state.settings.sortOption
    val sections = remember(state.filteredItems, sortOption, showingResults) {
        if (showingResults) {
            listOf(HomeSection(null, state.filteredItems)).filter { it.rows.isNotEmpty() }
        } else {
            HomeSummary.sections(state.filteredItems, sortOption)
        }
    }
    val lede = remember(state.allRows) { HomeSummary.lede(state.allRows) }

    LifecycleResumeEffect(state.settings.notifications) {
        blockedNotificationsIntent =
            NotificationHelper.blockedNotificationsSettingsIntent(context, state.settings.notifications)
        onPauseOrDispose {}
    }

    // Clear data can empty the pantry under a search or filter, which would then hide the first
    // thing added.
    LaunchedEffect(pantryEmpty) {
        if (pantryEmpty) {
            searching = false
            selectedIds = emptySet()
            stateHolder.onSearchQueryChanged("")
            stateHolder.onLocationSelected(null)
            stateHolder.onCategorySelected(null)
            stateHolder.onStatusFilterSelected(StatusFilter.ALL)
        }
    }

    // The line saying what is hidden goes in above the row the list is anchored to, so without this
    // it would sit just out of sight.
    LaunchedEffect(filtersActive) { if (filtersActive) listState.scrollToItem(0) }

    ShellChromeEffect(hideNav = selecting, hideScan = selecting || pantryEmpty)

    val closeSearch = {
        searching = false
        stateHolder.onSearchQueryChanged("")
    }
    BackHandler(enabled = selecting) { selectedIds = emptySet() }
    BackHandler(enabled = searching && !selecting) { closeSearch() }

    // Scrolled away as one piece, reacting to any drag rather than only at the top of the list.
    val headerCollapses by rememberUpdatedState(!selecting && !searching && !pantryEmpty)
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    var headerHeightPx by remember { mutableFloatStateOf(0f) }
    val headerScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!headerCollapses || headerHeightPx <= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceIn(-headerHeightPx, 0f)
                val consumed = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }
        }
    }
    LaunchedEffect(headerCollapses) { if (headerCollapses) headerOffsetPx = 0f }

    val twoPane = expanded && loaded && !pantryEmpty
    val paneItemId = homePaneItemId(pickedItemId, sections, searching, selecting)

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .then(if (twoPane) Modifier.width(HomeListPaneWidth) else Modifier.weight(1f))
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .clipToBounds()
                .nestedScroll(headerScroll)
        ) {
            when {
                !loaded -> Unit
                pantryEmpty -> BrandBar()
                selecting -> SelectionBar(selectedIds.size, onExit = { selectedIds = emptySet() })
                searching -> SearchBar(
                    query = state.searchQuery,
                    onQueryChange = stateHolder::onSearchQueryChanged,
                    onClose = closeSearch
                )
                else -> CollapsingHeader(
                    heightOffsetPx = { headerOffsetPx },
                    onNaturalHeightMeasured = { headerHeightPx = it }
                ) {
                    LedeHeader(
                        lede = remember(resources, lede) { homeLedeText(resources, lede).orEmpty() },
                        brand = stringResource(R.string.app_name),
                        actions = {
                            LarderIconButton(
                                LarderIcons.Search,
                                stringResource(R.string.home_search_description),
                                onClick = { searching = true }
                            )
                            FilterButton(filtersActive, onClick = { filterSheet = FilterSheetPage.Filters })
                        },
                        below = {
                            StatusChips(state) { filter ->
                                stateHolder.onStatusFilterSelected(
                                    if (state.selectedStatusFilter == filter) StatusFilter.ALL else filter
                                )
                                scope.launch { listState.scrollToItem(0) }
                            }
                        }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !loaded -> Unit
                    pantryEmpty -> HomeEmptyState(
                        onScan = { navigator.navigate(LarderRoute.Scanner()) },
                        onAddByHand = { navigator.navigate(LarderRoute.AddItem(AddItemMode.New)) }
                    )
                    sections.isEmpty() && query.isNotEmpty() -> NoResults(
                        title = stringResource(R.string.home_no_results_title, query),
                        message = stringResource(R.string.home_no_results_message),
                        actionLabel = stringResource(R.string.home_clear_search),
                        onAction = { stateHolder.onSearchQueryChanged("") }
                    )
                    sections.isEmpty() -> NoResults(
                        title = stringResource(R.string.home_no_filter_results_title),
                        message = stringResource(R.string.home_no_filter_results_message),
                        actionLabel = stringResource(R.string.home_clear_filters),
                        onAction = {
                            stateHolder.onLocationSelected(null)
                            stateHolder.onCategorySelected(null)
                            stateHolder.onStatusFilterSelected(StatusFilter.ALL)
                        }
                    )
                    else -> {
                        val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                        val bottomPadding = if (selecting) LarderSpace.s4 else maxOf(bottomOverlayClearance, keyboard)
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(
                                start = LarderSpace.s4,
                                end = LarderSpace.s4,
                                bottom = bottomPadding
                            ),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            val reminders = blockedNotificationsIntent
                            if (reminders != null && !remindersCalloutDismissed && !selecting && !searching) {
                                item(key = "reminders-off", contentType = "callout") {
                                    RemindersOffCallout(
                                        onOpenSettings = { context.startActivity(reminders) },
                                        onDismiss = onDismissRemindersCallout
                                    )
                                }
                            }
                            if (filtersActive && !selecting && !searching) {
                                item(key = "active-filter", contentType = "filter") {
                                    ActiveFilterLine(state, onClear = {
                                        stateHolder.onLocationSelected(null)
                                        stateHolder.onCategorySelected(null)
                                    })
                                }
                            }
                            if (showingResults) {
                                item(key = "results", contentType = "header") {
                                    SectionHeader(
                                        text = pluralStringResource(
                                            R.plurals.home_search_results,
                                            state.filteredItems.size,
                                            state.filteredItems.size
                                        ),
                                        first = true
                                    )
                                }
                            }
                            sections.forEachIndexed { sectionIndex, section ->
                                section.title?.let { title ->
                                    item(key = "section-$title", contentType = "header") {
                                        SectionHeader(
                                            text = sectionTitleText(title),
                                            modifier = Modifier.animateItem(),
                                            first = sectionIndex == 0
                                        )
                                    }
                                }
                                itemsIndexed(
                                    items = section.rows,
                                    key = { _, row -> row.id },
                                    contentType = { _, _ -> "row" }
                                ) { index, row ->
                                    ItemCard(
                                        row = row,
                                        today = state.today,
                                        position = LarderGroupPosition.of(index, section.rows.size),
                                        onDelete = { onDeleteItem(row.id) },
                                        onUseOne = { onUseOne(row.item) },
                                        onMoveToShoppingList = { onMoveToShoppingList(row.id) },
                                        onClick = {
                                            if (selecting) {
                                                selectedIds = selectedIds.toggled(row.id)
                                            } else {
                                                navigator.navigate(LarderRoute.ProductDetail(row.id))
                                            }
                                        },
                                        onLongClick = { selectedIds = selectedIds.toggled(row.id) },
                                        selectionMode = selecting,
                                        selected = row.id in selectedIds,
                                        current = twoPane && row.id == paneItemId,
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (selecting) {
                SelectionActions(
                    onRecipeIdeas = { showRecipeDialog = true },
                    onDelete = {
                        onDeleteItems(selectedIds)
                        selectedIds = emptySet()
                    }
                )
            }
        }

        if (twoPane) {
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                if (paneItemId != null) detailPane(paneItemId) else NothingChosen()
            }
        }
    }

    if (showRecipeDialog) {
        RecipeIdeasDialog(
            selected = state.items.filter { it.id in selectedIds },
            others = state.items.filter { it.id !in selectedIds },
            onShare = { selected, others ->
                showRecipeDialog = false
                shareRecipePrompt(context, selected, others)
                selectedIds = emptySet()
            },
            onDismiss = { showRecipeDialog = false }
        )
    }

    filterSheet?.let { page ->
        FilterSheet(
            page = page,
            state = state,
            onPageChange = { filterSheet = it },
            onLocationSelected = stateHolder::onLocationSelected,
            onCategorySelected = stateHolder::onCategorySelected,
            onSortSelected = stateHolder::onSortOptionSelected,
            onDismiss = { filterSheet = null }
        )
    }
}

private fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id

@Composable
private fun sectionTitleText(title: HomeSectionTitle): String = when (title) {
    HomeSectionTitle.UseFirst -> stringResource(R.string.home_section_use_first)
    HomeSectionTitle.LaterThisWeek -> stringResource(R.string.home_section_later_this_week)
    HomeSectionTitle.Later -> stringResource(R.string.home_section_later)
    is HomeSectionTitle.Place -> storageLocationLabel(title.location)
}

/** The header of an empty pantry: search and filters hide until there is something to find. */
@Composable
private fun BrandBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LarderSize.touch)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun FilterButton(active: Boolean, onClick: () -> Unit) {
    Box {
        LarderIconButton(
            LarderIcons.SlidersHorizontal,
            stringResource(if (active) R.string.home_filters_on_description else R.string.filter_content_description),
            onClick = onClick
        )
        if (active) {
            val dotRing = MaterialTheme.colorScheme.background
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(LarderSpace.s2)
                    .size(LarderSpace.s3)
                    .background(dotRing, CircleShape)
                    .padding(2.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}

/** Chips with nothing to show hide, except All and whichever is on, so a filter can be turned off. */
@Composable
private fun StatusChips(state: PantryUiState, onSelect: (StatusFilter) -> Unit) {
    val selected = state.selectedStatusFilter
    Row(
        modifier = Modifier
            .fullBleed(LarderSpace.s4)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LarderSpace.s4),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        StatusChip(stringResource(R.string.tab_all), state.items.size, selected == StatusFilter.ALL) {
            onSelect(StatusFilter.ALL)
        }
        listOf(
            Triple(R.string.metric_expired, state.expiredCount, StatusFilter.EXPIRED),
            Triple(R.string.metric_soon, state.expiringSoonCount, StatusFilter.EXPIRING_SOON),
            Triple(R.string.metric_fresh, state.freshCount, StatusFilter.FRESH)
        ).forEach { (label, count, filter) ->
            if (count > 0 || selected == filter) {
                StatusChip(stringResource(label), count, selected == filter) { onSelect(filter) }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    LarderChip(
        label = label,
        selected = selected,
        onClick = onClick,
        // The chip caps at 999+; a screen reader still gets the real number.
        modifier = Modifier.semantics { contentDescription = "$label, $count" },
        count = HomeSummary.cappedCount(count)
    )
}

@Composable
private fun SelectionBar(count: Int, onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1)
    ) {
        LarderIconButton(LarderIcons.X, stringResource(R.string.exit_selection_content_description), onExit)
        Text(
            text = stringResource(R.string.selection_count_format, count),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SelectionActions(onRecipeIdeas: () -> Unit, onDelete: () -> Unit) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        LarderDivider()
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderButton(
                text = stringResource(R.string.action_get_recipe_ideas),
                onClick = onRecipeIdeas,
                modifier = Modifier.weight(1f),
                style = LarderButtonStyle.Secondary,
                leadingIcon = LarderIcons.Sparkles
            )
            LarderButton(
                text = stringResource(R.string.delete),
                onClick = onDelete,
                style = LarderButtonStyle.DangerText,
                leadingIcon = LarderIcons.Trash2
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LarderSpace.s1, end = LarderSpace.s4, top = LarderSpace.s3, bottom = LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIconButton(LarderIcons.ArrowLeft, stringResource(R.string.back_content_description), onClose)
        LarderTextField(
            value = query,
            onValueChange = onQueryChange,
            label = null,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = stringResource(R.string.search_placeholder),
            trailing = {
                if (query.isNotEmpty()) {
                    LarderIconButton(
                        LarderIcons.X,
                        stringResource(R.string.clear_content_description),
                        onClick = { onQueryChange("") }
                    )
                }
            }
        )
    }
}

@Composable
private fun HomeEmptyState(onScan: () -> Unit, onAddByHand: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.home_empty_title),
        message = stringResource(R.string.home_empty_message),
        art = LarderArt.Shelf
    ) {
        LarderButton(
            text = stringResource(R.string.home_empty_scan),
            onClick = onScan,
            leadingIcon = LarderIcons.ScanBarcode
        )
        LarderButton(
            text = stringResource(R.string.home_empty_add_by_hand),
            onClick = onAddByHand,
            style = LarderButtonStyle.Text
        )
    }
}

/** A dead end to step out of, not a first-use moment, so no illustration. */
@Composable
private fun NoResults(title: String, message: String, actionLabel: String, onAction: () -> Unit) {
    EmptyState(title = title, message = message, art = null) {
        LarderButton(text = actionLabel, onClick = onAction, style = LarderButtonStyle.Secondary)
    }
}

@Composable
private fun RemindersOffCallout(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    LarderCallout(
        kind = LarderCalloutKind.Warning,
        modifier = Modifier.padding(bottom = LarderSpace.s3),
        title = stringResource(R.string.home_reminders_off_title),
        message = stringResource(R.string.home_reminders_off_message),
        icon = LarderIcons.BellOff,
        actionLabel = stringResource(R.string.settings_notifications_open_settings),
        onAction = onOpenSettings,
        onDismiss = onDismiss,
        dismissDescription = stringResource(R.string.home_dismiss)
    )
}

/** Says what the filter sheet is hiding: "Fridge · 8 of 13 items". */
@Composable
private fun ActiveFilterLine(state: PantryUiState, onClear: () -> Unit) {
    val text = listOfNotNull(
        state.selectedLocation?.let { storageLocationLabel(it) },
        state.selectedCategory?.let { categoryLabel(it) },
        pluralStringResource(
            R.plurals.home_active_filter_count,
            state.items.size,
            state.filteredItems.size,
            state.items.size
        )
    ).joinToString(" · ")
    SectionHeader(text = text, first = true, trailing = {
        LarderButton(
            text = stringResource(R.string.home_clear_filters),
            onClick = onClear,
            style = LarderButtonStyle.Text
        )
    })
}

@Composable
private fun NothingChosen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LarderSpace.s6),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LarderIcon(LarderIcons.Package, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = stringResource(R.string.detail_choose_item),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecipeIdeasDialog(
    selected: List<PantryItem>,
    others: List<PantryItem>,
    onShare: (selected: List<PantryItem>, others: List<PantryItem>) -> Unit,
    onDismiss: () -> Unit
) {
    val resources = LocalContext.current.resources
    var includeOthers by remember { mutableStateOf(false) }
    LarderDialog(
        title = stringResource(R.string.share_recipe_dialog_title),
        text = stringResource(R.string.recipe_intro, recipeNames(resources, selected.map { it.name })),
        confirmLabel = stringResource(R.string.share_recipe_share_button),
        onConfirm = { onShare(selected, if (includeOthers) others else emptyList()) },
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.share_recipe_cancel_button),
        content = if (others.isEmpty()) {
            null
        } else {
            {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(includeOthers, role = Role.Checkbox, onValueChange = { includeOthers = it }),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1)
                ) {
                    Checkbox(checked = includeOthers, onCheckedChange = null, modifier = Modifier.size(LarderSize.touch))
                    Column(modifier = Modifier.padding(vertical = LarderSpace.s2)) {
                        Text(
                            text = stringResource(R.string.share_recipe_include_other_items_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (includeOthers) {
                                pluralStringResource(R.plurals.recipe_include_others_count, others.size, others.size)
                            } else {
                                stringResource(R.string.share_recipe_include_other_items_subtitle)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    )
}

private enum class FilterSheetPage { Filters, Sort }

@Composable
private fun sortLabel(option: SortOption): String = stringResource(
    when (option) {
        SortOption.EXPIRY_DATE -> R.string.sort_option_expiry_date
        SortOption.NAME -> R.string.sort_option_name
        SortOption.DATE_ADDED -> R.string.sort_option_date_added
        SortOption.STORAGE_LOCATION -> R.string.sort_option_storage_location
    }
)

/** Filters and sort share one sheet, with sort a step on from the filters. Choices apply as they
 *  are tapped, so the button can say how many items they leave. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    page: FilterSheetPage,
    state: PantryUiState,
    onPageChange: (FilterSheetPage) -> Unit,
    onLocationSelected: (String?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onSortSelected: (SortOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sortOption = state.settings.sortOption
    LarderSheet(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (page == FilterSheetPage.Filters) R.string.filters_sheet_title else R.string.sort_sheet_title
        )
    ) {
        when (page) {
            FilterSheetPage.Filters -> {
                SectionHeader(stringResource(R.string.home_location_label), first = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                    LarderChip(
                        label = stringResource(R.string.filter_all_locations),
                        selected = state.selectedLocation == null,
                        onClick = { onLocationSelected(null) }
                    )
                    state.settings.storageLocations.forEach { location ->
                        LarderChip(
                            label = storageLocationLabel(location),
                            selected = state.selectedLocation == location,
                            onClick = { onLocationSelected(location) }
                        )
                    }
                }
                if (state.availableCategories.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.home_category_label))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                        LarderChip(
                            label = stringResource(R.string.filter_all_categories),
                            selected = state.selectedCategory == null,
                            onClick = { onCategorySelected(null) }
                        )
                        state.availableCategories.forEach { category ->
                            LarderChip(
                                label = categoryLabel(category),
                                selected = state.selectedCategory == category,
                                onClick = { onCategorySelected(category) }
                            )
                        }
                    }
                }
                Spacer(Modifier.padding(top = LarderSpace.s4))
                LarderDivider(Modifier.fullBleed(LarderSpace.s4))
                LarderListItem(
                    title = stringResource(R.string.sort_sheet_title),
                    modifier = Modifier.fullBleed(LarderSpace.s4),
                    trailing = {
                        Text(
                            text = sortLabel(sortOption),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LarderIcon(
                            LarderIcons.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    onClick = { onPageChange(FilterSheetPage.Sort) }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LarderSpace.s3),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LarderButton(
                        text = stringResource(R.string.home_clear_filters),
                        onClick = {
                            onLocationSelected(null)
                            onCategorySelected(null)
                        },
                        style = LarderButtonStyle.Text
                    )
                    LarderButton(
                        text = pluralStringResource(
                            R.plurals.home_show_items,
                            state.filteredItems.size,
                            state.filteredItems.size
                        ),
                        onClick = onDismiss
                    )
                }
            }
            FilterSheetPage.Sort -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                    SortOption.entries.forEach { option ->
                        LarderChip(
                            label = sortLabel(option),
                            selected = sortOption == option,
                            onClick = { onSortSelected(option) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = LarderSpace.s3),
                    horizontalArrangement = Arrangement.End
                ) {
                    LarderButton(text = stringResource(R.string.home_done), onClick = onDismiss)
                }
            }
        }
    }
}

// Clamps content height to naturalHeight + heightOffsetPx and translates it up by the same
// amount, so it looks like it's scrolling away underneath the top edge as heightOffsetPx
// (driven by a nested scroll connection) goes from 0 to -naturalHeight.
//
// The offset arrives as a lambda rather than a value on purpose. Taken as a `Float`, the caller
// has to read the offset state in its own composable scope, so every scroll frame invalidated
// the entire dashboard. Reading it here, inside the measure block, means a scroll re-runs layout
// only.
@Composable
private fun CollapsingHeader(
    heightOffsetPx: () -> Float,
    onNaturalHeightMeasured: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        onNaturalHeightMeasured(placeable.height.toFloat())
        val offset = heightOffsetPx()
        val height = (placeable.height + offset).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, height) {
            placeable.placeRelative(0, offset.roundToInt())
        }
    }
}
