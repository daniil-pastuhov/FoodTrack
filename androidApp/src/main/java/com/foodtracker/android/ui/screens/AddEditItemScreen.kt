package com.foodtracker.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foodtracker.android.R
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.ui.components.ProductDetailSheet
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.android.util.daysRemainingLabel
import com.foodtracker.android.util.foodLabelName
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.model.AppSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.android.util.categoryFieldText
import com.foodtracker.android.util.categoryForFieldText
import com.foodtracker.android.util.categoryLabel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalTime::class)
@Composable
fun AddEditItemScreen(
    stateHolder: PantryStateHolder,
    initialItem: PantryItem?,
    prefillBarcode: String?,
    prefillName: String?,
    prefillBrand: String?,
    prefillImageUrl: String?,
    prefillExpiryDate: LocalDate?,
    prefillCategory: String?,
    /** Expiry to start a *new* item from when the scan produced no date of its own, plus where
     *  it came from so the form can say so - see [ShelfLifeSuggestion]. */
    prefillSuggestion: ShelfLifeSuggestion?,
    settings: AppSettings,
    onNavigateBack: () -> Unit,
    onSaveItem: (PantryItem) -> Unit,
    /** Called instead of a save when the user chose to add a unit to an item they already have,
     *  so the host can report it and leave this screen. */
    onDuplicateMerged: (PantryItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    val today = remember { DateUtils.today() }
    val focusManager = LocalFocusManager.current

    var name by remember {
        mutableStateOf(initialItem?.name ?: prefillName ?: "")
    }
    var brand by remember {
        mutableStateOf(initialItem?.brand ?: prefillBrand ?: "")
    }
    var barcode by remember {
        mutableStateOf(initialItem?.barcode ?: prefillBarcode ?: "")
    }
    var imageUrl by remember {
        mutableStateOf(initialItem?.imageUrl ?: prefillImageUrl)
    }
    var expiryDate by remember {
        mutableStateOf(
            initialItem?.expiryDate
                ?: prefillExpiryDate
                ?: prefillSuggestion?.date
                ?: LocalDate.fromEpochDays(today.toEpochDays() + 7)
        )
    }
    // The caption explaining where a suggested date came from is shown only until the user
    // touches the date - once they've picked one themselves, saying "same as last time" would
    // be describing a value that is no longer on screen. Editing an existing item never counts
    // as a suggestion in the first place.
    var dateWasEdited by remember { mutableStateOf(false) }
    // A suggestion produced by the embedded scan overlay below, which supersedes whatever this
    // screen was opened with.
    var overlaySuggestion by remember { mutableStateOf<ShelfLifeSuggestion?>(null) }
    val activeSuggestion = when {
        // Editing an existing item: the date shown is the item's own, not a guess.
        initialItem != null -> null
        dateWasEdited -> null
        overlaySuggestion != null -> overlaySuggestion?.takeIf { it.isInformative }
        // A real date was handed in (scanned, or carried from a shopping list entry).
        prefillExpiryDate != null -> null
        else -> prefillSuggestion?.takeIf { it.isInformative }
    }
    var storageLocation by remember {
        mutableStateOf(initialItem?.storageLocation ?: settings.storageLocationForNewItem())
    }
    val appContext = LocalContext.current
    val labelOf: (String) -> String = remember(appContext) { { categoryLabel(appContext.resources, it) } }
    // The field shows display names; this is the stored category whatever is in the field stands
    // for, so saving it unchanged keeps that exact category (see categoryForFieldText).
    var categorySource by remember { mutableStateOf(initialItem?.category ?: prefillCategory) }
    val initialCategoryText = categorySource
        ?.let { categoryFieldText(it, state.categoryPreferences, labelOf) }
        ?: ""
    var category by remember {
        mutableStateOf(initialCategoryText)
    }
    var quantity by remember {
        mutableStateOf(initialItem?.quantity ?: 1)
    }
    var notes by remember {
        mutableStateOf(initialItem?.notes ?: "")
    }
    var essential by remember {
        mutableStateOf(initialItem?.essential ?: true)
    }

    val categorySuggestions = remember(category, state.categoryPreferences) {
        if (category.isBlank()) {
            emptyList()
        } else {
            state.categoryPreferences
                .map { labelOf(it.displayName) }
                .distinct()
                .filter {
                    it.contains(category, ignoreCase = true) &&
                            !it.equals(category, ignoreCase = true)
                }
                .take(5)
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showProductDetail by remember { mutableStateOf(false) }

    // Embedded scan flow: reuses ScannerScreen as a full-screen overlay on top of this form
    // instead of navigating away, so fields already typed here (quantity, notes, storage
    // location, essential) survive a scan rather than being lost to a fresh screen instance.
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) showScanner = true
    }
    val onScanButtonClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun saveItem() {
        if (name.isNotBlank()) {
            val item = PantryItem(
                id = initialItem?.id ?: "item_${System.currentTimeMillis()}",
                name = name.trim(),
                brand = brand.trim().ifBlank { null },
                barcode = barcode.trim().ifBlank { null },
                imageUrl = imageUrl,
                expiryDate = expiryDate,
                addedDate = initialItem?.addedDate ?: today,
                storageLocation = storageLocation,
                category = category.trim().ifBlank { null }
                    ?.let { categoryForFieldText(it, categorySource, state.categoryPreferences, labelOf) }
                    ?.ifBlank { null },
                quantity = quantity,
                notes = notes.trim().ifBlank { null },
                essential = essential
            )
            onSaveItem(item)
        }
    }

    // Offered when the scan matched something already in the pantry. Adding a unit to the
    // existing item is almost always what a re-scan of the same product means; creating a second
    // row for it is the exception, so it is the secondary action rather than the default.
    val duplicate = state.duplicateOf
    if (duplicate != null && initialItem == null) {
        AlertDialog(
            onDismissRequest = { stateHolder.dismissDuplicate() },
            title = { Text(stringResource(R.string.duplicate_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.duplicate_dialog_message,
                        if (duplicate.quantity > 1) "${duplicate.name} ×${duplicate.quantity}" else duplicate.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    stateHolder.incrementQuantity(duplicate.id) { updated ->
                        onDuplicateMerged(updated)
                    }
                }) {
                    Text(stringResource(R.string.duplicate_dialog_add_one), color = BrandPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { stateHolder.dismissDuplicate() }) {
                    Text(stringResource(R.string.duplicate_dialog_separate))
                }
            }
        )
    }

    val detailNutrition = state.scannedProduct?.nutrition
    if (showProductDetail && detailNutrition != null) {
        ProductDetailSheet(
            productName = name.ifBlank { stringResource(R.string.new_item_placeholder) },
            nutrition = detailNutrition,
            warnings = state.allergenWarnings,
            onDismiss = { showProductDetail = false }
        )
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Instant.fromEpochMilliseconds(
                expiryDate.toEpochDays() * 24L * 60 * 60 * 1000
            ).toEpochMilliseconds()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val localDate = Instant.fromEpochMilliseconds(millis)
                            .toLocalDateTime(TimeZone.UTC).date
                        expiryDate = localDate
                        dateWasEdited = true
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.date_picker_ok), color = BrandPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.date_picker_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(if (initialItem != null) R.string.edit_item_title else R.string.add_item_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back_content_description))
                    }
                },
                actions = {
                    IconButton(onClick = onScanButtonClick) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = stringResource(R.string.scan_content_description)
                        )
                    }
                    IconButton(onClick = ::saveItem, enabled = name.isNotBlank()) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(
                                if (initialItem != null) R.string.update_item_button else R.string.save_item_button
                            ),
                            tint = if (name.isNotBlank()) BrandPrimary else TextSecondary.copy(alpha = 0.4f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
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
            // Product Image Header Card
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val nutrition = state.scannedProduct?.nutrition
                val hasDetail = nutrition != null && !nutrition.isEmpty
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        // Only tappable when there is something to show: Open Food Facts is
                        // crowd-sourced, so plenty of products have nothing beyond a name.
                        .clickable(enabled = hasDetail) { showProductDetail = true }
                ) {
                    if (!imageUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(imageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(80.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Fastfood,
                            contentDescription = null,
                            tint = BrandPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (name.isNotBlank()) name else stringResource(R.string.new_item_placeholder),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (barcode.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.barcode_format, barcode),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Allergen warning, above everything else on the form: it is the one thing on this
            // screen someone might need to act on before reading anything else.
            if (state.allergenWarnings.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { showProductDetail = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            R.string.allergen_warning_format,
                            state.allergenWarnings.joinToString(", ")
                        ),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Name suggestions from the on-device classifier, for a photographed product with no
            // barcode to look up. Only offered while the name is still empty: once the user has
            // typed something, replacing it from under them is worse than not helping.
            if (name.isBlank() && (state.isLabelingPhoto || state.photoLabelSuggestions.isNotEmpty())) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(
                            if (state.isLabelingPhoto) R.string.photo_suggestions_analyzing
                            else R.string.photo_suggestions_title
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    if (!state.isLabelingPhoto) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.photoLabelSuggestions.forEach { suggestion ->
                                val label = foodLabelName(context, suggestion) ?: return@forEach
                                SuggestionChip(
                                    onClick = {
                                        name = label
                                        // The classifier's category is coarse but real, and it
                                        // is what lets the per-category default shelf life apply
                                        // to an item with no barcode to remember one against.
                                        if (category.isBlank()) {
                                            categorySource = suggestion.category
                                            category = categoryFieldText(suggestion.category, state.categoryPreferences, labelOf)
                                        }
                                        focusManager.clearFocus()
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }

            // Name Field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.product_name_label)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Brand Field
            OutlinedTextField(
                value = brand,
                onValueChange = { brand = it },
                label = { Text(stringResource(R.string.brand_label)) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Expiration Date Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.expiry_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Date selector row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { showDatePicker = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = BrandPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = formatLocalDate(expiryDate),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = daysRemainingLabel((expiryDate.toEpochDays() - today.toEpochDays()).toInt()),
                            color = BrandPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (activeSuggestion != null) {
                        val dayCount = activeSuggestion.days ?: 0
                        val spanLabel = pluralStringResource(
                            R.plurals.expiry_suggestion_days,
                            dayCount,
                            dayCount
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = BrandPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when (activeSuggestion.source) {
                                    ShelfLifeSource.CATEGORY_DEFAULT -> stringResource(
                                        R.string.expiry_suggestion_category,
                                        category.ifBlank { stringResource(R.string.shopping_list_uncategorized) },
                                        spanLabel
                                    )
                                    else -> stringResource(R.string.expiry_suggestion_remembered, spanLabel)
                                },
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // A year this far off is almost always a mis-read (e.g. "27" taken as year
                    // 27 AD) or a lot number, so say so instead of quietly saving it.
                    if (!DateUtils.isPlausibleExpiryDate(expiryDate, today)) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(
                                    R.string.expiry_date_implausible_warning,
                                    DateUtils.MAX_PLAUSIBLE_EXPIRY_YEARS
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick offset chips
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        QuickDateChip(label = stringResource(R.string.quick_date_3_days)) {
                            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + 3)
                            dateWasEdited = true
                        }
                        QuickDateChip(label = stringResource(R.string.quick_date_1_week)) {
                            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + 7)
                            dateWasEdited = true
                        }
                        QuickDateChip(label = stringResource(R.string.quick_date_2_weeks)) {
                            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + 14)
                            dateWasEdited = true
                        }
                        QuickDateChip(label = stringResource(R.string.quick_date_1_month)) {
                            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + 30)
                            dateWasEdited = true
                        }
                    }
                }
            }

            // Storage Location
            Text(
                text = stringResource(R.string.storage_location_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                settings.storageLocations.forEach { location ->
                    FilterChip(
                        selected = storageLocation == location,
                        onClick = { storageLocation = location },
                        label = { Text(storageLocationLabel(location)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrandPrimary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }

            // Category Field
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.category_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                if (categorySuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categorySuggestions.forEach { suggestion ->
                            SuggestionChip(
                                onClick = {
                                    category = suggestion
                                    focusManager.clearFocus()
                                },
                                label = { Text(suggestion) }
                            )
                        }
                    }
                }
            }

            // Quantity Stepper
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = stringResource(R.string.quantity_label),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { if (quantity > 1) quantity -= 1 },
                    enabled = quantity > 1
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = stringResource(R.string.quantity_decrease_content_description)
                    )
                }
                Text(
                    text = quantity.toString(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.width(24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                IconButton(onClick = { quantity += 1 }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.quantity_increase_content_description)
                    )
                }
            }

            // Essential toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.essential_item_label),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.essential_item_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Switch(checked = essential, onCheckedChange = { essential = it })
            }

            // Notes Field
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.notes_label)) },
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Save Button
            Button(
                onClick = ::saveItem,
                enabled = name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(if (initialItem != null) R.string.update_item_button else R.string.save_item_button),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
        }
    }

    // Intercepts the system back gesture/button while the scan overlay is open so it closes
    // just the overlay rather than falling through to the outer navigation's predictive-back
    // handler, which would otherwise discard this whole Add/Edit screen instead.
    BackHandler(enabled = showScanner) {
        stateHolder.clearScanState()
        showScanner = false
    }

    if (showScanner) {
        ScannerScreen(
            stateHolder = stateHolder,
            onNavigateBack = {
                stateHolder.clearScanState()
                showScanner = false
            },
            onProceedToAddItem = { scannedBarcode, scannedName, scannedBrand, scannedImageUrl, scannedExpiryDate, scannedCategory, scannedSuggestion ->
                scannedBarcode?.let { barcode = it }
                scannedName?.let { name = it }
                scannedBrand?.let { brand = it }
                scannedImageUrl?.let { imageUrl = it }
                scannedCategory?.let {
                    categorySource = it
                    category = categoryFieldText(it, state.categoryPreferences, labelOf)
                }
                // A date read off the packaging wins; otherwise fall back to what this barcode
                // (or its category) suggests. Either way this is the scan's answer rather than
                // the user's, so it does not count as an edit and the caption stays visible.
                val resolvedDate = scannedExpiryDate ?: scannedSuggestion?.date
                if (resolvedDate != null) {
                    expiryDate = resolvedDate
                    dateWasEdited = false
                }
                overlaySuggestion = scannedSuggestion.takeIf { scannedExpiryDate == null }
                stateHolder.clearScanState()
                showScanner = false
            },
            showManualEntryShortcut = false,
            // Batch and Receipt commit straight to the pantry, bypassing the form this overlay
            // is sitting on, and this host has no way to import a shared shopping list either.
            // Offering a mode whose result lands nowhere is offering something that can't work.
            enabledModes = listOf(
                CameraScanMode.BARCODE,
                CameraScanMode.DATE_OCR,
                CameraScanMode.PHOTO
            ),
            // The "+1 to the one you already have" prompt belongs to the scan-first flow. Here
            // the user is already filling in a specific item, and merging would navigate away
            // and discard whatever they had typed.
            checkDuplicates = false
        )
    }
    }
}

@Composable
private fun QuickDateChip(
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        shape = RoundedCornerShape(8.dp)
    )
}
