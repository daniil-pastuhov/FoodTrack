package com.foodtracker.android.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.foodtracker.android.R
import com.foodtracker.android.notification.NotificationHelper
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderChip
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderSegmented
import com.foodtracker.android.ui.design.LarderSwitch
import com.foodtracker.android.ui.design.LarderTextField
import com.foodtracker.android.ui.design.LedeHeader
import com.foodtracker.android.ui.design.SectionHeader
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.backup.RestoreMode
import com.foodtracker.model.AppSettings
import com.foodtracker.model.AppTheme
import com.foodtracker.model.NotificationSettings
import com.foodtracker.model.ReminderStyle
import com.foodtracker.model.StorePlace
import com.foodtracker.model.digestEveryDays
import com.foodtracker.model.reminderStyle
import com.foodtracker.model.withDigestEveryDays
import com.foodtracker.model.withEnabled
import com.foodtracker.model.withReminderStyle
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** The width of the section list on expanded windows. */
private val SectionListWidth = 300.dp

/** The widest a form gets, so fields don't stretch across a tablet. */
private val FormMaxWidth = 640.dp

/** The longest label that fits on one line in a third of a phone-width card. */
private const val MAX_EQUAL_SEGMENT_LABEL = 10

/** One page of the expanded layout. Categories isn't one: it opens its own screen. */
private enum class SettingsSection { Notifications, Shops, Threshold, Locations, Allergens, Appearance, Data }

/** Whether section cards show their own heading. The expanded layout titles the pane instead. */
private val LocalShowsSectionHeads = staticCompositionLocalOf { true }

/**
 * Everything on one page in four groups on phones; a list of sections with their current values,
 * and one section beside it, when expanded. Choices save as they're made.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onClearAllData: () -> Unit,
    onManageCategories: () -> Unit,
    /** Writes a full backup to the document the user picked. */
    onExportBackup: (Uri, (Boolean) -> Unit) -> Unit,
    /** Restores from the document the user picked, in the chosen mode. */
    onImportBackup: (Uri, RestoreMode) -> Unit,
    /** Default filename offered in the save dialog, stamped with today's date. */
    backupFileName: String,
    /** How many items a Clear all data would remove, read when that dialog opens. */
    pantryItemCount: () -> Int = { 0 },
    /** Asks for location permission and, once granted, reports the device's current position so
     *  it can be saved as a store. Null coordinates mean the user declined or no fix arrived. */
    onAddCurrentLocationAsStore: (label: String, onResolved: (latitude: Double?, longitude: Double?) -> Unit) -> Unit = { _, onResolved -> onResolved(null, null) },
    modifier: Modifier = Modifier,
    // See PantryDashboardScreen's parameter of the same name.
    bottomOverlayClearance: Dp = 160.dp
) {
    val context = LocalContext.current
    val snackbars = LocalLarderSnackbars.current
    var draft by remember { mutableStateOf(settings) }
    // A Replace restore changes settings underneath this screen; without this the next choice
    // made here would write the old ones back.
    LaunchedEffect(settings) { draft = settings }

    var section by rememberSaveable { mutableStateOf(SettingsSection.Notifications) }
    var newStoreText by rememberSaveable { mutableStateOf("") }
    var isResolvingStoreLocation by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var clearItemCount by remember { mutableStateOf<Int?>(null) }
    // Held between picking a file and choosing how to apply it: the mode question only makes
    // sense once we know there is a file, and asking it first would strand the answer if the
    // user then backed out of the picker.
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    // Counted rather than launched directly: Try again can be tapped after leaving this tab,
    // when the launcher is gone and launching it would throw.
    var exportRequests by remember { mutableIntStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            onExportBackup(it) { ok ->
                if (ok) {
                    snackbars.show(context.getString(R.string.backup_export_success), null, null)
                } else {
                    snackbars.show(
                        context.getString(R.string.backup_export_failed),
                        context.getString(R.string.settings_try_again)
                    ) { exportRequests++ }
                }
            }
        }
    }
    LaunchedEffect(exportRequests) {
        if (exportRequests > 0) exportLauncher.launch(backupFileName)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> pendingImportUri = uri }

    // Re-checked on every resume, since the usual fix happens in system settings and the user
    // comes straight back here afterwards.
    var blockedNotificationsIntent by remember { mutableStateOf<Intent?>(null) }
    LifecycleResumeEffect(draft.notifications) {
        blockedNotificationsIntent = NotificationHelper.blockedNotificationsSettingsIntent(context, draft.notifications)
        onPauseOrDispose { }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(draft)
        draft = updated
        onSave(updated)
    }

    fun saveCurrentLocationAsStore() {
        val label = newStoreText.trim()
        if (label.isEmpty() || isResolvingStoreLocation) return
        isResolvingStoreLocation = true
        onAddCurrentLocationAsStore(label) { latitude, longitude ->
            isResolvingStoreLocation = false
            if (latitude == null || longitude == null) {
                snackbars.show(
                    context.getString(R.string.settings_store_location_failed),
                    context.getString(R.string.settings_try_again)
                ) { saveCurrentLocationAsStore() }
            } else {
                update {
                    it.copy(
                        stores = it.stores + StorePlace(
                            id = "store_${System.currentTimeMillis()}",
                            label = label,
                            latitude = latitude,
                            longitude = longitude
                        )
                    )
                }
                newStoreText = ""
            }
        }
    }

    fun showRemoved(name: String, undo: () -> Unit) {
        snackbars.show(
            context.getString(R.string.item_removed_snackbar, name),
            context.getString(R.string.undo_action),
            undo
        )
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = draft.notifications.hour,
            initialMinute = draft.notifications.minute,
            is24Hour = true
        )
        LarderDialog(
            title = stringResource(R.string.settings_time_label),
            confirmLabel = stringResource(R.string.date_picker_ok),
            onConfirm = {
                update {
                    it.copy(notifications = it.notifications.copy(hour = timeState.hour, minute = timeState.minute))
                }
                showTimePicker = false
            },
            onDismissRequest = { showTimePicker = false },
            dismissLabel = stringResource(R.string.date_picker_cancel)
        ) {
            TimePicker(state = timeState)
        }
    }

    pendingImportUri?.let { uri ->
        LarderDialog(
            title = stringResource(R.string.settings_import_backup_button),
            confirmLabel = null,
            onConfirm = {},
            onDismissRequest = { pendingImportUri = null },
            text = stringResource(R.string.restore_mode_message),
            dismissLabel = stringResource(R.string.restore_mode_cancel)
        ) {
            RestoreChoice(
                title = stringResource(R.string.restore_mode_merge_title),
                subtitle = stringResource(R.string.restore_mode_merge_subtitle),
                onClick = {
                    pendingImportUri = null
                    onImportBackup(uri, RestoreMode.MERGE)
                }
            )
            RestoreChoice(
                title = stringResource(R.string.restore_mode_replace_title),
                subtitle = stringResource(R.string.restore_mode_replace_subtitle),
                destructive = true,
                onClick = {
                    pendingImportUri = null
                    onImportBackup(uri, RestoreMode.REPLACE)
                }
            )
        }
    }

    clearItemCount?.let { count ->
        LarderDialog(
            title = stringResource(R.string.clear_data_dialog_title),
            text = if (count > 0) {
                pluralStringResource(R.plurals.clear_data_dialog_count, count, count)
            } else {
                stringResource(R.string.clear_data_dialog_message)
            },
            confirmLabel = stringResource(R.string.clear_data_confirm),
            onConfirm = {
                clearItemCount = null
                onClearAllData()
            },
            // Irreversible, so a tap outside must not count as an answer either way.
            onDismissRequest = {},
            dismissLabel = stringResource(R.string.clear_data_keep),
            onDismiss = { clearItemCount = null },
            destructive = true
        )
    }

    @Composable
    fun NotificationsContent() = NotificationsSection(
        notifications = draft.notifications,
        onChange = { updated -> update { it.copy(notifications = updated) } },
        onPickTime = { showTimePicker = true },
        onOpenBlockedSettings = blockedNotificationsIntent?.let { intent -> { context.startActivity(intent) } }
    )

    @Composable
    fun ShopsContent() = ShopsSection(
        enabled = draft.storeRemindersEnabled,
        stores = draft.stores,
        newStoreName = newStoreText,
        isSaving = isResolvingStoreLocation,
        onEnabledChange = { enabled -> update { it.copy(storeRemindersEnabled = enabled) } },
        onNewStoreNameChange = { newStoreText = it },
        onSaveStore = ::saveCurrentLocationAsStore,
        onRemoveStore = { index ->
            draft.removingStore(index)?.let { (updated, store) ->
                update { updated }
                showRemoved(store.label) { update { it.restoringStore(store, index) } }
            }
        }
    )

    @Composable
    fun ThresholdContent() = ThresholdSection(
        days = draft.expiringSoonDays,
        onChange = { days -> update { it.copy(expiringSoonDays = days) } }
    )

    @Composable
    fun LocationsContent() = LocationsSection(
        settings = draft,
        onUpdate = ::update,
        onRemove = { location, name ->
            draft.removingLocation(location)?.let { (updated, removed) ->
                update { updated }
                showRemoved(name) { update { it.restoringLocation(removed) } }
            }
        }
    )

    @Composable
    fun AllergensContent() = AllergensSection(
        allergens = draft.allergens,
        onAdd = { input -> update { it.copy(allergens = it.allergens.addingAllergen(input)) } },
        onRemove = { allergen ->
            update { it.copy(allergens = it.allergens - allergen) }
            showRemoved(allergen) { update { it.copy(allergens = it.allergens + allergen) } }
        }
    )

    @Composable
    fun AppearanceContent() = AppearanceSection(
        theme = draft.theme,
        onChange = { theme -> update { it.copy(theme = theme) } }
    )

    @Composable
    fun DataContent() = DataSection(
        onExport = { exportRequests++ },
        // Not "application/json": plenty of file providers hand a .json file back as text/plain
        // or application/octet-stream, and a strict filter greys out the very backup the user is
        // trying to pick. The parse is what validates it.
        onImport = { importLauncher.launch(arrayOf("*/*")) },
        onClear = { clearItemCount = pantryItemCount() }
    )

    if (isExpanded()) {
        Row(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(modifier = Modifier.width(SectionListWidth).fillMaxHeight()) {
                LedeHeader(lede = stringResource(R.string.settings_title))
                SectionList(
                    settings = draft,
                    selected = section,
                    onSelect = { section = it },
                    onManageCategories = onManageCategories,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = LarderSpace.s4)
                        .padding(bottom = bottomOverlayClearance)
                )
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                LedeHeader(lede = sectionTitle(section), brand = sectionGroup(section).orEmpty())
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = LarderSpace.s4)
                        .padding(bottom = bottomOverlayClearance)
                ) {
                    CompositionLocalProvider(LocalShowsSectionHeads provides false) {
                        Column(modifier = Modifier.widthIn(max = FormMaxWidth)) {
                            when (section) {
                                SettingsSection.Notifications -> NotificationsContent()
                                SettingsSection.Shops -> ShopsContent()
                                SettingsSection.Threshold -> ThresholdContent()
                                SettingsSection.Locations -> LocationsContent()
                                SettingsSection.Allergens -> AllergensContent()
                                SettingsSection.Appearance -> AppearanceContent()
                                SettingsSection.Data -> DataContent()
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LedeHeader(lede = stringResource(R.string.settings_title))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomOverlayClearance),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = FormMaxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = LarderSpace.s4),
                    verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
                ) {
                    GroupTitle(stringResource(R.string.settings_group_reminders), first = true)
                    NotificationsContent()
                    ShopsContent()
                    GroupTitle(stringResource(R.string.settings_group_kitchen))
                    ThresholdContent()
                    LocationsContent()
                    CategoriesSection(onManageCategories)
                    AllergensContent()
                    GroupTitle(stringResource(R.string.settings_section_appearance))
                    AppearanceContent()
                    GroupTitle(stringResource(R.string.settings_section_data))
                    DataContent()
                }
            }
        }
    }
}

@Composable
private fun sectionTitle(section: SettingsSection): String = stringResource(
    when (section) {
        SettingsSection.Notifications -> R.string.settings_section_notifications
        SettingsSection.Shops -> R.string.settings_section_stores
        SettingsSection.Threshold -> R.string.settings_section_expiring_soon
        SettingsSection.Locations -> R.string.settings_section_locations
        SettingsSection.Allergens -> R.string.settings_section_allergens
        SettingsSection.Appearance -> R.string.settings_section_appearance
        SettingsSection.Data -> R.string.settings_section_data
    }
)

/** The group a section sits in, or null for the two that are a group of their own. */
@Composable
private fun sectionGroup(section: SettingsSection): String? = when (section) {
    SettingsSection.Notifications, SettingsSection.Shops -> stringResource(R.string.settings_group_reminders)
    SettingsSection.Threshold, SettingsSection.Locations, SettingsSection.Allergens ->
        stringResource(R.string.settings_group_kitchen)
    SettingsSection.Appearance, SettingsSection.Data -> null
}

/** The left pane when expanded: every section with its current value. */
@Composable
private fun SectionList(
    settings: AppSettings,
    selected: SettingsSection,
    onSelect: (SettingsSection) -> Unit,
    onManageCategories: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    @Composable
    fun Entry(section: SettingsSection, icon: LarderIcons, value: String) = SectionRow(
        icon = icon,
        title = sectionTitle(section),
        value = value,
        selected = section == selected,
        onClick = { onSelect(section) }
    )

    Column(modifier = modifier) {
        SectionHeader(stringResource(R.string.settings_group_reminders), first = true)
        LarderGroup {
            Entry(SettingsSection.Notifications, LarderIcons.Bell, reminderSummary(settings.notifications))
            LarderDivider()
            Entry(
                SettingsSection.Shops,
                LarderIcons.Store,
                when {
                    !settings.storeRemindersEnabled -> stringResource(R.string.settings_off)
                    settings.stores.isEmpty() -> stringResource(R.string.settings_add_store)
                    else -> settings.stores.joinToString { it.label }
                }
            )
        }
        SectionHeader(stringResource(R.string.settings_group_kitchen))
        LarderGroup {
            Entry(
                SettingsSection.Threshold,
                LarderIcons.Clock,
                pluralStringResource(
                    R.plurals.category_settings_shelf_life_days_format,
                    settings.expiringSoonDays,
                    settings.expiringSoonDays
                )
            )
            LarderDivider()
            Entry(
                SettingsSection.Locations,
                LarderIcons.Refrigerator,
                settings.storageLocations.joinToString { storageLocationLabel(context.resources, it) }
            )
            LarderDivider()
            SectionRow(
                icon = LarderIcons.Layers,
                title = stringResource(R.string.settings_section_categories),
                value = stringResource(R.string.settings_manage_categories_button),
                selected = false,
                onClick = onManageCategories,
                showsChevron = true
            )
            LarderDivider()
            Entry(
                SettingsSection.Allergens,
                LarderIcons.CircleAlert,
                if (settings.allergens.isEmpty()) {
                    stringResource(R.string.settings_allergens_none)
                } else {
                    settings.allergens.sorted().joinToString()
                }
            )
        }
        Spacer(Modifier.height(LarderSpace.s4))
        LarderGroup {
            Entry(SettingsSection.Appearance, LarderIcons.Sparkles, themeLabel(settings.theme))
        }
        Spacer(Modifier.height(LarderSpace.s4))
        LarderGroup {
            Entry(SettingsSection.Data, LarderIcons.Archive, stringResource(R.string.settings_data_summary))
        }
    }
}

@Composable
private fun SectionRow(
    icon: LarderIcons,
    title: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    showsChevron: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val titleColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface
    val valueColor = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) scheme.primaryContainer else scheme.surface)
            .selectable(selected = selected, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(icon, contentDescription = null, tint = valueColor)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 2)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showsChevron) LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = valueColor)
    }
}

/** "Each item · 1 day before": the section list's short form of [reminderPreview]. */
@Composable
private fun reminderSummary(settings: NotificationSettings): String {
    if (!settings.enabled) return stringResource(R.string.settings_off)
    val lead = pluralStringResource(R.plurals.settings_lead_days_phrase, settings.leadDays, settings.leadDays)
    return when (settings.reminderStyle) {
        ReminderStyle.EACH_ITEM -> "${stringResource(R.string.settings_reminder_style_each_item)} · $lead"
        ReminderStyle.SUMMARY ->
            "${stringResource(R.string.settings_reminder_style_summary)} · ${digestEveryDaysLabel(settings.digestEveryDays)}"
        ReminderStyle.BOTH -> "${stringResource(R.string.settings_reminder_style_both)} · $lead"
        null -> stringResource(R.string.settings_reminder_preview_none)
    }
}

@Composable
private fun themeLabel(theme: AppTheme): String = stringResource(
    when (theme) {
        AppTheme.SYSTEM -> R.string.settings_theme_system
        AppTheme.LIGHT -> R.string.settings_theme_light
        AppTheme.DARK -> R.string.settings_theme_dark
    }
)

/** The heading of one of the phone page's four groups. */
@Composable
private fun GroupTitle(text: String, first: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier
            .padding(top = if (first) LarderSpace.s1 else LarderSpace.s3)
            .semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
}

/** One section on its sheet of paper. A null [title] is for the sections their group names. */
@Composable
private fun SectionCard(
    title: String?,
    icon: LarderIcons?,
    content: @Composable ColumnScope.() -> Unit
) {
    LarderGroup {
        Column(
            modifier = Modifier.padding(LarderSpace.s4),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
        ) {
            if (title != null && LocalShowsSectionHeads.current) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    icon?.let { LarderIcon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun SectionDescription(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A label above a control. */
@Composable
private fun ControlLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The whole row toggles, so the switch needs no label of its own. */
@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LarderSize.touch)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = LarderSpace.s3)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            description?.let { SectionDescription(it) }
        }
        LarderSwitch(checked = checked, onCheckedChange = null)
    }
}

/** A row that shows a value and opens something. */
@Composable
private fun ValueRow(icon: LarderIcons, title: String, onClick: () -> Unit, value: String? = null, showsChevron: Boolean = false) {
    val shape = RoundedCornerShape(LarderRadius.md)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .heightIn(min = LarderSize.control)
            .padding(horizontal = LarderSpace.s3, vertical = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(icon, contentDescription = null, tint = scheme.onSurfaceVariant)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        value?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface) }
        if (showsChevron) LarderIcon(LarderIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant)
    }
}

/** A run of settings rows that expands into place when it appears and collapses when it goes,
 *  instead of making everything below it jump. */
@Composable
private fun ExpandingBlock(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3), content = content)
    }
}

/** A field with the round add button beside it. */
@Composable
private fun AddRow(
    field: @Composable (Modifier) -> Unit,
    addDescription: String,
    onAdd: () -> Unit,
    icon: LarderIcons = LarderIcons.Plus,
    // Clears the field's label, so the button lines up with the input.
    topPadding: Dp = LarderSpace.s6
) {
    Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2), verticalAlignment = Alignment.Top) {
        field(Modifier.weight(1f))
        LarderIconButton(
            icon = icon,
            contentDescription = addDescription,
            onClick = onAdd,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(top = topPadding)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationsSection(
    notifications: NotificationSettings,
    onChange: (NotificationSettings) -> Unit,
    onPickTime: () -> Unit,
    /** Set while the system won't deliver the reminders turned on here. */
    onOpenBlockedSettings: (() -> Unit)?
) {
    SectionCard(stringResource(R.string.settings_section_notifications), LarderIcons.Bell) {
        SwitchRow(
            title = stringResource(R.string.settings_notifications_enabled),
            checked = notifications.enabled,
            onCheckedChange = { onChange(notifications.withEnabled(it)) }
        )

        ExpandingBlock(visible = notifications.enabled) {
            ExpandingBlock(visible = onOpenBlockedSettings != null) {
                LarderCallout(
                    kind = LarderCalloutKind.Warning,
                    icon = LarderIcons.BellOff,
                    title = stringResource(R.string.settings_notifications_blocked_title),
                    message = stringResource(R.string.settings_notifications_blocked),
                    actionLabel = stringResource(R.string.settings_notifications_open_settings),
                    onAction = { onOpenBlockedSettings?.invoke() }
                )
            }

            ControlLabel(stringResource(R.string.settings_reminder_style_label))
            // Equal thirds, unless a label as long as Russian's "По продуктам" would wrap in one.
            val sharesWidth = stringResource(R.string.settings_reminder_style_each_item).length <= MAX_EQUAL_SEGMENT_LABEL
            LarderSegmented(
                options = listOf(ReminderStyle.EACH_ITEM, ReminderStyle.SUMMARY, ReminderStyle.BOTH),
                selected = notifications.reminderStyle,
                onSelect = { style -> style?.let { onChange(notifications.withReminderStyle(it)) } },
                label = { style ->
                    stringResource(
                        when (style) {
                            ReminderStyle.SUMMARY -> R.string.settings_reminder_style_summary
                            ReminderStyle.BOTH -> R.string.settings_reminder_style_both
                            else -> R.string.settings_reminder_style_each_item
                        }
                    )
                },
                modifier = if (sharesWidth) Modifier.fillMaxWidth() else Modifier,
                fill = sharesWidth
            )

            ExpandingBlock(visible = notifications.individualEnabled) {
                ControlLabel(stringResource(R.string.settings_lead_days_label))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                    listOf(1, 2, 3, 5, 7).forEach { days ->
                        LarderChip(
                            label = pluralStringResource(R.plurals.settings_lead_days_phrase, days, days),
                            selected = notifications.leadDays == days,
                            onClick = { onChange(notifications.copy(leadDays = days)) }
                        )
                    }
                }
            }

            ExpandingBlock(visible = notifications.digestEnabled) {
                ControlLabel(stringResource(R.string.settings_digest_frequency_label))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                    listOf(1, 2, 3, 5, 7).forEach { days ->
                        LarderChip(
                            label = digestEveryDaysLabel(days),
                            selected = notifications.digestEveryDays == days,
                            onClick = { onChange(notifications.withDigestEveryDays(days)) }
                        )
                    }
                }
                ExpandingBlock(visible = notifications.digestEveryDays == 7) {
                    ControlLabel(stringResource(R.string.settings_digest_weekday_label))
                    WeekdayChips(
                        selected = notifications.digestWeekday,
                        onSelect = { onChange(notifications.copy(digestWeekday = it)) }
                    )
                }
            }

            ValueRow(
                icon = LarderIcons.Clock,
                title = stringResource(R.string.settings_time_label),
                value = formatReminderTime(notifications),
                onClick = onPickTime
            )

            ReminderPreview(reminderPreview(notifications))
        }
    }
}

/** The sentence saying what the choices above add up to. */
@Composable
private fun ReminderPreview(text: String) {
    val shape = RoundedCornerShape(LarderRadius.md)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(LarderSpace.s3)
            // The sentence grows to two lines for "Both" and shrinks back.
            .animateContentSize()
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        LarderIcon(LarderIcons.Bell, contentDescription = null, size = 18.dp, tint = scheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

private fun formatReminderTime(settings: NotificationSettings): String =
    "%02d:%02d".format(settings.hour, settings.minute)

/** One plain sentence saying what the current notification settings will actually do, so the
 *  user doesn't have to work it out from the individual controls. */
@Composable
private fun reminderPreview(settings: NotificationSettings): String {
    val time = formatReminderTime(settings)
    val eachItem = stringResource(
        R.string.settings_reminder_preview_each_item,
        pluralStringResource(R.plurals.settings_lead_days_phrase, settings.leadDays, settings.leadDays),
        time
    )
    val summary = when (val every = settings.digestEveryDays) {
        1 -> stringResource(R.string.settings_reminder_preview_summary_daily, time)
        7 -> stringResource(
            R.string.settings_reminder_preview_summary_weekly,
            DayOfWeek.of(settings.digestWeekday.coerceIn(1, 7)).getDisplayName(TextStyle.FULL, Locale.getDefault()),
            time
        )
        else -> stringResource(
            R.string.settings_reminder_preview_summary_every,
            pluralStringResource(R.plurals.settings_digest_every_days_phrase, every, every),
            time
        )
    }
    return when (settings.reminderStyle) {
        ReminderStyle.EACH_ITEM -> eachItem
        ReminderStyle.SUMMARY -> summary
        ReminderStyle.BOTH -> "$eachItem\n$summary"
        null -> stringResource(R.string.settings_reminder_preview_none)
    }
}

/** Chip label for a summary interval: "Daily" and "Weekly" at the ends, "N days" between. */
@Composable
private fun digestEveryDaysLabel(days: Int): String = when (days) {
    1 -> stringResource(R.string.settings_digest_daily)
    7 -> stringResource(R.string.settings_digest_weekly)
    else -> pluralStringResource(R.plurals.settings_digest_every_days_chip, days, days)
}

/** Monday-first weekday picker; [selected] and the value passed to [onSelect] are ISO day
 *  numbers (1 = Monday .. 7 = Sunday), matching [NotificationSettings.digestWeekday]. Wraps
 *  rather than scrolls, since seven chips don't fit one row on a phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(selected: Int, onSelect: (Int) -> Unit) {
    val locale = Locale.getDefault()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        DayOfWeek.entries.forEach { day ->
            LarderChip(
                label = day.getDisplayName(TextStyle.SHORT, locale),
                selected = selected == day.value,
                onClick = { onSelect(day.value) }
            )
        }
    }
}

@Composable
private fun ShopsSection(
    enabled: Boolean,
    stores: List<StorePlace>,
    newStoreName: String,
    isSaving: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onNewStoreNameChange: (String) -> Unit,
    onSaveStore: () -> Unit,
    onRemoveStore: (Int) -> Unit
) {
    SectionCard(stringResource(R.string.settings_section_stores), LarderIcons.Store) {
        SwitchRow(
            title = stringResource(R.string.settings_store_reminders_enabled),
            description = stringResource(R.string.settings_store_reminders_desc),
            checked = enabled,
            onCheckedChange = onEnabledChange
        )

        // The shops are only actionable once the reminders are armed, and offering "use my
        // location" before then would ask for location for a feature that's switched off.
        ExpandingBlock(visible = enabled) {
            if (stores.isNotEmpty()) {
                Column {
                    stores.forEachIndexed { index, store ->
                        if (index > 0) LarderDivider()
                        Row(
                            modifier = Modifier.heightIn(min = LarderSize.touch),
                            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LarderIcon(LarderIcons.Store, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                store.label,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            LarderIconButton(
                                icon = LarderIcons.X,
                                contentDescription = stringResource(R.string.settings_remove_format, store.label),
                                onClick = { onRemoveStore(index) }
                            )
                        }
                    }
                }
            }
            LarderTextField(
                value = newStoreName,
                onValueChange = onNewStoreNameChange,
                label = stringResource(R.string.settings_add_store_placeholder),
                enabled = !isSaving,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            LarderButton(
                text = stringResource(R.string.settings_add_store),
                onClick = onSaveStore,
                style = LarderButtonStyle.Secondary,
                enabled = newStoreName.isNotBlank() || isSaving,
                loading = isSaving,
                leadingIcon = LarderIcons.MapPin,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ThresholdSection(days: Int, onChange: (Int) -> Unit) {
    SectionCard(stringResource(R.string.settings_section_expiring_soon), LarderIcons.Clock) {
        SectionDescription(stringResource(R.string.settings_expiring_soon_desc))
        LarderSegmented(
            options = listOf(1, 2, 3, 5, 7),
            selected = days,
            onSelect = onChange,
            label = { it.toString() },
            modifier = Modifier.fillMaxWidth(),
            fill = true
        )
    }
}

@Composable
private fun LocationsSection(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onRemove: (location: String, shownName: String) -> Unit
) {
    val context = LocalContext.current
    val label: (String) -> String = remember(context) { { storageLocationLabel(context.resources, it) } }
    var newName by rememberSaveable { mutableStateOf("") }
    var newNameError by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var editingName by rememberSaveable { mutableStateOf("") }
    var editingError by rememberSaveable { mutableStateOf<String?>(null) }

    fun addLocation() {
        when (val check = checkNewLocation(settings.storageLocations, newName, label)) {
            LocationNameCheck.NoChange -> Unit
            is LocationNameCheck.Taken ->
                newNameError = context.getString(R.string.settings_location_taken, check.name)
            is LocationNameCheck.Accepted -> {
                onUpdate { it.copy(storageLocations = it.storageLocations + check.name) }
                newName = ""
            }
        }
    }

    fun confirmRename(location: String) {
        when (val check = checkLocationRename(location, settings.storageLocations, editingName, label)) {
            LocationNameCheck.NoChange -> editing = null
            is LocationNameCheck.Taken ->
                editingError = context.getString(R.string.settings_location_taken, check.name)
            is LocationNameCheck.Accepted -> {
                onUpdate { it.withLocationRenamed(location, check.name) }
                editing = null
            }
        }
    }

    SectionCard(stringResource(R.string.settings_section_locations), LarderIcons.Refrigerator) {
        SwitchRow(
            title = stringResource(R.string.settings_remember_last_location),
            description = stringResource(R.string.settings_remember_last_location_desc),
            checked = settings.rememberLastStorageLocation,
            onCheckedChange = { checked -> onUpdate { it.copy(rememberLastStorageLocation = checked) } }
        )
        SectionDescription(stringResource(R.string.settings_locations_desc))

        Column {
            settings.storageLocations.forEachIndexed { index, location ->
                if (index > 0) LarderDivider()
                val name = label(location)
                if (editing == location) {
                    AddRow(
                        field = { fieldModifier ->
                            LarderTextField(
                                value = editingName,
                                onValueChange = {
                                    editingName = it
                                    editingError = null
                                },
                                label = stringResource(R.string.settings_rename_location_format, name),
                                error = editingError,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { confirmRename(location) }),
                                modifier = fieldModifier.padding(vertical = LarderSpace.s2)
                            )
                        },
                        addDescription = stringResource(R.string.settings_confirm_rename),
                        onAdd = { confirmRename(location) },
                        icon = LarderIcons.Check,
                        topPadding = LarderSpace.s8
                    )
                } else {
                    LocationRow(
                        name = name,
                        isDefault = settings.defaultStorageLocation == location,
                        canDelete = settings.storageLocations.size > 1,
                        onSetDefault = { onUpdate { it.copy(defaultStorageLocation = location) } },
                        onRename = {
                            editing = location
                            editingName = name
                            editingError = null
                        },
                        onDelete = { onRemove(location, name) }
                    )
                }
            }
        }

        AddRow(
            field = { fieldModifier ->
                LarderTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        newNameError = null
                    },
                    label = stringResource(R.string.settings_add_location_placeholder),
                    error = newNameError,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { addLocation() }),
                    modifier = fieldModifier
                )
            },
            addDescription = stringResource(R.string.settings_add_location),
            onAdd = ::addLocation
        )
    }
}

@Composable
private fun LocationRow(
    name: String,
    isDefault: Boolean,
    canDelete: Boolean,
    onSetDefault: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.heightIn(min = LarderSize.touch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIconButton(
            icon = LarderIcons.Star,
            contentDescription = stringResource(R.string.settings_set_default_location_format, name),
            onClick = onSetDefault,
            tint = if (isDefault) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.semantics { selected = isDefault }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isDefault) {
                Text(
                    stringResource(R.string.settings_location_default),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        LarderIconButton(
            icon = LarderIcons.Pencil,
            contentDescription = stringResource(R.string.settings_rename_location_format, name),
            onClick = onRename
        )
        LarderIconButton(
            icon = LarderIcons.Trash2,
            contentDescription = stringResource(R.string.settings_delete_location_format, name),
            onClick = onDelete,
            enabled = canDelete
        )
    }
}

@Composable
private fun CategoriesSection(onManageCategories: () -> Unit) {
    SectionCard(stringResource(R.string.settings_section_categories), LarderIcons.Layers) {
        SectionDescription(stringResource(R.string.settings_categories_desc))
        ValueRow(
            icon = LarderIcons.Layers,
            title = stringResource(R.string.settings_manage_categories_button),
            onClick = onManageCategories,
            showsChevron = true
        )
    }
}

/** Empty by default: this is a safety feature for the people who need it, not a nag for everyone. */
@Composable
private fun AllergensSection(
    allergens: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var newAllergen by rememberSaveable { mutableStateOf("") }

    fun add() {
        onAdd(newAllergen)
        newAllergen = ""
    }

    SectionCard(stringResource(R.string.settings_section_allergens), LarderIcons.CircleAlert) {
        SectionDescription(stringResource(R.string.settings_allergens_desc))
        if (allergens.isNotEmpty()) {
            Column {
                allergens.sorted().forEachIndexed { index, allergen ->
                    if (index > 0) LarderDivider()
                    Row(
                        modifier = Modifier.heightIn(min = LarderSize.touch),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            allergen,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LarderIconButton(
                            icon = LarderIcons.X,
                            contentDescription = stringResource(R.string.settings_remove_format, allergen),
                            onClick = { onRemove(allergen) }
                        )
                    }
                }
            }
        }
        AddRow(
            field = { fieldModifier ->
                LarderTextField(
                    value = newAllergen,
                    onValueChange = { newAllergen = it },
                    label = stringResource(R.string.settings_add_allergen_label),
                    placeholder = stringResource(R.string.settings_add_allergen_placeholder),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = fieldModifier
                )
            },
            addDescription = stringResource(R.string.settings_add_allergen),
            onAdd = ::add
        )
    }
}

@Composable
private fun AppearanceSection(theme: AppTheme, onChange: (AppTheme) -> Unit) {
    SectionCard(title = null, icon = null) {
        LarderSegmented(
            options = listOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK),
            selected = theme,
            onSelect = onChange,
            label = { themeLabel(it) },
            modifier = Modifier.fillMaxWidth(),
            fill = true
        )
    }
}

@Composable
private fun DataSection(onExport: () -> Unit, onImport: () -> Unit, onClear: () -> Unit) {
    SectionCard(title = null, icon = null) {
        SectionDescription(stringResource(R.string.settings_backup_desc))
        LarderButton(
            text = stringResource(R.string.settings_export_backup_button),
            onClick = onExport,
            style = LarderButtonStyle.Outline,
            leadingIcon = LarderIcons.Upload,
            modifier = Modifier.fillMaxWidth()
        )
        LarderButton(
            text = stringResource(R.string.settings_import_backup_button),
            onClick = onImport,
            style = LarderButtonStyle.Outline,
            leadingIcon = LarderIcons.Download,
            modifier = Modifier.fillMaxWidth()
        )
        LarderButton(
            text = stringResource(R.string.settings_clear_data_button),
            onClick = onClear,
            style = LarderButtonStyle.DangerText,
            leadingIcon = LarderIcons.Trash2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** One choice in the restore dialog, its consequence in words. Replace also carries a danger
 *  outline, because it wipes whatever is on the device before restoring. */
@Composable
private fun RestoreChoice(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    val shape = RoundedCornerShape(LarderRadius.md)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, if (destructive) scheme.error else scheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .heightIn(min = LarderSize.touch)
            .padding(LarderSpace.s3)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (destructive) scheme.error else scheme.primary
        )
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

private val PreviewStores = listOf(
    StorePlace("a", "Supermarket by the station", 0.0, 0.0),
    StorePlace("b", "Corner shop on Mill Road", 0.0, 0.0)
)

@PreviewLightDark
@Composable
internal fun NotificationsSectionEachItemPreview() = LarderPreview {
    NotificationsSection(NotificationSettings(), {}, {}, onOpenBlockedSettings = null)
}

@PreviewLightDark
@Composable
internal fun NotificationsSectionBothWeeklyPreview() = LarderPreview {
    NotificationsSection(
        NotificationSettings().withReminderStyle(ReminderStyle.BOTH).withDigestEveryDays(7),
        {},
        {},
        onOpenBlockedSettings = null
    )
}

@PreviewLightDark
@Composable
internal fun NotificationsSectionBlockedPreview() = LarderPreview {
    NotificationsSection(NotificationSettings(), {}, {}, onOpenBlockedSettings = {})
}

@PreviewLightDark
@Composable
internal fun NotificationsSectionOffPreview() = LarderPreview {
    NotificationsSection(NotificationSettings().withEnabled(false), {}, {}, onOpenBlockedSettings = null)
}

@PreviewLightDark
@Composable
internal fun ShopsSectionSavedPreview() = LarderPreview {
    ShopsSection(true, PreviewStores, "", false, {}, {}, {}, {})
}

@PreviewLightDark
@Composable
internal fun ShopsSectionSavingPreview() = LarderPreview {
    ShopsSection(true, emptyList(), "Corner shop on Mill Road", true, {}, {}, {}, {})
}

@PreviewLightDark
@Composable
internal fun ThresholdAndAppearancePreview() = LarderPreview {
    ThresholdSection(3) {}
    AppearanceSection(AppTheme.SYSTEM) {}
}

@PreviewLightDark
@Composable
internal fun LocationsSectionPreview() = LarderPreview {
    LocationsSection(AppSettings(), {}, { _, _ -> })
}

@PreviewLightDark
@Composable
internal fun AllergensSectionPreview() = LarderPreview {
    AllergensSection(setOf("gluten", "milk", "peanuts"), {}, {})
}

@PreviewLightDark
@Composable
internal fun DataSectionPreview() = LarderPreview {
    DataSection({}, {}, {})
}

@PreviewLightDark
@Composable
internal fun RestoreChoicePreview() = LarderPreview {
    RestoreChoice("Add what's missing", "Keeps everything already here.", {})
    RestoreChoice("Replace everything", "Deletes what's on this device first.", {}, destructive = true)
}
