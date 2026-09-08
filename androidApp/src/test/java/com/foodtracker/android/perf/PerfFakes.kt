package com.foodtracker.android.perf

import com.foodtracker.model.AppSettings
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.model.UNCATEGORIZED_ORIGIN
import com.foodtracker.repository.CategoryPreferenceRepository
import com.foodtracker.repository.LocalProductRepository
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.ProductNameRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.repository.ShoppingListRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory repository fakes for the JVM performance tests.
 *
 * These are deliberately free of I/O and of artificial delays: the point of the measurement is
 * the state holder's own filtering/sorting/derivation cost, so anything the fakes contribute
 * would be noise added equally to the "before" and "after" numbers while shrinking the relative
 * difference being measured.
 */
class FakePantryRepository(items: List<PantryItem> = emptyList()) : PantryRepository {
    val itemsFlow = MutableStateFlow(items)
    override fun getItemsFlow(): Flow<List<PantryItem>> = itemsFlow
    override suspend fun getItem(id: String): PantryItem? = itemsFlow.value.find { it.id == id }
    override suspend fun saveItem(item: PantryItem) {
        itemsFlow.value = itemsFlow.value.filterNot { it.id == item.id } + item
    }
    override suspend fun deleteItem(id: String) {
        itemsFlow.value = itemsFlow.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        itemsFlow.value = emptyList()
    }
}

class FakeSettingsRepository(private var settings: AppSettings = AppSettings()) : SettingsRepository {
    var saveCount: Int = 0
        private set
    override fun getSettings(): AppSettings = settings
    override fun saveSettings(settings: AppSettings) {
        saveCount++
        this.settings = settings
    }
}

class FakeShoppingListRepository(entries: List<ShoppingListEntry> = emptyList()) : ShoppingListRepository {
    val entriesFlow = MutableStateFlow(entries)
    override fun getEntriesFlow(): Flow<List<ShoppingListEntry>> = entriesFlow
    override suspend fun findByBarcode(barcode: String): ShoppingListEntry? =
        entriesFlow.value.find { it.barcode == barcode }
    override suspend fun findByName(name: String): ShoppingListEntry? =
        entriesFlow.value.find { it.productName.equals(name, ignoreCase = true) }
    override suspend fun getMaxSortOrder(): Double? = entriesFlow.value.maxOfOrNull { it.sortOrder }
    override suspend fun addEntry(entry: ShoppingListEntry) {
        entriesFlow.value = entriesFlow.value + entry
    }
    override suspend fun updateEntry(entry: ShoppingListEntry) {
        entriesFlow.value = entriesFlow.value.map { if (it.id == entry.id) entry else it }
    }
    override suspend fun deleteEntry(id: String) {
        entriesFlow.value = entriesFlow.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        entriesFlow.value = emptyList()
    }
}

class FakeProductNameRepository : ProductNameRepository {
    val namesFlow = MutableStateFlow<List<String>>(emptyList())
    private val sortOrders = mutableMapOf<String, Double>()
    override fun getKnownNamesFlow(): Flow<List<String>> = namesFlow
    override suspend fun recordName(name: String) {
        if (name !in namesFlow.value) namesFlow.value = namesFlow.value + name
    }
    override suspend fun getSortOrder(name: String): Double? = sortOrders[name]
    override suspend fun recordSortOrder(name: String, sortOrder: Double) {
        sortOrders[name] = sortOrder
    }
}

class FakeLocalProductRepository : LocalProductRepository {
    private val products = mutableMapOf<String, ProductInfo>()
    override suspend fun getByBarcode(barcode: String): ProductInfo? = products[barcode]
    override suspend fun saveProduct(product: ProductInfo) {
        products[product.barcode] = product
    }
}

class FakeCategoryPreferenceRepository(
    preferences: List<CategoryPreference> = emptyList()
) : CategoryPreferenceRepository {
    val preferencesFlow = MutableStateFlow(preferences)
    override fun getPreferencesFlow(): Flow<List<CategoryPreference>> = preferencesFlow
    // Excludes the uncategorized sentinel, like the Room implementation does — see
    // CategoryPreferenceRepository.getMaxSortOrder.
    override suspend fun getMaxSortOrder(): Double? =
        preferencesFlow.value.filterNot { it.originName == UNCATEGORIZED_ORIGIN }
            .maxOfOrNull { it.sortOrder }
    override suspend fun registerIfAbsent(originName: String, sortOrder: Double, displayName: String) {
        if (preferencesFlow.value.none { it.originName == originName }) {
            preferencesFlow.value = preferencesFlow.value +
                CategoryPreference(originName, displayName, sortOrder)
        }
    }
    override suspend fun updateEntry(preference: CategoryPreference) = updateEntries(listOf(preference))

    override suspend fun updateEntries(preferences: List<CategoryPreference>) {
        if (preferences.isEmpty()) return
        val updates = preferences.associateBy { it.originName }
        preferencesFlow.value = preferencesFlow.value.map { updates[it.originName] ?: it }
    }
}
