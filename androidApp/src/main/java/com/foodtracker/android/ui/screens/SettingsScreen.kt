package com.foodtracker.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
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
import com.foodtracker.backup.RestoreMode
import com.foodtracker.model.AppSettings
import com.foodtracker.model.StorePlace
import com.foodtracker.model.AppTheme
import com.foodtracker.model.DigestFrequency
import com.foodtracker.model.NotificationSettings
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.ui.platform.LocalContext
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.android.util.storageLocationRename
import com.foodtracker.android.util.isExistingLocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onClearAllData: () -> Unit,
    onManageCategories: () -> Unit,
    /** Writes a full backup to the document the user picked. */
    onExportBackup: (Uri) -> Unit,
    /** Restores from the document the user picked, in the chosen mode. */
    onImportBackup: (Uri, RestoreMode) -> Unit,
    /** Default filename offered in the save dialog, stamped with today's date. */
    backupFileName: String,
    /** Asks for location permission and, once granted, reports the device's current position so
     *  it can be saved as a store. Null coordinates mean the user declined or no fix arrived. */
    onAddCurrentLocationAsStore: (label: String, onResolved: (latitude: Double?, longitude: Double?) -> Unit) -> Unit = { _, onResolved -> onResolved(null, null) },
    modifier: Modifier = Modifier,
    // See PantryDashboardScreen's parameter of the same name.
    bottomOverlayClearance: Dp = 160.dp
) {
    val context = LocalContext.current
    val locationLabel: (String) -> String = remember(context) { { storageLocationLabel(context.resources, it) } }
    var draft by remember { mutableStateOf(settings) }
    var newLocationText by remember { mutableStateOf("") }
    var newAllergenText by remember { mutableStateOf("") }
    var newStoreText by remember { mutableStateOf("") }
    var isResolvingStoreLocation by remember { mutableStateOf(false) }
    var storeLocationFailed by remember { mutableStateOf(false) }
    var editingLocation by remember { mutableStateOf<String?>(null) }
    var editingLocationText by remember { mutableStateOf("") }
    var showTimePicker by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    // Held between picking a file and choosing how to apply it: the mode question only makes
    // sense once we know there is a file, and asking it first would strand the answer if the
    // user then backed out of the picker.
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImportUri = uri }

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

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text(stringResource(R.string.settings_import_backup_button)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.restore_mode_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    RestoreModeRow(
                        title = stringResource(R.string.restore_mode_merge_title),
                        subtitle = stringResource(R.string.restore_mode_merge_subtitle),
                        onClick = {
                            pendingImportUri = null
                            onImportBackup(uri, RestoreMode.MERGE)
                        }
                    )
                    RestoreModeRow(
                        title = stringResource(R.string.restore_mode_replace_title),
                        subtitle = stringResource(R.string.restore_mode_replace_subtitle),
                        destructive = true,
                        onClick = {
                            pendingImportUri = null
                            onImportBackup(uri, RestoreMode.REPLACE)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingImportUri = null }) {
                    Text(stringResource(R.string.date_picker_cancel))
                }
            }
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
                    val notifications = draft.notifications
                    fun persistNotifications(updated: NotificationSettings) =
                        persist(draft.copy(notifications = updated))

                    Spacer(modifier = Modifier.height(14.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.settings_individual_alerts),
                        description = stringResource(R.string.settings_individual_alerts_desc),
                        checked = notifications.individualEnabled,
                        onCheckedChange = { persistNotifications(notifications.copy(individualEnabled = it)) }
                    )
                    if (notifications.individualEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.settings_lead_days_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1, 2, 3, 5, 7).forEach { days ->
                                SettingsChoiceChip(
                                    label = days.toString(),
                                    selected = notifications.leadDays == days,
                                    onClick = { persistNotifications(notifications.copy(leadDays = days)) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    NotificationToggleRow(
                        title = stringResource(R.string.settings_digest),
                        description = stringResource(R.string.settings_digest_desc),
                        checked = notifications.digestEnabled,
                        onCheckedChange = { persistNotifications(notifications.copy(digestEnabled = it)) }
                    )
                    if (notifications.digestEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.settings_digest_frequency_label),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SettingsChoiceChip(
                                label = stringResource(R.string.settings_digest_daily),
                                selected = notifications.digestFrequency == DigestFrequency.DAILY,
                                onClick = { persistNotifications(notifications.copy(digestFrequency = DigestFrequency.DAILY)) }
                            )
                            SettingsChoiceChip(
                                label = stringResource(R.string.settings_digest_weekly),
                                selected = notifications.digestFrequency == DigestFrequency.WEEKLY,
                                onClick = { persistNotifications(notifications.copy(digestFrequency = DigestFrequency.WEEKLY)) }
                            )
                        }
                        if (notifications.digestFrequency == DigestFrequency.WEEKLY) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.settings_digest_weekday_label),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            WeekdayChips(
                                selected = notifications.digestWeekday,
                                onSelect = { persistNotifications(notifications.copy(digestWeekday = it)) }
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_remember_last_location))
                        Text(
                            text = stringResource(R.string.settings_remember_last_location_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = draft.rememberLastStorageLocation,
                        onCheckedChange = { persist(draft.copy(rememberLastStorageLocation = it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = BrandPrimary, checkedTrackColor = BrandPrimary.copy(alpha = 0.5f))
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
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
                                val newName = storageLocationRename(location, editingLocationText, draft.storageLocations, locationLabel)
                                if (newName != null) {
                                    val updatedLocations = draft.storageLocations.map { if (it == location) newName else it }
                                    val updatedDefault = if (draft.defaultStorageLocation == location) newName else draft.defaultStorageLocation
                                    val updatedLastUsed = if (draft.lastUsedStorageLocation == location) newName else draft.lastUsedStorageLocation
                                    persist(
                                        draft.copy(
                                            storageLocations = updatedLocations,
                                            defaultStorageLocation = updatedDefault,
                                            lastUsedStorageLocation = updatedLastUsed
                                        )
                                    )
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
                            Text(storageLocationLabel(location), modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                editingLocation = location
                                editingLocationText = locationLabel(location)
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_rename_location), tint = TextSecondary)
                            }
                            IconButton(
                                onClick = {
                                    val remaining = draft.storageLocations.filterNot { it == location }
                                    val updatedDefault = if (draft.defaultStorageLocation == location) remaining.first() else draft.defaultStorageLocation
                                    val updatedLastUsed = draft.lastUsedStorageLocation.takeUnless { it == location }
                                    persist(
                                        draft.copy(
                                            storageLocations = remaining,
                                            defaultStorageLocation = updatedDefault,
                                            lastUsedStorageLocation = updatedLastUsed
                                        )
                                    )
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
                        if (newName.isNotBlank() && !isExistingLocation(draft.storageLocations, newName, locationLabel)) {
                            persist(draft.copy(storageLocations = draft.storageLocations + newName))
                            newLocationText = ""
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.settings_add_location), tint = BrandPrimary)
                    }
                }
            }

            // Allergen warnings. Empty by default — this is a safety feature for the people who
            // need it, not a nag for everyone.
            SettingsSectionCard(title = stringResource(R.string.settings_section_allergens)) {
                Text(
                    text = stringResource(R.string.settings_allergens_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                draft.allergens.sorted().forEach { allergen ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Text(allergen, modifier = Modifier.weight(1f))
                        IconButton(onClick = { persist(draft.copy(allergens = draft.allergens - allergen)) }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.settings_remove_allergen_content_description),
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newAllergenText,
                        onValueChange = { newAllergenText = it },
                        placeholder = { Text(stringResource(R.string.settings_add_allergen_placeholder)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        // Stored lowercased: matching is case-insensitive anyway, and this stops
                        // "Milk" and "milk" reading as two different entries in the list.
                        val entry = newAllergenText.trim().lowercase()
                        if (entry.isNotBlank() && entry !in draft.allergens) {
                            persist(draft.copy(allergens = draft.allergens + entry))
                            newAllergenText = ""
                        }
                    }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.settings_add_allergen),
                            tint = BrandPrimary
                        )
                    }
                }
            }

            // Store reminders
            SettingsSectionCard(title = stringResource(R.string.settings_section_stores)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_store_reminders_enabled))
                        Text(
                            text = stringResource(R.string.settings_store_reminders_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = draft.storeRemindersEnabled,
                        onCheckedChange = { persist(draft.copy(storeRemindersEnabled = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = BrandPrimary)
                    )
                }

                // The stores themselves are only actionable once the reminders are armed, and
                // showing the "use my location" button before then would ask for location
                // permission for a feature the user hasn't switched on.
                if (draft.storeRemindersEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    draft.stores.forEach { store ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Text(store.label, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                persist(draft.copy(stores = draft.stores.filterNot { it.id == store.id }))
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.settings_remove_store_content_description),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    if (storeLocationFailed) {
                        Text(
                            text = stringResource(R.string.settings_store_location_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = newStoreText,
                            onValueChange = { newStoreText = it },
                            placeholder = { Text(stringResource(R.string.settings_add_store_placeholder)) },
                            singleLine = true,
                            enabled = !isResolvingStoreLocation,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            enabled = !isResolvingStoreLocation,
                            onClick = {
                                val label = newStoreText.trim()
                                if (label.isBlank()) return@IconButton
                                isResolvingStoreLocation = true
                                storeLocationFailed = false
                                onAddCurrentLocationAsStore(label) { latitude, longitude ->
                                    isResolvingStoreLocation = false
                                    if (latitude == null || longitude == null) {
                                        storeLocationFailed = true
                                        return@onAddCurrentLocationAsStore
                                    }
                                    persist(
                                        draft.copy(
                                            stores = draft.stores + StorePlace(
                                                id = "store_${'$'}{System.currentTimeMillis()}",
                                                label = label,
                                                latitude = latitude,
                                                longitude = longitude
                                            )
                                        )
                                    )
                                    newStoreText = ""
                                }
                            }
                        ) {
                            if (isResolvingStoreLocation) {
                                CircularProgressIndicator(
                                    color = BrandPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = stringResource(R.string.settings_add_store),
                                    tint = BrandPrimary
                                )
                            }
                        }
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
                Text(
                    text = stringResource(R.string.settings_backup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { exportLauncher.launch(backupFileName) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_backup_button), fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    // Not "application/json": plenty of file providers hand a .json file back as
                    // text/plain or application/octet-stream, and a strict filter greys out the
                    // very backup the user is trying to pick. The parse is what validates it.
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_import_backup_button), fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(16.dp))
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

/** A labelled on/off row inside a settings card, with a one-line explanation underneath. */
@Composable
private fun NotificationToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = BrandPrimary, checkedTrackColor = BrandPrimary.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun SettingsChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = BrandPrimary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}

/** Monday-first weekday picker; [selected] and the value passed to [onSelect] are ISO day
 *  numbers (1 = Monday .. 7 = Sunday), matching [NotificationSettings.digestWeekday]. Wraps
 *  rather than scrolls, since seven chips don't fit one row on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(selected: Int, onSelect: (Int) -> Unit) {
    val locale = Locale.getDefault()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { day ->
            SettingsChoiceChip(
                label = day.getDisplayName(TextStyle.SHORT, locale),
                selected = selected == day.value,
                onClick = { onSelect(day.value) }
            )
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

/** One choice in the restore dialog. Replace is tinted as destructive because it is: it wipes
 *  whatever is on the device before restoring. */
@Composable
private fun RestoreModeRow(
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            color = if (destructive) MaterialTheme.colorScheme.error else BrandPrimary
        )
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
