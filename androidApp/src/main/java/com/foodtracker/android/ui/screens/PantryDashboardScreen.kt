package com.foodtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.components.ItemCard
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.expiredStatusColors
import com.foodtracker.android.ui.theme.expiringStatusColors
import com.foodtracker.android.ui.theme.freshStatusColors
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.StatusFilter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantryDashboardScreen(
    stateHolder: PantryStateHolder,
    onNavigateToScanner: () -> Unit,
    onNavigateToAddEdit: (PantryItem?) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCalendar: () -> Unit,
    onDeleteItem: (String) -> Unit = { stateHolder.deleteItem(it) },
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    val filtersActive = state.selectedLocation != null ||
            state.selectedStatusFilter != StatusFilter.ALL ||
            state.searchQuery.isNotBlank()

    fun toggleMetricFilter(filter: StatusFilter) {
        stateHolder.onStatusFilterSelected(
            if (state.selectedStatusFilter == filter) StatusFilter.ALL else filter
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
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
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToCalendar) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = stringResource(R.string.calendar_content_description)
                        )
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        BadgedBox(
                            badge = {
                                if (filtersActive) {
                                    Badge(containerColor = BrandPrimary)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = stringResource(R.string.filter_content_description)
                            )
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings_content_description)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToScanner,
                containerColor = BrandPrimary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_content_description))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.scan_item), fontWeight = FontWeight.SemiBold)
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
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

            // List of Items
            if (state.isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator(color = BrandPrimary)
                }
            } else if (state.filteredItems.isEmpty()) {
                Box(
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
            } else {
                // Adaptive: 1 column on phones, more columns as width allows (tablets,
                // landscape, foldables) — driven by available width, not a device check.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 260.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    gridItems(state.filteredItems, key = { it.id }) { item ->
                        ItemCard(
                            item = item,
                            onDelete = { onDeleteItem(item.id) },
                            onClick = { onNavigateToAddEdit(item) },
                            expiringSoonDays = state.settings.expiringSoonDays
                        )
                    }
                }
            }
        }
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
                selectedStatusFilter = state.selectedStatusFilter
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
    selectedStatusFilter: StatusFilter
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

        // Status Tabs. FRESH has no dedicated tab (it's only reachable via the metric
        // card above), so fall back to highlighting "All" rather than mislabeling
        // a Fresh-only filter as Expired via a raw ordinal lookup.
        val tabIndex = when (selectedStatusFilter) {
            StatusFilter.EXPIRING_SOON -> 1
            StatusFilter.EXPIRED -> 2
            else -> 0
        }
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = BrandPrimary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Tab(
                selected = selectedStatusFilter == StatusFilter.ALL,
                onClick = { stateHolder.onStatusFilterSelected(StatusFilter.ALL) },
                text = { Text(stringResource(R.string.tab_all)) }
            )
            Tab(
                selected = selectedStatusFilter == StatusFilter.EXPIRING_SOON,
                onClick = { stateHolder.onStatusFilterSelected(StatusFilter.EXPIRING_SOON) },
                text = { Text(stringResource(R.string.tab_expiring_soon)) }
            )
            Tab(
                selected = selectedStatusFilter == StatusFilter.EXPIRED,
                onClick = { stateHolder.onStatusFilterSelected(StatusFilter.EXPIRED) },
                text = { Text(stringResource(R.string.tab_expired)) }
            )
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
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) bgColor else bgColor.copy(alpha = 0.55f)
        ),
        modifier = modifier.then(
            if (selected) Modifier.border(2.dp, color, RoundedCornerShape(12.dp)) else Modifier
        )
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
                    fontWeight = FontWeight.SemiBold
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = count.toString(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}
