package com.foodtracker.android.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.foodtracker.android.R
import com.foodtracker.android.ui.components.ItemCard
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.expiredStatusColors
import com.foodtracker.android.ui.theme.expiringStatusColors
import com.foodtracker.android.ui.theme.freshStatusColors
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.android.util.buildRecipePrompt
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.SortOption
import com.foodtracker.model.categoryPreferenceSortOrder
import com.foodtracker.model.resolveCategoryDisplayName
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.StatusFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryDashboardScreen(
    stateHolder: PantryStateHolder,
    onNavigateToAddEdit: (PantryItem?) -> Unit,
    onDeleteItem: (String) -> Unit,
    onDecrementQuantity: (String) -> Unit,
    onMoveToShoppingList: (String) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    // Distance content must clear at the bottom so it can scroll out from under the floating
    // pill nav bar and scan/add buttons (see MainActivity, which measures the overlay and
    // computes this). Defaults to a rough guess for previews/standalone use.
    bottomOverlayClearance: Dp = 160.dp
) {
    val state by stateHolder.uiState.collectAsState()
    val context = LocalContext.current
    var showFilterSheet by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var includeOtherItemsInShare by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selectionMode = selectedIds.isNotEmpty()
    val filtersActive = state.selectedLocation != null ||
            state.selectedCategory != null ||
            state.selectedStatusFilter != StatusFilter.ALL ||
            state.searchQuery.isNotBlank()

    // Drives the collapsing header (TopBar + metric row + Add to Pantry): 0f is fully
    // expanded, -headerNaturalHeightPx is fully collapsed. Consumed eagerly in onPreScroll
    // (not on post-scroll/at list boundaries) so the header reacts to any upward drag
    // immediately, from anywhere in the list, rather than only once the list hits its top.
    var headerOffsetPx by remember { mutableFloatStateOf(0f) }
    var headerNaturalHeightPx by remember { mutableFloatStateOf(0f) }
    val headerNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (headerNaturalHeightPx <= 0f) return Offset.Zero
                val newOffset = (headerOffsetPx + available.y).coerceIn(-headerNaturalHeightPx, 0f)
                val consumed = newOffset - headerOffsetPx
                headerOffsetPx = newOffset
                return Offset(0f, consumed)
            }
        }
    }

    fun toggleMetricFilter(filter: StatusFilter) {
        stateHolder.onStatusFilterSelected(
            if (state.selectedStatusFilter == filter) StatusFilter.ALL else filter
        )
    }

    fun toggleSelected(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun shareRecipePrompt(includeOtherItems: Boolean) {
        val selectedItems = state.items.filter { it.id in selectedIds }
        if (selectedItems.isEmpty()) return
        val otherItems = if (includeOtherItems) {
            state.items.filter { it.id !in selectedIds }
        } else {
            emptyList()
        }
        val prompt = buildRecipePrompt(context, selectedItems, otherItems)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        context.startActivity(Intent.createChooser(sendIntent, null))
        selectedIds = emptySet()
        includeOtherItemsInShare = false
    }

    fun deleteSelectedItems() {
        // Goes through the same onDeleteItem the row's own delete menu item uses, so each
        // removal still gets its usual undo snackbar and shopping-list-for-essentials handling
        // — there's no separate bulk-undo, just one snackbar per item.
        selectedIds.forEach(onDeleteItem)
        selectedIds = emptySet()
    }

    BackHandler(enabled = selectionMode) {
        selectedIds = emptySet()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(headerNestedScrollConnection)
        ) {
            CollapsingHeader(
                heightOffsetPx = headerOffsetPx,
                onNaturalHeightMeasured = { headerNaturalHeightPx = it }
            ) {
                Column {
                    TopAppBar(
                        title = {
                            AnimatedContent(
                                targetState = selectionMode,
                                transitionSpec = {
                                    (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 }) togetherWith
                                        (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 3 })
                                },
                                label = "topBarTitle"
                            ) { inSelectionMode ->
                                if (inSelectionMode) {
                                    Text(
                                        text = stringResource(R.string.selection_count_format, selectedIds.size),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(BrandPrimary)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Fastfood,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = stringResource(R.string.app_name),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = stringResource(R.string.app_subtitle),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            AnimatedVisibility(
                                visible = selectionMode,
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut()
                            ) {
                                IconButton(onClick = { selectedIds = emptySet() }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.exit_selection_content_description)
                                    )
                                }
                            }
                        },
                        actions = {
                            AnimatedContent(
                                targetState = selectionMode,
                                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                                label = "topBarActions"
                            ) { inSelectionMode ->
                                if (inSelectionMode) {
                                    Row {
                                        IconButton(onClick = { showShareDialog = true }) {
                                            Icon(
                                                imageVector = Icons.Default.RestaurantMenu,
                                                contentDescription = stringResource(R.string.action_get_recipe_ideas)
                                            )
                                        }
                                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = stringResource(R.string.delete)
                                            )
                                        }
                                    }
                                } else {
                                    Row {
                                        IconButton(onClick = { showFilterSheet = true }) {
                                            BadgedBox(
                                                badge = {
                                                    AnimatedDotBadge(visible = filtersActive)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Tune,
                                                    contentDescription = stringResource(R.string.filter_content_description)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )

                    // Urgency Metrics Row — also acts as the primary status filter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val expiredColors = expiredStatusColors()
                        MetricCard(
                            title = stringResource(R.string.metric_expired),
                            count = state.expiredCount,
                            color = expiredColors.foreground,
                            bgColor = expiredColors.background,
                            icon = Icons.Default.Error,
                            selected = state.selectedStatusFilter == StatusFilter.EXPIRED,
                            onClick = { toggleMetricFilter(StatusFilter.EXPIRED) },
                            modifier = Modifier.weight(1f)
                        )
                        val expiringColors = expiringStatusColors()
                        MetricCard(
                            title = stringResource(R.string.metric_soon),
                            count = state.expiringSoonCount,
                            color = expiringColors.foreground,
                            bgColor = expiringColors.background,
                            icon = Icons.Default.Warning,
                            selected = state.selectedStatusFilter == StatusFilter.EXPIRING_SOON,
                            onClick = { toggleMetricFilter(StatusFilter.EXPIRING_SOON) },
                            modifier = Modifier.weight(1f)
                        )
                        val freshColors = freshStatusColors()
                        MetricCard(
                            title = stringResource(R.string.metric_fresh),
                            count = state.freshCount,
                            color = freshColors.foreground,
                            bgColor = freshColors.background,
                            icon = Icons.Default.Schedule,
                            selected = state.selectedStatusFilter == StatusFilter.FRESH,
                            onClick = { toggleMetricFilter(StatusFilter.FRESH) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // List of Items
            val dashboardContentKey = when {
                state.isLoading -> "loading"
                state.filteredItems.isEmpty() -> "empty"
                else -> "grid"
            }
            AnimatedContent(
                targetState = dashboardContentKey,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                modifier = Modifier.fillMaxSize(),
                label = "dashboardContent"
            ) { contentKey ->
                when (contentKey) {
                    "loading" -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CircularProgressIndicator(color = BrandPrimary)
                    }
                    "empty" -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Fastfood,
                                contentDescription = null,
                                tint = TextSecondary.copy(alpha = 0.4f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.empty_state_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                            Text(
                                text = stringResource(R.string.empty_state_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary.copy(alpha = 0.7f),
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                    else -> {
                        // Adaptive: 1 column on phones, more columns as width allows (tablets,
                        // landscape, foldables) — driven by available width, not a device check.
                        // 340dp keeps room for image + expiry badge + location chip + menu button
                        // on one line even for long day counts ("624 days expired") — below that,
                        // the info row wraps and the grid stretches every card in the row to match.
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 340.dp),
                            // Bottom padding clears the floating pill nav bar and scan/add
                            // buttons that overlay this screen (see MainActivity), so the last
                            // row can still scroll fully into view above them.
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomOverlayClearance),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItems(state.filteredItems, key = { it.id }) { item ->
                                ItemCard(
                                    item = item,
                                    categoryPreferences = state.categoryPreferences,
                                    onDelete = { onDeleteItem(item.id) },
                                    onDecrement = { onDecrementQuantity(item.id) },
                                    onMoveToShoppingList = { onMoveToShoppingList(item.id) },
                                    onClick = {
                                        if (selectionMode) toggleSelected(item.id) else onNavigateToAddEdit(item)
                                    },
                                    onLongClick = { toggleSelected(item.id) },
                                    selectionMode = selectionMode,
                                    selected = item.id in selectedIds,
                                    expiringSoonDays = state.settings.expiringSoonDays,
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showShareDialog) {
        AlertDialog(
            onDismissRequest = { showShareDialog = false },
            title = { Text(stringResource(R.string.share_recipe_dialog_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = includeOtherItemsInShare,
                        onCheckedChange = { includeOtherItemsInShare = it }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.share_recipe_include_other_items_title),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.share_recipe_include_other_items_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showShareDialog = false
                    shareRecipePrompt(includeOtherItemsInShare)
                }) {
                    Text(stringResource(R.string.share_recipe_share_button), color = BrandPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareDialog = false }) {
                    Text(stringResource(R.string.share_recipe_cancel_button))
                }
            }
        )
    }

    if (showDeleteSelectedDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text(stringResource(R.string.delete_selected_dialog_title)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteSelectedDialog = false
                    deleteSelectedItems()
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedDialog = false }) {
                    Text(stringResource(R.string.share_recipe_cancel_button))
                }
            }
        )
    }

    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState
        ) {
            FilterSheetContent(
                stateHolder = stateHolder,
                searchQuery = state.searchQuery,
                selectedLocation = state.selectedLocation,
                storageLocations = state.settings.storageLocations,
                selectedCategory = state.selectedCategory,
                categories = remember(state.items, state.categoryPreferences) {
                    state.items.map { it.category }
                        .mapNotNull { resolveCategoryDisplayName(it, state.categoryPreferences) }
                        .distinct()
                        .sortedWith(compareBy({ displayName ->
                            state.categoryPreferences
                                .filter { it.displayName == displayName && it.enabled }
                                .minOfOrNull { it.sortOrder } ?: Double.MAX_VALUE
                        }, { it }))
                },
                categoryPreferences = state.categoryPreferences,
                selectedStatusFilter = state.selectedStatusFilter,
                sortOption = state.settings.sortOption
            )
        }
    }
}

@Composable
private fun FilterSheetContent(
    stateHolder: PantryStateHolder,
    searchQuery: String,
    selectedLocation: String?,
    storageLocations: List<String>,
    selectedCategory: String?,
    categories: List<String>,
    categoryPreferences: List<CategoryPreference>,
    selectedStatusFilter: StatusFilter,
    sortOption: SortOption
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.filters_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { stateHolder.onSearchQueryChanged(it) },
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { stateHolder.onSearchQueryChanged("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_content_description))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )

        // Storage Location Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedLocation == null,
                    onClick = { stateHolder.onLocationSelected(null) },
                    label = { Text(stringResource(R.string.filter_all_locations)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = BrandPrimary
                    )
                )
            }
            items(storageLocations) { location ->
                FilterChip(
                    selected = selectedLocation == location,
                    onClick = { stateHolder.onLocationSelected(location) },
                    label = { Text(location) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = BrandPrimary
                    )
                )
            }
        }

        // Category Filter Chips — derived from categories present on current items (filtered to
        // enabled ones and ordered per user preference; see CategorySettingsScreen), rather than
        // a fixed settings list the way storage locations are.
        AnimatedVisibility(
            visible = categories.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { stateHolder.onCategorySelected(null) },
                        label = { Text(stringResource(R.string.filter_all_categories)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                            selectedLabelColor = BrandPrimary
                        )
                    )
                }
                items(categories) { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { stateHolder.onCategorySelected(category) },
                        label = { Text(category) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                            selectedLabelColor = BrandPrimary
                        )
                    )
                }
            }
        }

        // Status Filter Chips. FRESH has no dedicated chip (it's only reachable via the
        // metric card above), so it simply falls outside this row's selection.
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedStatusFilter == StatusFilter.ALL,
                    onClick = { stateHolder.onStatusFilterSelected(StatusFilter.ALL) },
                    label = { Text(stringResource(R.string.tab_all)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = BrandPrimary
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedStatusFilter == StatusFilter.EXPIRING_SOON,
                    onClick = { stateHolder.onStatusFilterSelected(StatusFilter.EXPIRING_SOON) },
                    label = { Text(stringResource(R.string.tab_expiring_soon)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = BrandPrimary
                    )
                )
            }
            item {
                FilterChip(
                    selected = selectedStatusFilter == StatusFilter.EXPIRED,
                    onClick = { stateHolder.onStatusFilterSelected(StatusFilter.EXPIRED) },
                    label = { Text(stringResource(R.string.tab_expired)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = BrandPrimary
                    )
                )
            }
        }

        // Sort Order
        Text(
            text = stringResource(R.string.sort_sheet_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        val sortLabels = listOf(
            SortOption.EXPIRY_DATE to stringResource(R.string.sort_option_expiry_date),
            SortOption.NAME to stringResource(R.string.sort_option_name),
            SortOption.DATE_ADDED to stringResource(R.string.sort_option_date_added),
            SortOption.STORAGE_LOCATION to stringResource(R.string.sort_option_storage_location)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            items(sortLabels) { (option, label) ->
                FilterChip(
                    selected = sortOption == option,
                    onClick = { stateHolder.onSortOptionSelected(option) },
                    label = { Text(label) },
                    leadingIcon = if (sortOption == option) {
                        { Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BrandPrimary.copy(alpha = 0.15f),
                        selectedLabelColor = BrandPrimary
                    )
                )
            }
        }
    }
}

@Composable
private fun AnimatedDotBadge(visible: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut()
    ) {
        Badge(containerColor = BrandPrimary)
    }
}

// Clamps content height to naturalHeight + heightOffsetPx and translates it up by the same
// amount, so it looks like it's scrolling away underneath the top edge as heightOffsetPx
// (driven by a nested scroll connection) goes from 0 to -naturalHeight.
@Composable
private fun CollapsingHeader(
    heightOffsetPx: Float,
    onNaturalHeightMeasured: (Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier.clipToBounds()) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        onNaturalHeightMeasured(placeable.height.toFloat())
        val height = (placeable.height + heightOffsetPx).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, height) {
            placeable.placeRelative(0, heightOffsetPx.roundToInt())
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    count: Int,
    color: Color,
    bgColor: Color,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) bgColor else bgColor.copy(alpha = 0.55f),
        label = "metricCardContainerColor"
    )
    val borderWidth by animateDpAsState(targetValue = if (selected) 2.dp else 0.dp, label = "metricCardBorderWidth")

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier.border(borderWidth, color, RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    if (targetState > initialState) {
                        (fadeIn(tween(180)) + slideInVertically(tween(180)) { it }) togetherWith
                            (fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it })
                    } else {
                        (fadeIn(tween(180)) + slideInVertically(tween(180)) { -it }) togetherWith
                            (fadeOut(tween(180)) + slideOutVertically(tween(180)) { it })
                    }
                },
                label = "metricCardCount"
            ) { animatedCount ->
                Text(
                    text = animatedCount.toString(),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
    }
}
