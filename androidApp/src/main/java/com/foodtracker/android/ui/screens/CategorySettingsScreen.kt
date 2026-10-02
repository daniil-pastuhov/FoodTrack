package com.foodtracker.android.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.model.CategoryPreference
import com.foodtracker.viewmodel.PantryStateHolder
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.categoryRename
import androidx.compose.ui.platform.LocalContext

/** Lets the user reorder (drag), rename, or disable each category detected from a scanned or
 *  added product's raw category value (see [CategoryPreference]). A rename/disable applies
 *  everywhere that category is shown - pantry filter chips, item card badges, and shopping list
 *  group headers - without rewriting the raw value stored on any product, so it also applies to
 *  the same product scanned again in the future. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySettingsScreen(
    stateHolder: PantryStateHolder,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    var editingOrigin by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val labelOf: (String) -> String = remember(context) { { categoryLabel(context.resources, it) } }
    var shelfLifeOrigin by remember { mutableStateOf<String?>(null) }
    var shelfLifeText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Grouping, and the merged-origins subtitle for each row, are derived once per preferences
    // change in PantryStateHolder. The subtitle in particular used to be built inside the
    // LazyColumn item body, so every visible row re-scanned, re-sorted and re-joined the whole
    // preference list on every recomposition — including on every frame of a drag.
    val groupedPreferences = state.categoryRows
    var localPreferences by remember { mutableStateOf(groupedPreferences) }
    LaunchedEffect(groupedPreferences) { localPreferences = groupedPreferences }

    val lazyListState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = from.index - 2
        val toIndex = to.index - 2

        if (fromIndex in localPreferences.indices && toIndex in localPreferences.indices) {
            localPreferences = localPreferences.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }

    // The whole visible order is what gets persisted, not just where the dragged row landed
    // relative to its neighbours — see PantryStateHolder.reorderCategoryPreferences.
    fun commitReorder() {
        stateHolder.reorderCategoryPreferences(localPreferences.map { it.preference.originName })
    }

    editingOrigin?.let { origin ->
        val categorySuggestions = remember(editingText, state.categoryPreferences) {
            if (editingText.isBlank()) {
                emptyList()
            } else {
                state.categoryPreferences
                    .map { labelOf(it.displayName) }
                    .distinct()
                    .filter {
                        it.contains(editingText, ignoreCase = true) &&
                                !it.equals(editingText, ignoreCase = true)
                    }
                    .take(5)
            }
        }
        AlertDialog(
            onDismissRequest = { editingOrigin = null },
            title = { Text(stringResource(R.string.category_settings_rename_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (categorySuggestions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categorySuggestions.forEach { suggestion ->
                                SuggestionChip(
                                    onClick = {
                                        editingText = suggestion
                                        focusManager.clearFocus()
                                    },
                                    label = { Text(suggestion) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // The field shows translated names: see categoryRename.
                    val current = state.categoryPreferences.firstOrNull { it.originName == origin }?.displayName
                    val newName = current?.let {
                        categoryRename(it, editingText, state.categoryPreferences.map { pref -> pref.displayName }, labelOf)
                    }
                    if (newName != null) stateHolder.renameCategory(origin, newName)
                    editingOrigin = null
                }) {
                    Text(stringResource(R.string.settings_confirm_rename), color = BrandPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingOrigin = null }) {
                    Text(stringResource(R.string.date_picker_cancel))
                }
            }
        )
    }

    shelfLifeOrigin?.let { origin ->
        AlertDialog(
            onDismissRequest = { shelfLifeOrigin = null },
            title = { Text(stringResource(R.string.category_settings_shelf_life_dialog_title)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.category_settings_shelf_life_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = shelfLifeText,
                        // Digits only: the value is a day count, and letting anything else in
                        // just means the confirm button silently does nothing.
                        onValueChange = { input ->
                            shelfLifeText = input.filter { it.isDigit() }.take(4)
                        },
                        label = { Text(stringResource(R.string.category_settings_shelf_life_field_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    stateHolder.setCategoryDefaultShelfLife(origin, shelfLifeText.toIntOrNull())
                    shelfLifeOrigin = null
                }) {
                    Text(stringResource(R.string.settings_confirm_rename), color = BrandPrimary)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        stateHolder.setCategoryDefaultShelfLife(origin, null)
                        shelfLifeOrigin = null
                    }) {
                        Text(stringResource(R.string.category_settings_shelf_life_clear))
                    }
                    TextButton(onClick = { shelfLifeOrigin = null }) {
                        Text(stringResource(R.string.date_picker_cancel))
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.category_settings_title), fontWeight = FontWeight.Bold) },
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
        if (localPreferences.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp)
            ) {
                Text(
                    text = stringResource(R.string.category_settings_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = innerPadding.calculateTopPadding() + 4.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text(
                        text = stringResource(R.string.category_settings_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                item {
                    val allEnabled = localPreferences.all { it.preference.enabled }
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.category_settings_toggle_all),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = allEnabled,
                                onCheckedChange = { stateHolder.setAllCategoriesEnabled(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = BrandPrimary, checkedTrackColor = BrandPrimary.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
                items(localPreferences, key = { it.preference.displayName }) { row ->
                    val pref = row.preference
                    ReorderableItem(reorderableState, key = pref.displayName) { isDragging ->
                        CategoryPreferenceRow(
                            preference = pref,
                            allOrigins = row.mergedOrigins,
                            isDragging = isDragging,
                            dragHandleModifier = Modifier.draggableHandle(
                                onDragStarted = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = {
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    commitReorder()
                                }
                            ),
                            onToggleEnabled = { stateHolder.setCategoryEnabled(pref.originName, it) },
                            onRenameClick = {
                                editingOrigin = pref.originName
                                editingText = labelOf(pref.displayName)
                            },
                            onShelfLifeClick = {
                                shelfLifeOrigin = pref.originName
                                shelfLifeText = pref.defaultShelfLifeDays?.toString() ?: ""
                            },
                            modifier = Modifier.animateItem().padding(bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPreferenceRow(
    preference: CategoryPreference,
    allOrigins: String,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onToggleEnabled: (Boolean) -> Unit,
    onRenameClick: () -> Unit,
    onShelfLifeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isDragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        label = "categoryRowContainerColor"
    )
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.category_settings_drag_handle_content_description),
                tint = TextSecondary,
                modifier = dragHandleModifier
                    .padding(end = 8.dp)
                    .size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = categoryLabel(preference.displayName),
                    fontWeight = FontWeight.SemiBold,
                    color = if (preference.enabled) MaterialTheme.colorScheme.onSurface else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (allOrigins.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.category_settings_original_name_format, allOrigins),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Shows the configured default shelf life, or invites setting one. A text button
            // rather than an icon so the current value is readable at a glance without opening
            // the dialog - it's the only per-category setting with a value worth showing.
            val shelfLifeLabel = stringResource(R.string.category_settings_shelf_life_content_description)
            TextButton(
                onClick = onShelfLifeClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.semantics { contentDescription = shelfLifeLabel }
            ) {
                val days = preference.defaultShelfLifeDays
                Text(
                    text = if (days != null) {
                        pluralStringResource(R.plurals.category_settings_shelf_life_days_format, days, days)
                    } else {
                        stringResource(R.string.category_settings_shelf_life_none)
                    },
                    fontSize = 12.sp,
                    color = if (days != null) BrandPrimary else TextSecondary,
                    maxLines = 1
                )
            }
            IconButton(onClick = onRenameClick) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.category_settings_rename_content_description),
                    tint = TextSecondary
                )
            }
            Switch(
                checked = preference.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = BrandPrimary, checkedTrackColor = BrandPrimary.copy(alpha = 0.5f))
            )
        }
    }
}
