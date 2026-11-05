package com.foodtracker.android.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.camera.ProductImageLabeler
import com.foodtracker.android.ui.components.ProductDetailSheet
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderCard
import com.foodtracker.android.ui.design.LarderChip
import com.foodtracker.android.ui.design.LarderDatePickerDialog
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIconSize
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderStepper
import com.foodtracker.android.ui.design.LarderSwitch
import com.foodtracker.android.ui.design.LarderTabularNumbers
import com.foodtracker.android.ui.design.LarderTextField
import com.foodtracker.android.ui.design.LarderThumb
import com.foodtracker.android.ui.design.LarderTopBar
import com.foodtracker.android.ui.design.StatusChip
import com.foodtracker.android.ui.design.categoryIcon
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.util.categoryFieldText
import com.foodtracker.android.util.categoryForFieldText
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.foodLabelName
import com.foodtracker.android.util.formatLocalDate
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.model.AppSettings
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.recognition.FoodLabelSuggestion
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/** The centred column on tablets and foldables. */
private val AddItemColumnWidth = 640.dp

/** The photo in the header, larger than a list row's. */
private val AddItemHeroThumb = 72.dp

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
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

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
                ?: QuickDate.OneWeek.date(today)
        )
    }
    // Set by Save with no name, so the field says why nothing happened.
    var nameMissing by remember { mutableStateOf(false) }
    val nameFocus = remember { FocusRequester() }
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
    var storageLocationPicked by remember { mutableStateOf(false) }
    // The barcode the form was opened with, or the one last scanned from the overlay below.
    // Not the barcode text field itself: typing in it shouldn't move the location per keystroke.
    var scannedBarcodeForLocation by remember { mutableStateOf(prefillBarcode) }
    val appContext = LocalContext.current
    val labelOf: (String) -> String = remember(appContext) { { categoryLabel(appContext.resources, it) } }
    // The field shows display names; this is the stored category whatever is in the field stands
    // for, so saving it unchanged keeps that exact category (see categoryForFieldText).
    var categorySource by remember { mutableStateOf(initialItem?.category ?: prefillCategory) }
    var category by remember {
        mutableStateOf(
            categorySource?.let { categoryFieldText(it, state.categoryPreferences, labelOf) } ?: ""
        )
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
    var notificationsEnabled by remember {
        mutableStateOf(initialItem?.notificationsEnabled ?: true)
    }

    val fields = AddItemFields(
        name, brand, barcode, imageUrl, expiryDate, storageLocation, category, quantity, notes,
        essential, notificationsEnabled
    )
    // The form as it opened: what a scan or an edit brought in isn't a change until it's touched,
    // so Back leaves such a form without asking.
    var openedFields by remember { mutableStateOf(fields) }
    val hasChanges = fields != openedFields
    var showDiscard by remember { mutableStateOf(false) }

    // A scanned product the user has stored before goes back where it was put last time; the
    // last-used/default location above only stands for products the app hasn't seen stored.
    // Resolved asynchronously (it reads the product cache), so it must not override a location
    // the user has already tapped in the meantime.
    LaunchedEffect(scannedBarcodeForLocation) {
        val lookup = scannedBarcodeForLocation
        if (initialItem != null || lookup.isNullOrBlank()) return@LaunchedEffect
        val known = stateHolder.storageLocationForNewItem(lookup)
        if (!storageLocationPicked) {
            // The app's answer rather than the person's, so it doesn't count as a change.
            if (storageLocation == openedFields.storageLocation) {
                openedFields = openedFields.copy(storageLocation = known)
            }
            storageLocation = known
        }
    }

    val categorySuggestions = remember(category, state.categoryPreferences) {
        categorySuggestions(category, state.categoryPreferences.map { labelOf(it.displayName) })
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showProductDetail by remember { mutableStateOf(false) }

    // The product on this form, kept here because the scan state it came from doesn't last: the
    // scan overlay below starts from a clean slate, and the allergen warning stays for as long
    // as the product does.
    var product by remember { mutableStateOf(scannedProductFor(barcode, state.scannedProduct)) }
    var allergens by remember {
        mutableStateOf(if (product != null) state.allergenWarnings else emptyList())
    }

    // The scanner offers its own photo suggestions and hands a tapped one over as the name, so
    // this form only finishes the job when Continue left it before the classifier was done. The
    // classifier stops with the scanner, hence classifying again here.
    var photoToIdentify by remember {
        mutableStateOf(imageUrl.takeIf { state.isLabelingPhoto && name.isBlank() })
    }
    var photoSuggestions by remember { mutableStateOf(emptyList<FoodLabelSuggestion>()) }
    LaunchedEffect(photoToIdentify) {
        val photo = photoToIdentify ?: return@LaunchedEffect
        photoSuggestions = ProductImageLabeler.suggestNames(appContext, photo)
        photoToIdentify = null
    }

    // Embedded scan flow: reuses ScannerScreen as a full-screen overlay on top of this form
    // instead of navigating away, so fields already typed here (quantity, notes, storage
    // location, essential) survive a scan rather than being lost to a fresh screen instance.
    var showScanner by remember { mutableStateOf(false) }
    var scannerMode by remember { mutableStateOf(CameraScanMode.BARCODE) }

    fun openScanner(mode: CameraScanMode) {
        // Not clearScanState: a shopping list entry this form is filling in outlives the scan.
        stateHolder.clearScanResults()
        scannerMode = mode
        showScanner = true
    }

    fun closeScanner() {
        stateHolder.clearScanResults()
        showScanner = false
    }

    fun leave() {
        if (hasChanges) showDiscard = true else onNavigateBack()
    }

    fun saveItem() {
        if (name.isBlank()) {
            nameMissing = true
            nameFocus.requestFocus()
            scope.launch { scrollState.animateScrollTo(0) }
            return
        }
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
            essential = essential,
            notificationsEnabled = notificationsEnabled
        )
        onSaveItem(item)
    }

    // Only a scan the scanner didn't already ask about reaches here: one filling in a shopping
    // list entry, which is about buying the product. Adding a unit to the existing item is almost
    // always what a re-scan means, so creating a second row is the secondary action.
    val duplicate = state.duplicateOf
    if (duplicate != null && initialItem == null) {
        LarderDialog(
            title = stringResource(R.string.duplicate_dialog_title),
            text = stringResource(
                R.string.duplicate_dialog_message,
                if (duplicate.quantity > 1) "${duplicate.name} ×${duplicate.quantity}" else duplicate.name
            ),
            confirmLabel = stringResource(R.string.duplicate_dialog_add_one),
            onConfirm = { stateHolder.addOneMore(duplicate.id) { updated -> onDuplicateMerged(updated) } },
            dismissLabel = stringResource(R.string.duplicate_dialog_separate),
            onDismissRequest = { stateHolder.dismissDuplicate() }
        )
    }

    if (showDiscard) {
        LarderDialog(
            title = stringResource(R.string.discard_changes_title),
            text = if (initialItem != null) {
                stringResource(R.string.discard_edit_message, initialItem.name)
            } else {
                stringResource(R.string.discard_new_message)
            },
            confirmLabel = stringResource(R.string.discard_changes_confirm),
            onConfirm = {
                showDiscard = false
                onNavigateBack()
            },
            dismissLabel = stringResource(R.string.discard_changes_keep),
            onDismissRequest = { showDiscard = false },
            // A tap outside must not settle this; Back means keep editing.
            dismissOnClickOutside = false,
            destructive = true
        )
    }

    val detailNutrition = product?.nutrition
    if (showProductDetail && detailNutrition != null) {
        ProductDetailSheet(
            productName = name.ifBlank { stringResource(R.string.new_item_placeholder) },
            nutrition = detailNutrition,
            warnings = allergens,
            onDismiss = { showProductDetail = false }
        )
    }

    if (showDatePicker) {
        LarderDatePickerDialog(
            initialDate = expiryDate,
            onDateSelected = {
                expiryDate = it
                dateWasEdited = true
                showDatePicker = false
            },
            onDismissRequest = { showDatePicker = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LarderTopBar(
                title = stringResource(if (initialItem != null) R.string.edit_item_title else R.string.add_item_title),
                onBack = ::leave
            ) {
                LarderIconButton(
                    icon = LarderIcons.Camera,
                    contentDescription = stringResource(R.string.scan_content_description),
                    onClick = { openScanner(CameraScanMode.BARCODE) },
                    modifier = Modifier
                        .padding(end = LarderSpace.s2)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        bottomBar = {
            AddItemSaveBar(
                label = if (initialItem != null) {
                    stringResource(R.string.update_item_button)
                } else {
                    stringResource(R.string.save_item_button, storageLocationLabel(storageLocation))
                },
                onSave = ::saveItem
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = AddItemColumnWidth)
                .fillMaxWidth()
                .padding(start = LarderSpace.s4, end = LarderSpace.s4, top = LarderSpace.s2, bottom = LarderSpace.s4),
            verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)
        ) {
            if (showsAddItemHero(imageUrl, barcode)) {
                AddItemHero(
                    name = name.ifBlank { stringResource(R.string.new_item_placeholder) },
                    brand = brand,
                    barcode = barcode,
                    imageUrl = imageUrl,
                    fallbackIcon = categoryIcon(categorySource ?: category)
                )
            }

            // Allergen warning, above everything else on the form: it is the one thing on this
            // screen someone might need to act on before reading anything else.
            if (allergens.isNotEmpty()) {
                LarderCallout(
                    kind = LarderCalloutKind.Error,
                    icon = LarderIcons.TriangleAlert,
                    title = stringResource(R.string.allergen_warning_format, allergens.joinToString(", ")),
                    message = stringResource(R.string.scan_allergen_note),
                    actionLabel = stringResource(R.string.add_see_details),
                    onAction = { showProductDetail = true }
                )
            }

            // Only when there is something to show: Open Food Facts is crowd-sourced, so plenty
            // of products have nothing beyond a name.
            if (detailNutrition != null && !detailNutrition.isEmpty) {
                AddItemDetailLink(onClick = { showProductDetail = true })
            }

            // Only while the name is empty: once something is typed, replacing it from under the
            // person is worse than not helping.
            if (name.isBlank() && (photoToIdentify != null || photoSuggestions.isNotEmpty())) {
                AddItemPhotoSuggestions(
                    identifying = photoToIdentify != null,
                    labels = photoSuggestions.mapNotNull { suggestion ->
                        foodLabelName(appContext, suggestion)?.let { it to suggestion }
                    },
                    onPick = { label, suggestion ->
                        name = label
                        // The classifier's category is coarse but real, and it is what lets the
                        // per-category default shelf life apply to an item with no barcode to
                        // remember one against.
                        if (category.isBlank()) {
                            categorySource = suggestion.category
                            category = categoryFieldText(suggestion.category, state.categoryPreferences, labelOf)
                        }
                        focusManager.clearFocus()
                    }
                )
            }

            LarderTextField(
                value = name,
                onValueChange = {
                    name = it
                    if (it.isNotBlank()) nameMissing = false
                },
                label = stringResource(R.string.product_name_label),
                error = if (nameMissing) stringResource(R.string.add_name_missing) else null,
                modifier = Modifier.fillMaxWidth().focusRequester(nameFocus)
            )

            LarderTextField(
                value = brand,
                onValueChange = { brand = it },
                label = stringResource(R.string.brand_label),
                modifier = Modifier.fillMaxWidth()
            )

            val daysRemaining = (expiryDate.toEpochDays() - today.toEpochDays()).toInt()
            AddItemDateCard(
                date = expiryDate,
                today = today,
                daysRemaining = daysRemaining,
                status = ExpiryStatus.compute(daysRemaining, settings.expiringSoonDays),
                caption = activeSuggestion?.let { suggestion ->
                    val dayCount = suggestion.days ?: 0
                    val spanLabel = pluralStringResource(R.plurals.expiry_suggestion_days, dayCount, dayCount)
                    when (suggestion.source) {
                        ShelfLifeSource.CATEGORY_DEFAULT -> stringResource(
                            R.string.expiry_suggestion_category,
                            category.ifBlank { stringResource(R.string.shopping_list_uncategorized) },
                            spanLabel
                        )
                        else -> stringResource(R.string.expiry_suggestion_remembered, spanLabel)
                    }
                },
                // A year this far off is almost always a mis-read (e.g. "27" taken as year 27 AD)
                // or a lot number, so say so instead of quietly saving it. Saving stays allowed.
                warning = if (DateUtils.isPlausibleExpiryDate(expiryDate, today)) {
                    null
                } else {
                    stringResource(R.string.expiry_date_implausible_warning, DateUtils.MAX_PLAUSIBLE_EXPIRY_YEARS)
                },
                onPickDate = {
                    // The keyboard would otherwise sit over the bottom of the picker.
                    focusManager.clearFocus()
                    showDatePicker = true
                },
                onScanDate = { openScanner(CameraScanMode.DATE_OCR) },
                onQuickDate = {
                    expiryDate = it.date(today)
                    dateWasEdited = true
                }
            )

            Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)) {
                Text(
                    text = stringResource(R.string.storage_location_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                AddItemChipFlow {
                    settings.storageLocations.forEach { location ->
                        LarderChip(
                            label = storageLocationLabel(location),
                            selected = storageLocation == location,
                            onClick = {
                                storageLocation = location
                                storageLocationPicked = true
                            }
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                LarderTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = stringResource(R.string.category_label),
                    modifier = Modifier.fillMaxWidth()
                )
                if (categorySuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = LarderSpace.s1)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
                    ) {
                        categorySuggestions.forEach { suggestion ->
                            LarderChip(
                                label = suggestion,
                                selected = false,
                                onClick = {
                                    category = suggestion
                                    focusManager.clearFocus()
                                }
                            )
                        }
                    }
                }
            }

            LarderGroup {
                LarderListItem(
                    title = stringResource(R.string.quantity_label),
                    trailing = { LarderStepper(value = quantity, onValueChange = { quantity = it }) }
                )
            }

            LarderTextField(
                value = notes,
                onValueChange = { notes = it },
                label = stringResource(R.string.home_notes_label),
                placeholder = stringResource(R.string.add_notes_placeholder),
                singleLine = false,
                minLines = 3,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            LarderGroup {
                AddItemSwitchRow(
                    title = stringResource(R.string.essential_item_label),
                    description = stringResource(R.string.essential_item_description),
                    checked = essential,
                    onCheckedChange = { essential = it }
                )
                LarderDivider()
                AddItemSwitchRow(
                    title = stringResource(R.string.item_notifications_label),
                    description = stringResource(R.string.item_notifications_description),
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )
            }
        }
        }
    }

    // Declared after the host's own back handling, so it wins: a form with changes asks first.
    BackHandler(enabled = hasChanges && !showScanner) { showDiscard = true }

    // Intercepts the system back gesture/button while the scan overlay is open so it closes
    // just the overlay rather than falling through to the outer navigation's predictive-back
    // handler, which would otherwise discard this whole Add/Edit screen instead.
    BackHandler(enabled = showScanner) { closeScanner() }

    if (showScanner) {
        ScannerScreen(
            stateHolder = stateHolder,
            initialMode = scannerMode,
            onNavigateBack = ::closeScanner,
            onProceedToAddItem = { scannedBarcode, scannedName, scannedBrand, scannedImageUrl, scannedExpiryDate, scannedCategory, scannedSuggestion ->
                scannedBarcode?.let {
                    barcode = it
                    scannedBarcodeForLocation = it
                    // A new barcode is a new product, so the last one's warning goes with it.
                    product = scannedProductFor(it, state.scannedProduct)
                    allergens = if (product != null) state.allergenWarnings else emptyList()
                }
                scannedName?.let { name = it }
                scannedBrand?.let { brand = it }
                scannedImageUrl?.let { imageUrl = it }
                // A photo suggestion's category is a guess, so it leaves an existing one alone.
                if (scannedCategory != null && (scannedBarcode != null || category.isBlank())) {
                    categorySource = scannedCategory
                    category = categoryFieldText(scannedCategory, state.categoryPreferences, labelOf)
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
                photoSuggestions = emptyList()
                photoToIdentify = scannedImageUrl.takeIf { state.isLabelingPhoto && name.isBlank() }
                closeScanner()
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

/** Pinned so saving never needs a scroll to the end of a long form. The bar rides above the
 *  keyboard too, so a name can be typed and saved without dismissing it. */
@Composable
private fun AddItemSaveBar(label: String, onSave: () -> Unit) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        LarderDivider()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3)
        ) {
            LarderButton(
                text = label,
                onClick = onSave,
                leadingIcon = LarderIcons.Check,
                maxLines = 1,
                modifier = Modifier.widthIn(max = AddItemColumnWidth).fillMaxWidth()
            )
        }
    }
}

/** Appears once there is a photo or a barcode. Wraps the whole name, which the one-line field
 *  below can't show. */
@Composable
private fun AddItemHero(
    name: String,
    brand: String,
    barcode: String,
    imageUrl: String?,
    fallbackIcon: LarderIcons
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s4),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderThumb(
            imageUrl = imageUrl?.ifBlank { null },
            fallbackIcon = fallbackIcon,
            modifier = Modifier
                .clip(RoundedCornerShape(LarderRadius.lg))
                .size(AddItemHeroThumb)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (brand.isNotBlank()) {
                Text(
                    text = brand,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (barcode.isNotBlank()) {
                Text(
                    text = stringResource(R.string.barcode_format, barcode),
                    style = MaterialTheme.typography.bodyMedium.merge(LarderTabularNumbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddItemDetailLink(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LarderSize.touch)
            .clip(RoundedCornerShape(LarderRadius.md))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(LarderIcons.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            text = stringResource(R.string.product_detail_content_description),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        LarderIcon(
            LarderIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AddItemPhotoSuggestions(
    identifying: Boolean,
    labels: List<Pair<String, FoodLabelSuggestion>>,
    onPick: (String, FoodLabelSuggestion) -> Unit
) {
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (identifying) {
                CircularProgressIndicator(
                    modifier = Modifier.size(LarderIconSize.Small),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
            Text(
                text = stringResource(
                    if (identifying) R.string.photo_suggestions_analyzing else R.string.photo_suggestions_title
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!identifying) {
            AddItemChipFlow {
                labels.forEach { (label, suggestion) ->
                    LarderChip(label = label, selected = false, onClick = { onPick(label, suggestion) })
                }
            }
        }
    }
}

/** The date, its status, why it was suggested, and the ways to change it. */
@Composable
private fun AddItemDateCard(
    date: LocalDate,
    today: LocalDate,
    daysRemaining: Int,
    status: ExpiryStatus,
    caption: String?,
    warning: String?,
    onPickDate: () -> Unit,
    onScanDate: () -> Unit,
    onQuickDate: (QuickDate) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val statusText = remember(resources, locale, status, daysRemaining, date, today) {
        addItemDateLabel(resources, locale, status, daysRemaining, date, today)
    }
    val shape = RoundedCornerShape(LarderRadius.md)
    LarderCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)) {
            AddItemDateHead(onScanDate)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = LarderSize.control)
                    .clip(shape)
                    .background(scheme.surface)
                    .border(
                        width = if (warning != null) 2.dp else 1.dp,
                        color = if (warning != null) scheme.error else scheme.outline,
                        shape = shape
                    )
                    .clickable(role = Role.Button, onClick = onPickDate)
                    .padding(horizontal = LarderSpace.s3, vertical = LarderSpace.s1),
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LarderIcon(LarderIcons.CalendarDays, contentDescription = null, tint = scheme.primary)
                Text(
                    text = formatLocalDate(date),
                    style = MaterialTheme.typography.titleLarge.merge(LarderTabularNumbers),
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(status = status, text = statusText)
            }
            caption?.let {
                AddItemDateNote(it, LarderIcons.Sparkles, scheme.primary, scheme.onSurfaceVariant)
            }
            warning?.let {
                AddItemDateNote(it, LarderIcons.CircleAlert, scheme.error, scheme.error)
            }
        }
        AddItemChipFlow(modifier = Modifier.padding(top = LarderSpace.s2)) {
            QuickDate.entries.forEach { quick ->
                LarderChip(
                    label = stringResource(quick.label),
                    selected = quick.matches(date, today),
                    onClick = { onQuickDate(quick) }
                )
            }
        }
    }
}

@Composable
private fun AddItemDateNote(
    text: String,
    icon: LarderIcons,
    iconTint: Color,
    textColor: Color
) {
    Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1)) {
        LarderIcon(
            icon,
            contentDescription = null,
            size = LarderIconSize.Small,
            tint = iconTint,
            modifier = Modifier.padding(top = 1.dp)
        )
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/** The label with Scan date beside it, or under it when the two don't fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddItemDateHead(onScanDate: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.expiry_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        LarderButton(
            text = stringResource(R.string.scan_date_button),
            onClick = onScanDate,
            style = LarderButtonStyle.Text,
            leadingIcon = LarderIcons.ScanText
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddItemChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        content()
    }
}

/** The whole row is the switch, so it reads as one control and is one big target. */
@Composable
private fun AddItemSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    LarderListItem(
        title = title,
        supporting = description,
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { LarderSwitch(checked = checked, onCheckedChange = null) }
    )
}

@PreviewLightDark
@Composable
internal fun AddItemHeroPreview() = LarderPreview {
    AddItemHero("Mature cheddar", "Wyke Farms", "5012345678900", null, LarderIcons.Milk)
    AddItemHero("New Item", "", "", null, LarderIcons.Image)
    AddItemDetailLink {}
}

@PreviewLightDark
@Composable
internal fun AddItemDateCardPreview() = LarderPreview {
    val today = LocalDate(2026, 11, 4)
    AddItemDateCard(LocalDate(2026, 11, 11), today, 7, ExpiryStatus.FRESH, null, null, {}, {}, {})
    AddItemDateCard(
        LocalDate(2026, 11, 14), today, 10, ExpiryStatus.FRESH, "Same as last time · 10 days", null, {}, {}, {}
    )
    AddItemDateCard(
        LocalDate(2062, 11, 24), today, 13169, ExpiryStatus.FRESH, null,
        "This date is over 10 years away. Check the year.", {}, {}, {}
    )
}

@PreviewLightDark
@Composable
internal fun AddItemPhotoSuggestionsPreview() = LarderPreview {
    AddItemPhotoSuggestions(identifying = true, labels = emptyList(), onPick = { _, _ -> })
}

@PreviewLightDark
@Composable
internal fun AddItemSaveBarPreview() = LarderPreview {
    AddItemSaveBar("Save to Fridge") {}
    AddItemSwitchRow("Essential item", "Automatically add to your shopping list", true) {}
}
