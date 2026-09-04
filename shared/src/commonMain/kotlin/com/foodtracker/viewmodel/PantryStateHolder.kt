package com.foodtracker.viewmodel

import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.model.AppSettings
import com.foodtracker.model.ItemStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.parser.GS1BarcodeParser
import com.foodtracker.repository.PantryRepository
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
    val selectedStatusFilter: StatusFilter = StatusFilter.ALL,
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val expiredCount: Int = 0,
    val expiringSoonCount: Int = 0,
    val freshCount: Int = 0,
    val settings: AppSettings = AppSettings(),
    // Scan flow state
    val scannedProduct: ProductInfo? = null,
    val detectedDate: LocalDate? = null,
    val isLookingUpBarcode: Boolean = false,
    val scanMessage: String? = null
)

class PantryStateHolder(
    private val repository: PantryRepository,
    private val settingsRepository: SettingsRepository,
    private val openFoodFactsApi: OpenFoodFactsApi = OpenFoodFactsApi(),
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _uiState = MutableStateFlow(PantryUiState(settings = settingsRepository.getSettings()))
    val uiState: StateFlow<PantryUiState> = _uiState.asStateFlow()

    init {
        observeItems()
    }

    private fun observeItems() {
        coroutineScope.launch {
            repository.getItemsFlow().collectLatest { allItems ->
                _uiState.update { current ->
                    val today = DateUtils.today()
                    val expiringSoonDays = current.settings.expiringSoonDays
                    val activeItems = allItems.filter { it.status == ItemStatus.ACTIVE }

                    val expiredCount = activeItems.count { it.daysRemaining(today) < 0 }
                    val expiringSoonCount = activeItems.count { it.daysRemaining(today) in 0..expiringSoonDays }
                    val freshCount = activeItems.count { it.daysRemaining(today) > expiringSoonDays }

                    val filtered = computeFiltered(
                        allItems,
                        current.selectedLocation,
                        current.selectedStatusFilter,
                        current.searchQuery,
                        today,
                        expiringSoonDays
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

    private fun computeFiltered(
        items: List<PantryItem>,
        location: String?,
        filter: StatusFilter,
        query: String,
        today: LocalDate,
        expiringSoonDays: Int
    ): List<PantryItem> {
        return items.filter { item ->
            // Location filter
            val matchesLoc = location == null || item.storageLocation == location

            // Status filter
            val matchesStatus = when (filter) {
                StatusFilter.ALL -> item.status == ItemStatus.ACTIVE
                StatusFilter.EXPIRING_SOON -> item.status == ItemStatus.ACTIVE && item.daysRemaining(today) in 0..expiringSoonDays
                StatusFilter.EXPIRED -> item.status == ItemStatus.ACTIVE && item.daysRemaining(today) < 0
                StatusFilter.FRESH -> item.status == ItemStatus.ACTIVE && item.daysRemaining(today) > expiringSoonDays
            }

            // Search query
            val matchesQuery = query.isBlank() ||
                    item.name.contains(query, ignoreCase = true) ||
                    (item.brand?.contains(query, ignoreCase = true) == true)

            matchesLoc && matchesStatus && matchesQuery
        }.sortedBy { it.expiryDate.toEpochDays() }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { current ->
            val today = DateUtils.today()
            current.copy(
                searchQuery = query,
                filteredItems = computeFiltered(
                    current.items,
                    current.selectedLocation,
                    current.selectedStatusFilter,
                    query,
                    today,
                    current.settings.expiringSoonDays
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
                    current.selectedStatusFilter,
                    current.searchQuery,
                    today,
                    current.settings.expiringSoonDays
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
                    filter,
                    current.searchQuery,
                    today,
                    current.settings.expiringSoonDays
                )
            )
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        settingsRepository.saveSettings(newSettings)
        _uiState.update { current ->
            val today = DateUtils.today()
            val activeItems = current.items.filter { it.status == ItemStatus.ACTIVE }
            val expiringSoonDays = newSettings.expiringSoonDays

            current.copy(
                settings = newSettings,
                expiredCount = activeItems.count { it.daysRemaining(today) < 0 },
                expiringSoonCount = activeItems.count { it.daysRemaining(today) in 0..expiringSoonDays },
                freshCount = activeItems.count { it.daysRemaining(today) > expiringSoonDays },
                filteredItems = computeFiltered(
                    current.items,
                    current.selectedLocation,
                    current.selectedStatusFilter,
                    current.searchQuery,
                    today,
                    expiringSoonDays
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
                    scannedProduct = product ?: ProductInfo(barcode = barcode, name = "Product $barcode"),
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
            repository.saveItem(item)
            clearScanState()
        }
    }

    fun deleteItem(id: String) {
        coroutineScope.launch {
            repository.deleteItem(id)
        }
    }

    fun clearScanState() {
        _uiState.update {
            it.copy(
                scannedProduct = null,
                detectedDate = null,
                isLookingUpBarcode = false,
                scanMessage = null
            )
        }
    }
}
