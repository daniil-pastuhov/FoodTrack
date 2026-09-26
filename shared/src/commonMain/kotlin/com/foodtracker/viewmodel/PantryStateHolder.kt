package com.foodtracker.viewmodel

import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.backup.BackupDocument
import com.foodtracker.backup.BackupReadResult
import com.foodtracker.backup.BackupSerializer
import com.foodtracker.backup.RestoreMode
import com.foodtracker.backup.RestoreSummary
import com.foodtracker.model.AppSettings
import com.foodtracker.model.CachedProduct
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShelfLife
import com.foodtracker.model.ShelfLifeSource
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.SortOption
import com.foodtracker.model.UNCATEGORIZED_ORIGIN
import com.foodtracker.model.resolveCategoryDisplayName
import com.foodtracker.model.resolveCategoryOrigin
import com.foodtracker.match.NameSimilarity
import com.foodtracker.parser.GS1BarcodeParser
import com.foodtracker.parser.ReceiptLine
import com.foodtracker.parser.ReceiptParser
import com.foodtracker.recognition.FoodLabelSuggestion
import com.foodtracker.share.ImportedShoppingEntry
import com.foodtracker.share.ShoppingListShare
import com.foodtracker.repository.CategoryPreferenceRepository
import com.foodtracker.repository.LocalProductRepository
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.ProductNameRepository
import com.foodtracker.repository.ShoppingListRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** How close a receipt line's text must be to a known product before its details are adopted.
 *  Higher than `SHOPPING_MATCH_SUGGESTION_THRESHOLD`, which feeds a dialog the user answers;
 *  this one pre-fills a row silently, so it should only fire when fairly sure. */
private const val RECEIPT_MATCH_THRESHOLD = 0.6

/** What a receipt line was resolved to, when it was resolved to anything. */
private data class ReceiptMatch(
    val name: String,
    val barcode: String? = null,
    val brand: String? = null,
    val imageUrl: String? = null,
    val category: String? = null
)

enum class StatusFilter {
    ALL,
    EXPIRING_SOON,
    EXPIRED,
    FRESH
}

/**
 * The dashboard's filter selection, held separately from [PantryUiState] so that changing it is
 * a cheap flow write from the UI thread rather than a filter+sort run inside the click handler.
 * The actual list is derived from this downstream, on a background dispatcher.
 */
data class FilterCriteria(
    val location: String? = null,
    val category: String? = null,
    val status: StatusFilter = StatusFilter.ALL,
    val query: String = ""
)

data class PantryUiState(
    val items: List<PantryItem> = emptyList(),
    /** All items as UI rows, with expiry/status/category already derived. Used by the Calendar,
     *  which needs the full set rather than the dashboard's filtered subset. */
    val allRows: List<PantryRow> = emptyList(),
    val filteredItems: List<PantryRow> = emptyList(),
    /** All rows grouped by expiry date, for the Calendar. Derived here rather than in
     *  composition so paging between months doesn't regroup the whole pantry. */
    val itemsByExpiryDate: Map<LocalDate, List<PantryRow>> = emptyMap(),
    /** The single most urgent status among the items expiring on each date, for the Calendar's
     *  day indicator dots. Previously recomputed per day cell, per recomposition, by mapping
     *  that day's items to a throwaway status list. */
    val statusByExpiryDate: Map<LocalDate, ExpiryStatus> = emptyMap(),
    /** The Calendar's agenda: every item bucketed into Overdue / Today / Tomorrow / this week /
     *  next week / later, empty buckets dropped. */
    val agenda: List<AgendaSection> = emptyList(),
    /** Distinct resolved category names present on the current items, in the user's configured
     *  category order — the dashboard's category filter chips. */
    val availableCategories: List<String> = emptyList(),
    val selectedLocation: String? = null,
    val selectedCategory: String? = null,
    val selectedStatusFilter: StatusFilter = StatusFilter.ALL,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val expiredCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val freshCount: Int = 0,
    val settings: AppSettings = AppSettings(),
    // Scan flow state
    val scannedProduct: ProductInfo? = null,
    val productNotFoundBarcode: String? = null,
    val detectedDate: LocalDate? = null,
    /** Expiry date to pre-fill on the item this scan produces, and where it came from - a
     *  date read off the packaging, the shelf life this barcode was given last time, or its
     *  category's configured default. See [ShelfLife.suggest]. */
    val suggestedExpiry: ShelfLifeSuggestion? = null,
    /** Names suggested for a photographed, barcode-less product by the on-device image
     *  classifier (see `FoodLabels`). Empty unless a photo was just captured. */
    val photoLabelSuggestions: List<FoodLabelSuggestion> = emptyList(),
    /** Products scanned in batch mode and waiting to be reviewed and committed together. */
    val batchQueue: List<BatchScanEntry> = emptyList(),
    /** Set when a scanned barcode is already in the pantry, so the UI can offer to add a unit
     *  to that item rather than create a second row for the same product. */
    val duplicateOf: PantryItem? = null,
    /** Allergens the scanned product lists that the user asked to be warned about. Empty unless
     *  both sides have something to match. */
    val allergenWarnings: List<String> = emptyList(),
    /** True while that classification is running, so the form can say so rather than showing
     *  nothing and then popping chips in. */
    val isLabelingPhoto: Boolean = false,
    val isLookingUpBarcode: Boolean = false,
    val scanMessage: String? = null,
    /** Set while the scan flow was launched from a specific shopping list row (rather than
     *  the dashboard's general scan action), so [saveItem] knows to remove that exact entry
     *  even if it has no barcode of its own to match against (manually-added entries). */
    val scanningShoppingListEntryId: String? = null,
    // Shopping list
    val shoppingList: List<ShoppingListEntry> = emptyList(),
    /** The shopping list flattened into category-grouped rows with headers, ready to render.
     *  Grouping used to happen in composition on every emission. */
    val shoppingRows: List<ShoppingRow> = emptyList(),
    val knownProductNames: List<String> = emptyList(),
    // Category preferences: rename/reorder/enable state for each raw category value seen on a
    // product, managed from Settings (see CategorySettingsScreen / CategoryPreferenceRepository).
    val categoryPreferences: List<CategoryPreference> = emptyList(),
    /** One row per user-visible category for the Settings screen, with the origins merged onto
     *  each display name already resolved into a subtitle string. */
    val categoryRows: List<CategoryRow> = emptyList()
)

class PantryStateHolder(
    private val repository: PantryRepository,
    private val settingsRepository: SettingsRepository,
    private val shoppingListRepository: ShoppingListRepository,
    private val productNameRepository: ProductNameRepository,
    private val localProductRepository: LocalProductRepository,
    private val categoryPreferenceRepository: CategoryPreferenceRepository,
    /** Localized label for the "Other" bucket (see [UNCATEGORIZED_ORIGIN]) - seeded as that
     *  sentinel category's initial display name the first time it's registered, so it reads
     *  correctly in the device's language rather than a hardcoded English default. */
    private val uncategorizedLabel: String,
    private val openFoodFactsApi: OpenFoodFactsApi = OpenFoodFactsApi(),
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Where the pantry/shopping derivation runs (see [observeDerivedPantry]). Injectable so a
     *  test can pass [kotlin.coroutines.EmptyCoroutineContext] and have the derivation run
     *  inline on the calling thread, which is the only way to measure its cost without also
     *  measuring a thread hand-off. */
    private val computationContext: CoroutineContext = Dispatchers.Default
) {
    // Read once. getSettings() is a synchronous SharedPreferences read plus a JSON decode, and
    // it happens while the DI graph is being built — i.e. on the main thread during startup.
    private val initialSettings = settingsRepository.getSettings()

    private val _uiState = MutableStateFlow(PantryUiState(settings = initialSettings))
    val uiState: StateFlow<PantryUiState> = _uiState.asStateFlow()

    /** Dashboard filter selection. Writing this is all a click handler has to do; the filtered
     *  list is derived downstream, off the UI thread. */
    private val criteria = MutableStateFlow(FilterCriteria())

    /** Settings mirrored as a flow so the derivation below re-runs when they change, without
     *  every settings write having to recompute the list itself. */
    private val settingsState = MutableStateFlow(initialSettings)

    /** Category preferences, shared by the pantry derivation and the shopping list derivation so
     *  the underlying Room query runs once rather than once per consumer. */
    private val preferencesState = MutableStateFlow(emptyList<CategoryPreference>())

    /**
     * Everything derived from the pantry that does **not** depend on the dashboard's filter
     * selection: the per-item rows, the three counters, the calendar groupings and the category
     * chips. These are a pure function of (items, category preferences, settings).
     *
     * Kept in its own `combine` so that changing a filter doesn't re-run any of it. Folding
     * `criteria` into this stage — which an earlier version of this did — means every filter tap
     * and every keystroke rebuilds all N rows, regroups the whole pantry by expiry date and
     * recollects the category list, none of which the filter can affect. Measured on 5000 items
     * that made a filter change several times more expensive than the synchronous version it
     * replaced, even though it had moved off the UI thread.
     *
     * Must stay declared above `init`: Kotlin runs property initializers and init blocks in
     * declaration order, so `observeDerivedPantry()` would otherwise read this while it is still
     * null, and the resulting failure is silent — the coroutine dies under the SupervisorJob and
     * the pantry simply never loads.
     */
    private val basePantry: Flow<PantryDerivation.Base> =
        combine(
            repository.getItemsFlow(),
            preferencesState,
            // Only this one setting affects anything in Base. Depending on the whole AppSettings
            // would rebuild every row whenever an unrelated setting changed — the sort option in
            // particular, which affects only the filter stage below.
            settingsState.map { it.expiringSoonDays }.distinctUntilChanged()
        ) { items, preferences, expiringSoonDays ->
            PantryDerivation.deriveBase(items, preferences, expiringSoonDays)
        }.flowOn(computationContext)

    init {
        observeCategoryPreferences()
        observeDerivedPantry()
        observeShoppingList()
        observeKnownProductNames()
        observeSettingsPersistence()
        coroutineScope.launch {
            categoryPreferenceRepository.registerIfAbsent(UNCATEGORIZED_ORIGIN, Double.MAX_VALUE, uncategorizedLabel)
        }
    }

    /**
     * The single place the pantry's derived state is produced.
     *
     * Two things follow from this shape, and both are the point of the change:
     *  - `flowOn(computationContext)` puts the derivation on a background dispatcher. It used to
     *    run synchronously inside the click/`onValueChange` handler that triggered it, i.e. on
     *    the UI thread, once per keystroke while searching.
     *  - Category preferences are an input, so renaming or disabling a category now refreshes the
     *    filtered list. Previously the preferences observer updated `categoryPreferences` without
     *    recomputing `filteredItems`, leaving a display-name-based category filter stale.
     *
     * The result is computed *before* `_uiState.update`, not inside it: `update` is a CAS retry
     * loop, and with several observers writing the same state a filter+sort placed inside it can
     * run more than once per emission.
     */
    private fun observeDerivedPantry() {
        coroutineScope.launch {
            // Only this last stage re-runs when a filter changes, and all it does is filter and
            // sort an already-derived list of rows using precomputed fields.
            combine(
                basePantry,
                criteria,
                settingsState.map { it.sortOption }.distinctUntilChanged()
            ) { base, filter, sortOption ->
                Triple(
                    base,
                    filter,
                    PantryDerivation.filterAndSort(base.allRows, filter, base.expiringSoonDays, sortOption)
                )
            }
                .flowOn(computationContext)
                .collect { (base, filter, filtered) ->
                    registerCategories(base.items.mapNotNull { it.category })
                    _uiState.update { current ->
                        current.copy(
                            items = base.items,
                            allRows = base.allRows,
                            filteredItems = filtered,
                            itemsByExpiryDate = base.itemsByExpiryDate,
                            statusByExpiryDate = base.statusByExpiryDate,
                            agenda = base.agenda,
                            availableCategories = base.availableCategories,
                            expiredCount = base.expiredCount,
                            expiringSoonCount = base.expiringSoonCount,
                            freshCount = base.freshCount,
                            // The selected filter is published here, together with the list it
                            // produced, so a filter tap causes exactly one state emission and
                            // therefore one recomposition. Echoing the selection into _uiState
                            // from the setter as well — which an earlier version did, for
                            // immediate chip feedback — made every tap emit twice, and measurably
                            // doubled the P90 frame time on the filter-interaction benchmark.
                            selectedLocation = filter.location,
                            selectedCategory = filter.category,
                            selectedStatusFilter = filter.status,
                            isLoading = false
                        )
                    }
                }
        }
    }

    private fun observeShoppingList() {
        coroutineScope.launch {
            combine(
                shoppingListRepository.getEntriesFlow(),
                preferencesState
            ) { entries, preferences ->
                entries to PantryDerivation.buildShoppingRows(entries, preferences)
            }
                .flowOn(computationContext)
                .collect { (entries, rows) ->
                    registerCategories(entries.mapNotNull { it.category })
                    _uiState.update { it.copy(shoppingList = entries, shoppingRows = rows) }
                }
        }
    }

    private fun observeKnownProductNames() {
        coroutineScope.launch {
            productNameRepository.getKnownNamesFlow().collectLatest { names ->
                _uiState.update { it.copy(knownProductNames = names) }
            }
        }
    }

    private fun observeCategoryPreferences() {
        coroutineScope.launch {
            categoryPreferenceRepository.getPreferencesFlow().collectLatest { preferences ->
                preferencesState.value = preferences
                val rows = PantryDerivation.buildCategoryRows(preferences)
                _uiState.update { it.copy(categoryPreferences = preferences, categoryRows = rows) }
            }
        }
    }

    /** Auto-registers any category in [origins] not already known, so it shows up in Settings
     *  (see CategorySettingsScreen) the first time a product carrying it is scanned or added,
     *  without requiring the user to configure anything up front. Origins already known (per the
     *  latest [PantryUiState.categoryPreferences]) are skipped without a DB write. */
    private suspend fun registerCategories(origins: List<String>) {
        // Read from preferencesState rather than _uiState: it is written first in
        // observeCategoryPreferences, so it can't lag behind the preferences this derivation
        // just ran with and cause a redundant re-registration.
        val known = preferencesState.value.map { it.originName }.toSet()
        val newOrigins = origins.distinct().filterNot { it in known }
        if (newOrigins.isEmpty()) return
        var nextSortOrder = (categoryPreferenceRepository.getMaxSortOrder() ?: -1.0) + 1.0
        newOrigins.forEach { origin ->
            categoryPreferenceRepository.registerIfAbsent(origin, nextSortOrder)
            nextSortOrder += 1.0
        }
    }

    // The four filter setters below are now just flow writes: the list is re-derived downstream
    // on a background dispatcher (see observeDerivedPantry). Each of these used to run a full
    // filter + sort synchronously on the caller's thread, which is the UI thread — for the
    // search field, once per keystroke.

    /**
     * [searchQuery] is the exception to publishing the selection from the derivation: it is the
     * value of a text field, so it has to update on the same frame as the keystroke or typing
     * visibly lags and drops characters. The list it produces still arrives separately.
     */
    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        criteria.update { it.copy(query = query) }
    }

    fun onLocationSelected(location: String?) {
        criteria.update { it.copy(location = location) }
    }

    fun onCategorySelected(category: String?) {
        criteria.update { it.copy(category = category) }
    }

    fun onStatusFilterSelected(filter: StatusFilter) {
        criteria.update { it.copy(status = filter) }
    }

    fun onSortOptionSelected(option: SortOption) {
        updateSettings(_uiState.value.settings.copy(sortOption = option))
    }

    fun updateSettings(newSettings: AppSettings) {
        // Feeding the derivation re-runs the counters and the filtered list off the UI thread,
        // which is what recomputed them inline here before. Persistence happens in
        // observeSettingsPersistence, driven by this same flow.
        settingsState.value = newSettings
        _uiState.update { it.copy(settings = newSettings) }
    }

    /**
     * Persists settings off the caller's thread. `saveSettings` is a JSON encode plus a
     * SharedPreferences write, and it used to run synchronously inside `updateSettings` — that
     * is, on the UI thread, before anything else happened.
     *
     * Driven by collecting [settingsState] rather than by a `launch` per call, because separate
     * coroutines could interleave and persist two rapid changes out of order, leaving the stored
     * settings disagreeing with what the user last chose. A flow collector is inherently ordered.
     */
    private fun observeSettingsPersistence() {
        coroutineScope.launch {
            settingsState
                .drop(1) // The initial value came *from* the repository; writing it back is a no-op.
                .collect { settingsRepository.saveSettings(it) }
        }
    }


    /**
     * Builds a full backup of everything stored locally and hands the JSON to [onReady].
     *
     * Reads the repositories directly rather than snapshotting [uiState]: the ui state holds
     * only what the screens need (it has no product cache and no known-names list), and a backup
     * that quietly omitted them would restore a pantry that had forgotten every shelf life and
     * every autosuggest name the user had built up.
     */
    fun exportBackup(onReady: (json: String, document: BackupDocument) -> Unit) {
        coroutineScope.launch {
            val document = BackupDocument(
                exportedAtEpochMillis = currentTimeMillis(),
                items = repository.getItemsFlow().first(),
                shoppingList = shoppingListRepository.getEntriesFlow().first(),
                categoryPreferences = categoryPreferenceRepository.getPreferencesFlow().first(),
                products = localProductRepository.getAll(),
                knownProductNames = productNameRepository.getKnownNamesFlow().first(),
                settings = settingsState.value
            )
            onReady(BackupSerializer.encode(document), document)
        }
    }

    /**
     * Restores [raw] according to [mode], reporting the outcome through [onComplete].
     *
     * [RestoreMode.MERGE] adds only what is missing, matching pantry items and shopping entries
     * by id so re-importing the same file twice is a no-op rather than a way to double a pantry.
     * [RestoreMode.REPLACE] clears local data first and also restores the backup's settings;
     * merge deliberately leaves settings alone, since those are the settings of the device the
     * user is holding rather than of the file they are importing.
     */
    fun restoreBackup(
        raw: String,
        mode: RestoreMode,
        onComplete: (BackupReadResult, RestoreSummary) -> Unit
    ) {
        val result = BackupSerializer.decode(raw)
        if (result !is BackupReadResult.Success) {
            onComplete(result, RestoreSummary())
            return
        }
        coroutineScope.launch {
            onComplete(result, applyRestore(result.document, mode))
        }
    }

    private suspend fun applyRestore(document: BackupDocument, mode: RestoreMode): RestoreSummary {
        if (mode == RestoreMode.REPLACE) {
            repository.clearAll()
            shoppingListRepository.clearAll()
            localProductRepository.clearAll()
            categoryPreferenceRepository.clearAll()
            productNameRepository.clearAll()
        }

        // Category preferences go back first: items and shopping entries reference categories by
        // origin, and restoring those first would auto-register each origin with a default
        // display name, which the backup's own (possibly renamed) version would then have to
        // overwrite - churning the preferences table and re-running the derivation each time.
        categoryPreferenceRepository.upsertAll(document.categoryPreferences)

        val existingItemIds = repository.getItemsFlow().first().mapTo(HashSet()) { it.id }
        var itemsRestored = 0
        document.items.forEach { item ->
            if (mode == RestoreMode.MERGE && item.id in existingItemIds) return@forEach
            repository.saveItem(item)
            itemsRestored++
        }

        val existingEntryIds = shoppingListRepository.getEntriesFlow().first().mapTo(HashSet()) { it.id }
        var entriesRestored = 0
        document.shoppingList.forEach { entry ->
            if (mode == RestoreMode.MERGE && entry.id in existingEntryIds) return@forEach
            shoppingListRepository.addEntry(entry)
            entriesRestored++
        }

        localProductRepository.saveAll(document.products)
        document.knownProductNames.forEach { productNameRepository.recordName(it) }

        if (mode == RestoreMode.REPLACE) {
            updateSettings(document.settings)
        }

        return RestoreSummary(
            itemsRestored = itemsRestored,
            shoppingEntriesRestored = entriesRestored,
            productsRestored = document.products.size,
            categoriesRestored = document.categoryPreferences.size,
            settingsRestored = mode == RestoreMode.REPLACE
        )
    }

    @OptIn(ExperimentalTime::class)
    private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    fun clearAllData() {
        coroutineScope.launch {
            repository.clearAll()
        }
    }

    /** The single-item scan's in-flight lookups, cancelled by [clearScanResults] so a lookup
     *  that finishes after the user has left the scanner cannot publish its result anyway. */
    private var barcodeLookupJob: Job? = null
    private var duplicateCheckJob: Job? = null

    /**
     * Starts the single-item lookup for [barcode] - unless a scan is already on screen, its
     * lookup still running or its product (or not-found notice) waiting on the user. The camera
     * keeps reading barcodes the whole time, so without this, nudging the phone towards the
     * next product on the shelf would swap the card out from under the user. A held result is
     * let go only by [clearScanResults] ("Scan Again", or a mode switch).
     *
     * @return whether the scan was taken, so the caller can skip its own follow-ups (the
     *  duplicate check) for one that was ignored.
     */
    fun onBarcodeScanned(barcode: String): Boolean {
        val held = _uiState.value
        if (held.isLookingUpBarcode || held.scannedProduct != null || held.productNotFoundBarcode != null) {
            return false
        }
        val gs1Date = GS1BarcodeParser.extractExpiryDate(barcode)
        _uiState.update {
            it.copy(
                isLookingUpBarcode = true,
                detectedDate = gs1Date ?: it.detectedDate,
                scanMessage = if (gs1Date != null) "Extracted date from barcode: $gs1Date" else null
            )
        }

        barcodeLookupJob?.cancel()
        barcodeLookupJob = coroutineScope.launch {
            // A locally-cached product (whether it was previously found via the API or entered
            // by hand for a barcode OFF didn't know) wins over the network, so a "custom"
            // product from a past scan is recognized here instead of showing not-found again.
            val product = localProductRepository.getByBarcode(barcode) ?: openFoodFactsApi.fetchProduct(barcode)
            val suggestion = suggestExpiry(
                detectedDate = gs1Date ?: _uiState.value.detectedDate,
                barcode = barcode,
                category = product?.category
            )
            val warnings = product?.nutrition
                ?.matchedAllergens(_uiState.value.settings.allergens)
                .orEmpty()
            // Checked inside the update rather than before it: the lookup runs off the main
            // thread, and clearScanResults cancels this job before it writes, so either this
            // write sees the cancellation or the clear lands after it and wipes it.
            _uiState.update {
                if (!isActive) return@update it
                it.copy(
                    scannedProduct = product,
                    productNotFoundBarcode = if (product == null) barcode else null,
                    suggestedExpiry = suggestion,
                    allergenWarnings = warnings,
                    isLookingUpBarcode = false,
                    scanMessage = if (product != null) "Found ${product.name}" else "Product not in database - enter name"
                )
            }
        }
        return true
    }

    /**
     * Resolves the expiry date to pre-fill for a scanned product: the date read off the
     * packaging if there was one, else the shelf life this exact barcode was last given, else
     * the default configured for its category.
     *
     * The category default is read from [preferencesState] rather than re-queried - it is the
     * same list the derivation already collects, so this costs nothing. The category is
     * resolved through [resolveCategoryOrigin] first because a product's raw category may have
     * been renamed, and the default hangs off the origin.
     */
    private suspend fun suggestExpiry(
        detectedDate: LocalDate?,
        barcode: String?,
        category: String?
    ): ShelfLifeSuggestion {
        val rememberedDays = if (detectedDate == null && barcode != null) {
            localProductRepository.getShelfLifeDays(barcode)
        } else {
            null
        }
        val preferences = preferencesState.value
        val origin = resolveCategoryOrigin(category, preferences)
        val categoryDefaultDays = preferences.find { it.originName == origin }?.defaultShelfLifeDays
        return ShelfLife.suggest(
            today = DateUtils.today(),
            detectedDate = detectedDate,
            rememberedDays = rememberedDays,
            categoryDefaultDays = categoryDefaultDays
        )
    }

    // ---- Batch scan -------------------------------------------------------------------

    /**
     * Queues [barcode] for the batch-add review list.
     *
     * Scanning a barcode already in the queue bumps its quantity instead of adding a second row
     * — that is what scanning four identical yoghurts in a row means, and it also absorbs the
     * camera re-reading a product that is simply still in frame.
     *
     * The row is published immediately, before the product lookup finishes, so the queue
     * visibly responds to each scan rather than lagging behind the network.
     */
    fun enqueueBatchScan(barcode: String) {
        val existing = _uiState.value.batchQueue.find { it.barcode == barcode }
        if (existing != null) {
            updateBatchEntry(existing.copy(quantity = existing.quantity + 1))
            return
        }

        val settings = _uiState.value.settings
        val gs1Date = GS1BarcodeParser.extractExpiryDate(barcode)
        val placeholder = BatchScanEntry(
            // The barcode doubles as the row id for scanned rows, so re-scanning the same
            // product keeps finding the same row. Receipt rows get a generated id instead.
            id = barcode,
            barcode = barcode,
            name = barcode,
            expiryDate = gs1Date ?: DateUtils.today(),
            storageLocation = settings.storageLocationForNewItem(),
            expirySource = if (gs1Date != null) ShelfLifeSource.DETECTED else ShelfLifeSource.FALLBACK,
            isLookingUp = true
        )
        _uiState.update { it.copy(batchQueue = it.batchQueue + placeholder) }

        coroutineScope.launch {
            val product = localProductRepository.getByBarcode(barcode) ?: openFoodFactsApi.fetchProduct(barcode)
            val suggestion = suggestExpiry(
                detectedDate = gs1Date,
                barcode = barcode,
                category = product?.category
            )
            _uiState.update { state ->
                state.copy(
                    batchQueue = state.batchQueue.map { entry ->
                        // Re-read from state rather than closing over `placeholder`: the user
                        // may have edited or removed this row while the lookup was in flight.
                        if (entry.id != barcode || !entry.isLookingUp) entry
                        else entry.copy(
                            name = product?.name ?: entry.name,
                            brand = product?.brand,
                            imageUrl = product?.imageUrl,
                            category = product?.category,
                            expiryDate = suggestion.date,
                            expirySource = suggestion.source,
                            isLookingUp = false
                        )
                    }
                )
            }
        }
    }

    /**
     * Turns the OCR'd text of a receipt into batch-queue rows and reports how many were added.
     *
     * Everything here is a *suggestion*: the rows land in the same queue barcode scanning uses,
     * where the user reviews, edits and commits them. Receipt text is heuristic in a way a
     * barcode is not (see [ReceiptParser]), so nothing is saved without that pass.
     *
     * A parsed name is matched against the products this device already knows — the autosuggest
     * names and the local product cache — using the same scorer the shopping list matches with.
     * A confident hit adopts that product's proper name, category and learned shelf life, so a
     * receipt's `MLK SEMI 2PT` becomes the milk the user has added before, with its usual expiry
     * already filled in. A miss keeps the receipt's own text and the category default.
     *
     * Rows are appended rather than merged: two lines both reading `APPLES` may well be two
     * separate purchases, and unlike a re-scanned barcode there's no identity to prove otherwise.
     */
    fun enqueueReceiptLines(ocrLines: List<String>, onComplete: (added: Int) -> Unit = {}) {
        val parsed = ReceiptParser.parseLines(ocrLines)
        if (parsed.isEmpty()) {
            onComplete(0)
            return
        }
        coroutineScope.launch {
            val settings = _uiState.value.settings
            val cached = localProductRepository.getAll()
            val knownNames = productNameRepository.getKnownNamesFlow().first()
            val rows = parsed.map { line ->
                val match = matchReceiptLine(line, cached, knownNames)
                val suggestion = suggestExpiry(
                    detectedDate = null,
                    barcode = match?.barcode,
                    category = match?.category
                )
                BatchScanEntry(
                    id = newReceiptRowId(),
                    barcode = match?.barcode,
                    name = match?.name ?: line.name,
                    brand = match?.brand,
                    imageUrl = match?.imageUrl,
                    category = match?.category,
                    expiryDate = suggestion.date,
                    quantity = line.quantity,
                    storageLocation = settings.storageLocationForNewItem(),
                    expirySource = suggestion.source
                )
            }
            _uiState.update { it.copy(batchQueue = it.batchQueue + rows) }
            onComplete(rows.size)
        }
    }

    /**
     * The cached product a receipt line most likely refers to, or null when nothing is close
     * enough.
     *
     * Falls back to the autosuggest names when no cached product matches: those carry no
     * barcode or category, but adopting a name the user has typed before still beats keeping a
     * till's abbreviation. The threshold is deliberately higher than the shopping list's
     * suggestion threshold — that one feeds a "did you mean?" dialog the user answers, while
     * this silently pre-fills a row, so it should only fire when it is fairly sure.
     */
    private fun matchReceiptLine(
        line: ReceiptLine,
        cached: List<CachedProduct>,
        knownNames: List<String>
    ): ReceiptMatch? {
        val bestCached = cached
            .map { it to NameSimilarity.score(line.name, it.name) }
            .filter { it.second >= RECEIPT_MATCH_THRESHOLD }
            .maxByOrNull { it.second }
            ?.first
        if (bestCached != null) {
            return ReceiptMatch(
                name = bestCached.name,
                barcode = bestCached.barcode,
                brand = bestCached.brand,
                imageUrl = bestCached.imageUrl,
                category = bestCached.category
            )
        }
        val bestName = NameSimilarity.bestMatch(line.name, knownNames, RECEIPT_MATCH_THRESHOLD)
        return bestName?.let { ReceiptMatch(name = it) }
    }

    @OptIn(ExperimentalTime::class)
    private fun newReceiptRowId(): String =
        "receipt_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt()}"

    fun updateBatchEntry(entry: BatchScanEntry) {
        _uiState.update { state ->
            state.copy(batchQueue = state.batchQueue.map { if (it.id == entry.id) entry else it })
        }
    }

    fun removeBatchEntry(id: String) {
        _uiState.update { state ->
            state.copy(batchQueue = state.batchQueue.filterNot { it.id == id })
        }
    }

    fun clearBatchQueue() {
        _uiState.update { it.copy(batchQueue = emptyList()) }
    }

    /**
     * Saves everything in the queue and empties it, reporting how many items were added.
     *
     * Each row goes through [saveItem], so a batch add still learns shelf lives, caches
     * products and clears matching shopping list entries exactly as adding one by one would —
     * none of that is re-implemented here.
     */
    fun commitBatchQueue(onComplete: (added: Int) -> Unit = {}) {
        val queue = _uiState.value.batchQueue
        if (queue.isEmpty()) {
            onComplete(0)
            return
        }
        val today = DateUtils.today()
        queue.forEach { entry ->
            saveItem(
                PantryItem(
                    id = newItemId(),
                    name = entry.name,
                    brand = entry.brand,
                    barcode = entry.barcode,
                    imageUrl = entry.imageUrl,
                    expiryDate = entry.expiryDate,
                    addedDate = today,
                    storageLocation = entry.storageLocation,
                    category = entry.category,
                    quantity = entry.quantity
                )
            )
        }
        clearBatchQueue()
        onComplete(queue.size)
    }

    @OptIn(ExperimentalTime::class)
    private fun newItemId(): String = "item_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt()}"

    // ---- Duplicate detection ----------------------------------------------------------

    /**
     * Records whether [barcode] is already in the pantry, so the UI can offer adding a unit to
     * that item instead of creating a second row for the same product.
     *
     * Only the single-item scan flow asks: batch mode collapses repeats into one row's quantity
     * by itself, and asking per scan would be exactly the interruption batch mode exists to
     * avoid.
     */
    fun checkForDuplicate(barcode: String) {
        duplicateCheckJob?.cancel()
        duplicateCheckJob = coroutineScope.launch {
            val existing = repository.findByBarcode(barcode).firstOrNull()
            _uiState.update { if (isActive) it.copy(duplicateOf = existing) else it }
        }
    }

    fun dismissDuplicate() {
        _uiState.update { it.copy(duplicateOf = null) }
    }

    /** Adds one unit to an existing item - the "+1 to the one you already have" answer to a
     *  duplicate scan. */
    fun incrementQuantity(id: String, onUpdated: (PantryItem) -> Unit = {}) {
        coroutineScope.launch {
            val item = repository.getItem(id) ?: return@launch
            val updated = item.copy(quantity = item.quantity + 1)
            repository.saveItem(updated)
            onUpdated(updated)
        }
        dismissDuplicate()
    }

    /** Publishes the result of classifying a captured product photo. Platform code does the
     *  classification itself (it needs the device's ML runtime) and hands the filtered
     *  suggestions here; see `FoodLabels.suggestionsFrom`. */
    fun onPhotoLabelsDetected(suggestions: List<FoodLabelSuggestion>) {
        _uiState.update { it.copy(photoLabelSuggestions = suggestions, isLabelingPhoto = false) }
    }

    fun onPhotoLabelingStarted() {
        _uiState.update { it.copy(photoLabelSuggestions = emptyList(), isLabelingPhoto = true) }
    }

    fun onDateOcrDetected(date: LocalDate) {
        _uiState.update {
            it.copy(
                detectedDate = date,
                // A date read off the packaging outranks any remembered/category guess already
                // published by an earlier barcode scan in the same session, so replace it
                // rather than letting the stale suggestion win at pre-fill time.
                suggestedExpiry = ShelfLife.suggest(today = DateUtils.today(), detectedDate = date),
                scanMessage = "Detected Expiry Date: $date"
            )
        }
    }

    fun saveItem(item: PantryItem) {
        // A freehand-typed category (rather than one carried over untouched from a scan) may
        // match an existing category's renamed display name (e.g. typing "test" when origin
        // "test2" was already renamed to display as "test") - reuse that origin instead of
        // fragmenting into a second, duplicate category. See resolveCategoryOrigin.
        val resolvedItem = item.copy(
            category = resolveCategoryOrigin(item.category, _uiState.value.categoryPreferences)
        )
        coroutineScope.launch {
            val isNewItem = repository.getItem(resolvedItem.id) == null
            repository.saveItem(resolvedItem)
            if (isNewItem) rememberStorageLocation(resolvedItem.storageLocation)
            recordProductName(resolvedItem.name)
            resolvedItem.barcode?.let { barcode ->
                localProductRepository.saveProduct(
                    ProductInfo(
                        barcode = barcode,
                        name = resolvedItem.name,
                        brand = resolvedItem.brand,
                        imageUrl = resolvedItem.imageUrl,
                        category = resolvedItem.category
                    )
                )
                // Must run after saveProduct: recordShelfLifeDays deliberately only updates an
                // existing cache row (so it can't clobber the cached name/brand/image), and
                // saveProduct is what guarantees that row exists.
                if (isNewItem) rememberShelfLife(barcode, resolvedItem)
            }
            if (isNewItem) {
                val scanEntryId = _uiState.value.scanningShoppingListEntryId
                if (scanEntryId != null) {
                    shoppingListRepository.deleteEntry(scanEntryId)
                } else if (resolvedItem.barcode != null) {
                    shoppingListRepository.findByBarcode(resolvedItem.barcode)?.let { match ->
                        shoppingListRepository.deleteEntry(match.id)
                    }
                }
            }
            clearScanState()
        }
    }

    /** A plain delete: removes the item outright and never touches the shopping list, even for
     *  an essential item — only [decrementQuantity] running an item down to zero does that.
     *  [onItemRemoved] receives the deleted item's last known state, so callers can either
     *  mirror side effects onto it (e.g. cancelling its scheduled notification) or hold onto
     *  it to offer an undo (see [restoreItem]). */
    fun deleteItem(id: String, onItemRemoved: (PantryItem) -> Unit = {}) {
        coroutineScope.launch {
            val item = repository.getItem(id)
            repository.deleteItem(id)
            if (item != null) {
                onItemRemoved(item)
            }
        }
    }

    /** Decrements one unit of an item; once the last unit is used it is removed the same way
     *  [deleteItem] removes a whole item, except an essential item is also re-added to the
     *  shopping list here (a plain [deleteItem] never does that).
     *  [onItemRemoved] fires only when that full removal happens, receiving the item's state
     *  just before removal (quantity 1), so callers that mirror side effects onto a removed
     *  item (e.g. cancelling its scheduled notification) know not to do so for a plain
     *  decrement that leaves the item in place, and can offer an undo (see [restoreItem]). */
    fun decrementQuantity(id: String, onItemRemoved: (PantryItem) -> Unit = {}) {
        coroutineScope.launch {
            val item = repository.getItem(id) ?: return@launch
            if (item.quantity <= 1) {
                repository.deleteItem(id)
                onItemRemoved(item)
                if (item.essential) {
                    addToShoppingListIfAbsent(item.name, item.barcode, item.category)
                }
            } else {
                repository.saveItem(item.copy(quantity = item.quantity - 1))
            }
        }
    }

    /**
     * Pushes [id]'s expiry date out by [days], for the notification's "Still good" action.
     *
     * Counted from the item's *current* expiry rather than from today, so tapping it on an item
     * that expired three days ago doesn't quietly hand it a fresh week - the point is a small
     * correction to a printed date by someone looking at the actual food, not a reset.
     *
     * [onUpdated] receives the saved item so the caller can reschedule its reminder; the
     * notification that prompted this has already fired, and without a reschedule the item
     * would never warn again.
     */
    fun extendExpiry(id: String, days: Int, onUpdated: (PantryItem) -> Unit = {}) {
        if (days <= 0) return
        coroutineScope.launch {
            val item = repository.getItem(id) ?: return@launch
            val extended = item.copy(
                expiryDate = LocalDate.fromEpochDays(item.expiryDate.toEpochDays() + days)
            )
            repository.saveItem(extended)
            onUpdated(extended)
        }
    }

    /** Explicit "move to shopping list" action (the Home screen's leading/right swipe action):
     *  removes the item from the pantry and adds it to the shopping list unconditionally,
     *  regardless of its [PantryItem.essential] flag — unlike [decrementQuantity], this is a
     *  deliberate user choice rather than an automatic essential-item safeguard.
     *  [onItemRemoved] receives the removed item's last known state, so callers can offer an
     *  undo (see [restoreItem], passing removedFromShoppingList = true). */
    fun moveToShoppingList(id: String, onItemRemoved: (PantryItem) -> Unit = {}) {
        coroutineScope.launch {
            val item = repository.getItem(id)
            repository.deleteItem(id)
            if (item != null) {
                addToShoppingListIfAbsent(item.name, item.barcode, item.category)
                onItemRemoved(item)
            }
        }
    }

    /** Reverses a [deleteItem]/[decrementQuantity] removal by putting [item] straight back
     *  (its id is preserved, so this is an upsert onto the same row rather than a new item).
     *  Pass [removedFromShoppingList] = true only when reversing a [decrementQuantity] full
     *  removal that added a shopping-list entry for it (i.e. the removed item was essential),
     *  so that entry is undone too; a plain [deleteItem] never added one, so leave it false
     *  there. */
    fun restoreItem(item: PantryItem, removedFromShoppingList: Boolean = false) {
        coroutineScope.launch {
            repository.saveItem(item)
            if (removedFromShoppingList) {
                val entry = if (item.barcode != null) {
                    shoppingListRepository.findByBarcode(item.barcode)
                } else {
                    shoppingListRepository.findByName(item.name)
                }
                entry?.let { shoppingListRepository.deleteEntry(it.id) }
            }
        }
    }

    fun addManualShoppingListEntry(productName: String) {
        val trimmed = productName.trim()
        if (trimmed.isBlank()) return
        coroutineScope.launch {
            addToShoppingListIfAbsent(trimmed, barcode = null, category = null)
            recordProductName(trimmed)
        }
    }

    /**
     * Adds every entry in [raw] (a QR payload or pasted text - see [ShoppingListShare.parse])
     * that isn't on the list already, and reports how many were added via [onComplete].
     *
     * Merging rather than replacing: an import is someone else's copy of the list, not
     * necessarily a newer one, so silently dropping what's already here would lose whatever the
     * receiving user added since. Deduping goes through the same
     * [addToShoppingListIfAbsent] every other path uses, so an imported name that is already
     * present - however it got there - is skipped rather than duplicated.
     *
     * Each imported name is also recorded for the autosuggest, so a list received from someone
     * else teaches this device the same names typing them by hand would.
     */
    fun importShoppingList(raw: String, onComplete: (added: Int, found: Int) -> Unit = { _, _ -> }) {
        val parsed = ShoppingListShare.parse(raw)
        if (parsed.isEmpty()) {
            onComplete(0, 0)
            return
        }
        coroutineScope.launch {
            var added = 0
            parsed.forEach { entry ->
                if (addImportedEntry(entry)) added++
            }
            onComplete(added, parsed.size)
        }
    }

    /** Returns true when the entry was actually added (i.e. it wasn't already on the list). */
    private suspend fun addImportedEntry(entry: ImportedShoppingEntry): Boolean {
        val existing = shoppingListRepository.findByName(entry.productName)
        if (existing != null) return false
        addToShoppingListIfAbsent(entry.productName, barcode = null, category = entry.category)
        recordProductName(entry.productName)
        // Quantity is applied after the fact: addToShoppingListIfAbsent is the shared dedupe
        // path and always creates a single unit, and teaching it about quantities would change
        // behaviour for every other caller.
        if (entry.quantity > 1) {
            shoppingListRepository.findByName(entry.productName)?.let { created ->
                shoppingListRepository.updateEntry(created.copy(quantity = entry.quantity))
            }
        }
        return true
    }

    /** Marks [id] as the shopping list entry the in-progress scan flow is completing, so
     *  [saveItem] removes it by id instead of relying on a barcode match. */
    fun beginScanForShoppingListEntry(id: String) {
        _uiState.update { it.copy(scanningShoppingListEntryId = id) }
    }

    /** [onItemRemoved] receives the deleted entry's last known state, so callers can hold onto
     *  it to offer an undo (see [restoreShoppingListEntry]). */
    fun deleteShoppingListEntry(id: String, onItemRemoved: (ShoppingListEntry) -> Unit = {}) {
        val entry = _uiState.value.shoppingList.find { it.id == id }
        coroutineScope.launch {
            shoppingListRepository.deleteEntry(id)
        }
        entry?.let(onItemRemoved)
    }

    fun incrementShoppingListQuantity(id: String) {
        val entry = _uiState.value.shoppingList.find { it.id == id } ?: return
        coroutineScope.launch {
            shoppingListRepository.updateEntry(entry.copy(quantity = entry.quantity + 1))
        }
    }

    /** Decrements one unit of a shopping list entry's needed quantity; once the last unit is
     *  decremented the whole entry is removed the same way [deleteShoppingListEntry] does.
     *  [onItemRemoved] fires only when that full removal happens, receiving the entry's state
     *  just before removal (quantity 1), so callers can offer an undo (see
     *  [restoreShoppingListEntry]). */
    fun decrementShoppingListQuantity(id: String, onItemRemoved: (ShoppingListEntry) -> Unit = {}) {
        val entry = _uiState.value.shoppingList.find { it.id == id } ?: return
        if (entry.quantity <= 1) {
            coroutineScope.launch {
                shoppingListRepository.deleteEntry(id)
            }
            onItemRemoved(entry)
        } else {
            coroutineScope.launch {
                shoppingListRepository.updateEntry(entry.copy(quantity = entry.quantity - 1))
            }
        }
    }

    /** Reverses a [deleteShoppingListEntry]/[decrementShoppingListQuantity] removal by putting
     *  [entry] straight back onto the shopping list (its id is preserved, so this is an upsert
     *  onto the same row rather than a new entry). */
    fun restoreShoppingListEntry(entry: ShoppingListEntry) {
        coroutineScope.launch {
            shoppingListRepository.addEntry(entry)
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun addToShoppingListIfAbsent(name: String, barcode: String?, category: String?) {
        val existing = if (barcode != null) {
            shoppingListRepository.findByBarcode(barcode)
        } else {
            shoppingListRepository.findByName(name)
        }
        if (existing != null) return

        // Restore this product's last manually-dragged position if it has one, so a re-added
        // item (e.g. an essential pantry item running out again) lands back where the user put
        // it rather than always appending to the end.
        val sortOrder = productNameRepository.getSortOrder(name)
            ?: ((shoppingListRepository.getMaxSortOrder() ?: 0.0) + 1.0)

        shoppingListRepository.addEntry(
            ShoppingListEntry(
                id = "shopping_${Clock.System.now().toEpochMilliseconds()}_${Random.nextInt()}",
                productName = name,
                barcode = barcode,
                category = category,
                addedDate = DateUtils.today(),
                sortOrder = sortOrder
            )
        )
    }

    /** Called after a drag-and-drop reorder completes. [beforeId]/[afterId] are the ids of the
     *  entries now immediately above/below [movedId] within the same category group (null at a
     *  group boundary). Computes a fractional sort order between them and persists it both on
     *  the moved entry and on its product name, so the position is remembered the next time this
     *  product re-enters the shopping list (see [addToShoppingListIfAbsent]). */
    fun moveShoppingListEntry(movedId: String, beforeId: String?, afterId: String?) {
        val list = _uiState.value.shoppingList
        val moved = list.find { it.id == movedId } ?: return
        val beforeOrder = beforeId?.let { id -> list.find { it.id == id }?.sortOrder }
        val afterOrder = afterId?.let { id -> list.find { it.id == id }?.sortOrder }
        val newSortOrder = when {
            beforeOrder != null && afterOrder != null -> (beforeOrder + afterOrder) / 2.0
            beforeOrder != null -> beforeOrder + 1.0
            afterOrder != null -> afterOrder - 1.0
            else -> moved.sortOrder
        }
        coroutineScope.launch {
            shoppingListRepository.updateEntry(moved.copy(sortOrder = newSortOrder))
            productNameRepository.recordSortOrder(moved.productName, newSortOrder)
        }
    }

    /** Renames the category identified by [originName] (its raw, scanned value - see
     *  [CategoryPreference.originName]) to [newDisplayName]. Applies retroactively everywhere
     *  that category is shown, without touching the stored `category` field on any product,
     *  since display always resolves through [CategoryPreference] (see
     *  [resolveCategoryDisplayName]). */
    fun renameCategory(originName: String, newDisplayName: String) {
        val trimmed = newDisplayName.trim()
        if (trimmed.isBlank()) return
        val prefs = _uiState.value.categoryPreferences
        val current = prefs.find { it.originName == originName } ?: return
        
        // If we're renaming to an existing display name, sync the enabled state and sort order
        // to effectively "merge" them in the UI.
        val existing = prefs.find { it.displayName.equals(trimmed, ignoreCase = true) }
        
        coroutineScope.launch {
            categoryPreferenceRepository.updateEntry(
                current.copy(
                    displayName = trimmed,
                    enabled = existing?.enabled ?: current.enabled,
                    sortOrder = existing?.sortOrder ?: current.sortOrder
                )
            )
        }
    }

    /** Enables or disables the category identified by [originName]. A disabled category is
     *  hidden everywhere (filter chips, item badges, shopping list group headers) - products
     *  that have it simply show as uncategorized, per [resolveCategoryDisplayName]. */
    fun setCategoryEnabled(originName: String, enabled: Boolean) {
        val prefs = _uiState.value.categoryPreferences
        val target = prefs.find { it.originName == originName } ?: return
        coroutineScope.launch {
            // Sync all origins sharing this display name so they enable/disable as a unit, in a
            // single batched write — one write per origin means one flow re-emission per origin,
            // each re-running the whole downstream derivation.
            categoryPreferenceRepository.updateEntries(
                prefs.filter { it.displayName == target.displayName }
                    .map { it.copy(enabled = enabled) }
            )
        }
    }

    /** Sets the shelf life, in days, pre-filled on a new item in the category identified by
     *  [originName] when its barcode has no remembered shelf life of its own. Null clears the
     *  default. Like [setCategoryEnabled], every origin sharing this category's display name is
     *  updated as a unit and in one batched write, since merged categories are a single row to
     *  the user. */
    fun setCategoryDefaultShelfLife(originName: String, days: Int?) {
        val prefs = _uiState.value.categoryPreferences
        val target = prefs.find { it.originName == originName } ?: return
        val sanitized = days?.coerceIn(0, ShelfLife.MAX_LEARNABLE_DAYS)
        coroutineScope.launch {
            categoryPreferenceRepository.updateEntries(
                prefs.filter { it.displayName == target.displayName && it.defaultShelfLifeDays != sanitized }
                    .map { it.copy(defaultShelfLifeDays = sanitized) }
            )
        }
    }

    /** Bulk on/off switch for every category at once, so the user isn't forced to flip each
     *  [setCategoryEnabled] toggle one by one. */
    fun setAllCategoriesEnabled(enabled: Boolean) {
        val all = _uiState.value.categoryPreferences
        coroutineScope.launch {
            // One batched write rather than one per category: this is the worst case for the
            // per-row version, since it can touch every preference at once.
            categoryPreferenceRepository.updateEntries(
                all.filter { it.enabled != enabled }.map { it.copy(enabled = enabled) }
            )
        }
    }

    /**
     * Called after a drag-and-drop reorder of categories in Settings completes.
     * [orderedOrigins] is the origin name of one row per visible category (see
     * [PantryDerivation.buildCategoryRows]), in the order the user now sees them; the whole list
     * is renumbered to match, in one batched write.
     *
     * Deliberately *not* [moveShoppingListEntry]'s scheme of giving the moved row a fractional
     * order between its two neighbours. The "Other" bucket is registered at [Double.MAX_VALUE] so
     * it always sorts last (see `init`), and `MAX_VALUE + 1.0` is still `MAX_VALUE` — so dropping
     * a row below "Other" persisted the sort order it already had. Nothing changed on disk, and
     * the row snapped back to its old place (the `sortOrder`-tied fallback ordering by display
     * name) as soon as the screen re-read the preferences. Renumbering has no such degenerate
     * case, and it also repairs any ties already stored.
     */
    fun reorderCategoryPreferences(orderedOrigins: List<String>) {
        val list = _uiState.value.categoryPreferences
        // Rows are grouped by display name, so every origin merged onto one row moves as a unit.
        val orderByDisplayName = HashMap<String, Double>(orderedOrigins.size)
        orderedOrigins.forEachIndexed { index, origin ->
            val displayName = list.find { it.originName == origin }?.displayName ?: return@forEachIndexed
            orderByDisplayName[displayName] = index.toDouble()
        }
        val updated = list.mapNotNull { preference ->
            val newSortOrder = orderByDisplayName[preference.displayName] ?: return@mapNotNull null
            if (preference.sortOrder == newSortOrder) null else preference.copy(sortOrder = newSortOrder)
        }
        if (updated.isEmpty()) return
        coroutineScope.launch { categoryPreferenceRepository.updateEntries(updated) }
    }

    /** Records [location] as the one to pre-select for the next new item (see
     *  [AppSettings.storageLocationForNewItem]). Only new items count: editing an old item's
     *  location says nothing about where the next scan is going. Skipped while the feature is
     *  off so the stored value doesn't silently change underneath a user who disabled it. */
    private fun rememberStorageLocation(location: String) {
        val settings = _uiState.value.settings
        if (settings.rememberLastStorageLocation && settings.lastUsedStorageLocation != location) {
            updateSettings(settings.copy(lastUsedStorageLocation = location))
        }
    }

    /** Learns how long [item]'s product keeps, so a future scan of the same barcode pre-fills
     *  its expiry date (see [suggestExpiry]). Only new items teach the cache: editing an old
     *  item's date says nothing about how long a *fresh* one lasts, which is the same rule
     *  [rememberStorageLocation] follows. An implausible span is skipped rather than stored -
     *  see [ShelfLife.learnableShelfLife]. */
    private suspend fun rememberShelfLife(barcode: String, item: PantryItem) {
        val days = ShelfLife.learnableShelfLife(item.addedDate, item.expiryDate) ?: return
        localProductRepository.recordShelfLifeDays(barcode, days)
    }

    /** Records [name] as a known product name for the shopping list's autosuggest.
     *  Persisted separately from shopping list entries themselves (see [ProductNameRepository])
     *  so the suggestion survives an entry being checked off or deleted. */
    private suspend fun recordProductName(name: String) {
        productNameRepository.recordName(name)
    }

    /**
     * Drops the result of the last single-item scan, without touching the two things that
     * outlive one scan: the batch queue, and the shopping-list entry this scan is filling in.
     *
     * This is what switching scanner mode calls. A product card, an OCR'd date or a photo
     * belongs to the mode that produced it, and leaving it published while the camera is doing
     * something else is how a stale result ends up on screen under a mode that cannot have
     * produced it. [clearScanState] is the stronger version, for leaving the scanner entirely.
     */
    fun clearScanResults() {
        barcodeLookupJob?.cancel()
        duplicateCheckJob?.cancel()
        _uiState.update {
            it.copy(
                scannedProduct = null,
                productNotFoundBarcode = null,
                detectedDate = null,
                suggestedExpiry = null,
                photoLabelSuggestions = emptyList(),
                isLabelingPhoto = false,
                duplicateOf = null,
                allergenWarnings = emptyList(),
                isLookingUpBarcode = false,
                scanMessage = null
            )
        }
    }

    /**
     * Drops the last scan's result *and* the shopping-list entry it was filling in, for when
     * that scan is finished with - saving the item it produced, or leaving the scanner.
     *
     * Deliberately not the batch queue: [saveItem] calls this, and [commitBatchQueue] saves
     * each row through [saveItem], so emptying the queue here would have it clear itself out
     * from under its own loop. The queue is discarded by [clearBatchQueue], which the scanner
     * calls when the user confirms leaving a queue uncommitted.
     */
    fun clearScanState() {
        clearScanResults()
        _uiState.update { it.copy(scanningShoppingListEntryId = null) }
    }
}
