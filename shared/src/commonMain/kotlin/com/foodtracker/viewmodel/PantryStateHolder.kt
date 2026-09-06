package com.foodtracker.viewmodel

import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.model.AppSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.SortOption
import com.foodtracker.parser.GS1BarcodeParser
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.ProductNameRepository
import com.foodtracker.repository.ShoppingListRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

enum class StatusFilter {
    ALL,
    EXPIRING_SOON,
    EXPIRED,
    FRESH
}

data class PantryUiState(
    val items: List<PantryItem> = emptyList(),
    val filteredItems: List<PantryItem> = emptyList(),
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
    val isLookingUpBarcode: Boolean = false,
    val scanMessage: String? = null,
    /** Set while the scan flow was launched from a specific shopping list row (rather than
     *  the dashboard's general scan action), so [saveItem] knows to remove that exact entry
     *  even if it has no barcode of its own to match against (manually-added entries). */
    val scanningShoppingListEntryId: String? = null,
    // Shopping list
    val shoppingList: List<ShoppingListEntry> = emptyList(),
    val knownProductNames: List<String> = emptyList()
)

class PantryStateHolder(
    private val repository: PantryRepository,
    private val settingsRepository: SettingsRepository,
    private val shoppingListRepository: ShoppingListRepository,
    private val productNameRepository: ProductNameRepository,
    private val openFoodFactsApi: OpenFoodFactsApi = OpenFoodFactsApi(),
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _uiState = MutableStateFlow(PantryUiState(settings = settingsRepository.getSettings()))
    val uiState: StateFlow<PantryUiState> = _uiState.asStateFlow()

    init {
        observeItems()
        observeShoppingList()
        observeKnownProductNames()
    }

    private fun observeItems() {
        coroutineScope.launch {
            repository.getItemsFlow().collectLatest { allItems ->
                _uiState.update { current ->
                    val today = DateUtils.today()
                    val expiringSoonDays = current.settings.expiringSoonDays

                    val expiredCount = allItems.count { it.daysRemaining(today) < 0 }
                    val expiringSoonCount = allItems.count { it.daysRemaining(today) in 0..expiringSoonDays }
                    val freshCount = allItems.count { it.daysRemaining(today) > expiringSoonDays }

                    val filtered = computeFiltered(
                        allItems,
                        current.selectedLocation,
                        current.selectedCategory,
                        current.selectedStatusFilter,
                        current.searchQuery,
                        today,
                        expiringSoonDays,
                        current.settings.sortOption
                    )

                    current.copy(
                        items = allItems,
                        filteredItems = filtered,
                        isLoading = false,
                        expiredCount = expiredCount,
                        expiringSoonCount = expiringSoonCount,
                        freshCount = freshCount
                    )
                }
            }
        }
    }

    private fun observeShoppingList() {
        coroutineScope.launch {
            shoppingListRepository.getEntriesFlow().collectLatest { entries ->
                _uiState.update { it.copy(shoppingList = entries) }
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

    private fun computeFiltered(
        items: List<PantryItem>,
        location: String?,
        category: String?,
        filter: StatusFilter,
        query: String,
        today: LocalDate,
        expiringSoonDays: Int,
        sortOption: SortOption
    ): List<PantryItem> {
        return items.filter { item ->
            // Location filter
            val matchesLoc = location == null || item.storageLocation == location

            // Category filter
            val matchesCategory = category == null || item.category == category

            // Status filter
            val matchesStatus = when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.EXPIRING_SOON -> item.daysRemaining(today) in 0..expiringSoonDays
                StatusFilter.EXPIRED -> item.daysRemaining(today) < 0
                StatusFilter.FRESH -> item.daysRemaining(today) > expiringSoonDays
            }

            // Search query
            val matchesQuery = query.isBlank() ||
                    item.name.contains(query, ignoreCase = true) ||
                    (item.brand?.contains(query, ignoreCase = true) == true)

            matchesLoc && matchesCategory && matchesStatus && matchesQuery
        }.sortedWith(sortComparator(sortOption))
    }

    private fun sortComparator(option: SortOption): Comparator<PantryItem> = when (option) {
        SortOption.EXPIRY_DATE -> compareBy { it.expiryDate.toEpochDays() }
        SortOption.NAME -> compareBy { it.name.lowercase() }
        SortOption.DATE_ADDED -> compareByDescending { it.addedDate.toEpochDays() }
        SortOption.STORAGE_LOCATION -> compareBy { it.storageLocation.lowercase() }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { current ->
            val today = DateUtils.today()
            current.copy(
                searchQuery = query,
                filteredItems = computeFiltered(
                    current.items,
                    current.selectedLocation,
                    current.selectedCategory,
                    current.selectedStatusFilter,
                    query,
                    today,
                    current.settings.expiringSoonDays,
                    current.settings.sortOption
                )
            )
        }
    }

    fun onLocationSelected(location: String?) {
        _uiState.update { current ->
            val today = DateUtils.today()
            current.copy(
                selectedLocation = location,
                filteredItems = computeFiltered(
                    current.items,
                    location,
                    current.selectedCategory,
                    current.selectedStatusFilter,
                    current.searchQuery,
                    today,
                    current.settings.expiringSoonDays,
                    current.settings.sortOption
                )
            )
        }
    }

    fun onCategorySelected(category: String?) {
        _uiState.update { current ->
            val today = DateUtils.today()
            current.copy(
                selectedCategory = category,
                filteredItems = computeFiltered(
                    current.items,
                    current.selectedLocation,
                    category,
                    current.selectedStatusFilter,
                    current.searchQuery,
                    today,
                    current.settings.expiringSoonDays,
                    current.settings.sortOption
                )
            )
        }
    }

    fun onStatusFilterSelected(filter: StatusFilter) {
        _uiState.update { current ->
            val today = DateUtils.today()
            current.copy(
                selectedStatusFilter = filter,
                filteredItems = computeFiltered(
                    current.items,
                    current.selectedLocation,
                    current.selectedCategory,
                    filter,
                    current.searchQuery,
                    today,
                    current.settings.expiringSoonDays,
                    current.settings.sortOption
                )
            )
        }
    }

    fun onSortOptionSelected(option: SortOption) {
        updateSettings(_uiState.value.settings.copy(sortOption = option))
    }

    fun updateSettings(newSettings: AppSettings) {
        settingsRepository.saveSettings(newSettings)
        _uiState.update { current ->
            val today = DateUtils.today()
            val expiringSoonDays = newSettings.expiringSoonDays

            current.copy(
                settings = newSettings,
                expiredCount = current.items.count { it.daysRemaining(today) < 0 },
                expiringSoonCount = current.items.count { it.daysRemaining(today) in 0..expiringSoonDays },
                freshCount = current.items.count { it.daysRemaining(today) > expiringSoonDays },
                filteredItems = computeFiltered(
                    current.items,
                    current.selectedLocation,
                    current.selectedCategory,
                    current.selectedStatusFilter,
                    current.searchQuery,
                    today,
                    expiringSoonDays,
                    newSettings.sortOption
                )
            )
        }
    }

    fun clearAllData() {
        coroutineScope.launch {
            repository.clearAll()
        }
    }

    fun onBarcodeScanned(barcode: String) {
        val gs1Date = GS1BarcodeParser.extractExpiryDate(barcode)
        _uiState.update {
            it.copy(
                isLookingUpBarcode = true,
                detectedDate = gs1Date ?: it.detectedDate,
                scanMessage = if (gs1Date != null) "Extracted date from barcode: $gs1Date" else null
            )
        }

        coroutineScope.launch {
            val product = openFoodFactsApi.fetchProduct(barcode)
            _uiState.update {
                it.copy(
                    scannedProduct = product,
                    productNotFoundBarcode = if (product == null) barcode else null,
                    isLookingUpBarcode = false,
                    scanMessage = if (product != null) "Found ${product.name}" else "Product not in database - enter name"
                )
            }
        }
    }

    fun onDateOcrDetected(date: LocalDate) {
        _uiState.update {
            it.copy(
                detectedDate = date,
                scanMessage = "Detected Expiry Date: $date"
            )
        }
    }

    fun saveItem(item: PantryItem) {
        coroutineScope.launch {
            val isNewItem = repository.getItem(item.id) == null
            repository.saveItem(item)
            recordProductName(item.name)
            if (isNewItem) {
                val scanEntryId = _uiState.value.scanningShoppingListEntryId
                if (scanEntryId != null) {
                    shoppingListRepository.deleteEntry(scanEntryId)
                } else if (item.barcode != null) {
                    shoppingListRepository.findByBarcode(item.barcode)?.let { match ->
                        shoppingListRepository.deleteEntry(match.id)
                    }
                }
            }
            clearScanState()
        }
    }

    /** [onItemRemoved] receives the deleted item's last known state, so callers can either
     *  mirror side effects onto it (e.g. cancelling its scheduled notification) or hold onto
     *  it to offer an undo (see [restoreItem]). */
    fun deleteItem(id: String, onItemRemoved: (PantryItem) -> Unit = {}) {
        coroutineScope.launch {
            val item = repository.getItem(id)
            repository.deleteItem(id)
            if (item != null) {
                onItemRemoved(item)
                if (item.essential) {
                    addToShoppingListIfAbsent(item.name, item.barcode, item.category)
                }
            }
        }
    }

    /** Decrements one unit of an item; once the last unit is used it is removed the same way
     *  [deleteItem] removes a whole item (essential items get re-added to the shopping list).
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

    /** Reverses a [deleteItem]/[decrementQuantity] removal by putting [item] straight back
     *  (its id is preserved, so this is an upsert onto the same row rather than a new item).
     *  If the removal had re-added it to the shopping list as an essential item, that entry
     *  is removed again so the item isn't listed as both in the pantry and needed. */
    fun restoreItem(item: PantryItem) {
        coroutineScope.launch {
            repository.saveItem(item)
            if (item.essential) {
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

    /** Marks [id] as the shopping list entry the in-progress scan flow is completing, so
     *  [saveItem] removes it by id instead of relying on a barcode match. */
    fun beginScanForShoppingListEntry(id: String) {
        _uiState.update { it.copy(scanningShoppingListEntryId = id) }
    }

    fun deleteShoppingListEntry(id: String) {
        coroutineScope.launch {
            shoppingListRepository.deleteEntry(id)
        }
    }

    fun incrementShoppingListQuantity(id: String) {
        val entry = _uiState.value.shoppingList.find { it.id == id } ?: return
        coroutineScope.launch {
            shoppingListRepository.updateEntry(entry.copy(quantity = entry.quantity + 1))
        }
    }

    fun decrementShoppingListQuantity(id: String) {
        val entry = _uiState.value.shoppingList.find { it.id == id } ?: return
        if (entry.quantity <= 1) return
        coroutineScope.launch {
            shoppingListRepository.updateEntry(entry.copy(quantity = entry.quantity - 1))
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

    /** Records [name] as a known product name for the shopping list's autosuggest.
     *  Persisted separately from shopping list entries themselves (see [ProductNameRepository])
     *  so the suggestion survives an entry being checked off or deleted. */
    private suspend fun recordProductName(name: String) {
        productNameRepository.recordName(name)
    }

    fun clearScanState() {
        _uiState.update {
            it.copy(
                scannedProduct = null,
                productNotFoundBarcode = null,
                detectedDate = null,
                isLookingUpBarcode = false,
                scanMessage = null,
                scanningShoppingListEntryId = null
            )
        }
    }
}
