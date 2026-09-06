package com.foodtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.model.AppSettings
import com.foodtracker.model.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onClearAllData: () -> Unit,
    onManageCategories: () -> Unit,
    modifier: Modifier = Modifier,
    // See PantryDashboardScreen's parameter of the same name.
    bottomOverlayClearance: Dp = 160.dp
) {
    var draft by remember { mutableStateOf(settings) }
    var newLocationText by remember { mutableStateOf("") }
    var editingLocation by remember { mutableStateOf<String?>(null) }
    var editingLocationText by remember { mutableStateOf("") }
    var showTimePicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun persist(updated: AppSettings) {
        draft = updated
        onSave(updated)
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = draft.notifications.hour,
            initialMinute = draft.notifications.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    persist(draft.copy(notifications = draft.notifications.copy(hour = timeState.hour, minute = timeState.minute)))
                    showTimePicker = false
                }) {
                    Text(stringResource(R.string.date_picker_ok), color = BrandPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.date_picker_cancel))
                }
            },
            text = { TimePicker(state = timeState) }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_data_dialog_title)) },
            text = { Text(stringResource(R.string.clear_data_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearAllData()
                }) {
                    Text(stringResource(R.string.clear_data_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.date_picker_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Notifications
            SettingsSectionCard(title = stringResource(R.string.settings_section_notifications)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_notifications_enabled), modifier = Modifier.weight(1f))
                    Switch(
                        checked = draft.notifications.enabled,
                        onCheckedChange = { persist(draft.copy(notifications = draft.notifications.copy(enabled = it))) },
                        colors = SwitchDefaults.colors(checkedThumbColor = BrandPrimary, checkedTrackColor = BrandPrimary.copy(alpha = 0.5f))
                    )
                }

                if (draft.notifications.enabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_lead_days_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 3, 5, 7).forEach { days ->
                            FilterChip(
                                selected = draft.notifications.leadDays == days,
                                onClick = { persist(draft.copy(notifications = draft.notifications.copy(leadDays = days))) },
                                label = { Text(days.toString()) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = BrandPrimary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.settings_time_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { showTimePicker = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null, tint = BrandPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "%02d:%02d".format(draft.notifications.hour, draft.notifications.minute),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Expiring soon threshold
            SettingsSectionCard(title = stringResource(R.string.settings_section_expiring_soon)) {
                Text(
                    text = stringResource(R.string.settings_expiring_soon_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 5, 7).forEach { days ->
                        FilterChip(
                            selected = draft.expiringSoonDays == days,
                            onClick = { persist(draft.copy(expiringSoonDays = days)) },
                            label = { Text(days.toString()) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandPrimary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            // Storage locations
            SettingsSectionCard(title = stringResource(R.string.settings_section_locations)) {
                Text(
                    text = stringResource(R.string.settings_locations_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                draft.storageLocations.forEach { location ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        if (editingLocation == location) {
                            OutlinedTextField(
                                value = editingLocationText,
                                onValueChange = { editingLocationText = it },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                val newName = editingLocationText.trim()
                                if (newName.isNotBlank() && !draft.storageLocations.contains(newName)) {
                                    val updatedLocations = draft.storageLocations.map { if (it == location) newName else it }
                                    val updatedDefault = if (draft.defaultStorageLocation == location) newName else draft.defaultStorageLocation
                                    persist(draft.copy(storageLocations = updatedLocations, defaultStorageLocation = updatedDefault))
                                }
                                editingLocation = null
                            }) {
                                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_confirm_rename), tint = BrandPrimary)
                            }
                        } else {
                            IconButton(onClick = { persist(draft.copy(defaultStorageLocation = location)) }) {
                                Icon(
                                    imageVector = if (draft.defaultStorageLocation == location) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = stringResource(R.string.settings_set_default_location),
                                    tint = BrandPrimary
                                )
                            }
                            Text(location, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                editingLocation = location
                                editingLocationText = location
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_rename_location), tint = TextSecondary)
                            }
                            IconButton(
                                onClick = {
                                    val remaining = draft.storageLocations.filterNot { it == location }
                                    val updatedDefault = if (draft.defaultStorageLocation == location) remaining.first() else draft.defaultStorageLocation
                                    persist(draft.copy(storageLocations = remaining, defaultStorageLocation = updatedDefault))
                                },
                                enabled = draft.storageLocations.size > 1
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = stringResource(R.string.settings_delete_location),
                                    tint = if (draft.storageLocations.size > 1) MaterialTheme.colorScheme.error else TextSecondary.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newLocationText,
                        onValueChange = { newLocationText = it },
                        placeholder = { Text(stringResource(R.string.settings_add_location_placeholder)) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val newName = newLocationText.trim()
                        if (newName.isNotBlank() && !draft.storageLocations.contains(newName)) {
                            persist(draft.copy(storageLocations = draft.storageLocations + newName))
                            newLocationText = ""
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_add_location), tint = BrandPrimary)
                    }
                }
            }

            // Categories
            SettingsSectionCard(title = stringResource(R.string.settings_section_categories)) {
                Text(
                    text = stringResource(R.string.settings_categories_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onManageCategories)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.settings_manage_categories_button), modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextSecondary)
                }
            }

            // Appearance
            SettingsSectionCard(title = stringResource(R.string.settings_section_appearance)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val themes = listOf(
                        AppTheme.SYSTEM to stringResource(R.string.settings_theme_system),
                        AppTheme.LIGHT to stringResource(R.string.settings_theme_light),
                        AppTheme.DARK to stringResource(R.string.settings_theme_dark)
                    )
                    themes.forEach { (theme, label) ->
                        FilterChip(
                            selected = draft.theme == theme,
                            onClick = { persist(draft.copy(theme = theme)) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandPrimary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Data
            SettingsSectionCard(title = stringResource(R.string.settings_section_data)) {
                Button(
                    onClick = { showClearConfirm = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_clear_data_button), fontWeight = FontWeight.SemiBold)
                }
            }

            // Clears the floating pill nav bar and scan/add buttons that overlay this screen
            // (see MainActivity).
            Spacer(modifier = Modifier.height(bottomOverlayClearance))
        }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
