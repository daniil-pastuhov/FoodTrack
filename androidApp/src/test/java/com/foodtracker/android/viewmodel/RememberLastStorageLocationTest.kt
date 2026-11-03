package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.model.AppSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Saving a new item records its storage location so the next scan starts out there
 * (see [AppSettings.storageLocationForNewItem]).
 */
class RememberLastStorageLocationTest {

    private class Fixture(settings: AppSettings = AppSettings(), items: List<PantryItem> = emptyList()) {
        val settingsRepository = FakeSettingsRepository(settings)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = FakePantryRepository(items),
            settingsRepository = settingsRepository,
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = FakeLocalProductRepository(),
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        val settings: AppSettings get() = stateHolder.uiState.value.settings

        fun close() = scope.cancel()
    }

    private fun pantryItem(id: String, storageLocation: String) = PantryItem(
        id = id,
        name = id,
        expiryDate = DateUtils.today(),
        addedDate = DateUtils.today(),
        storageLocation = storageLocation
    )

    @Test
    fun testSavingANewItemRemembersItsLocationAndPersistsIt() {
        val fixture = Fixture()
        try {
            fixture.stateHolder.saveItem(pantryItem("milk", "Freezer"))

            assertEquals("Freezer", fixture.settings.lastUsedStorageLocation)
            assertEquals("Freezer", fixture.settings.storageLocationForNewItem())
            assertEquals("Freezer", fixture.settingsRepository.getSettings().lastUsedStorageLocation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testEditingAnExistingItemDoesNotChangeTheRememberedLocation() {
        val existing = pantryItem("milk", "Fridge")
        val fixture = Fixture(
            settings = AppSettings(lastUsedStorageLocation = "Pantry"),
            items = listOf(existing)
        )
        try {
            fixture.stateHolder.saveItem(existing.copy(storageLocation = "Freezer"))

            assertEquals("Pantry", fixture.settings.lastUsedStorageLocation)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testNothingIsRememberedWhileTheFeatureIsOff() {
        val fixture = Fixture(settings = AppSettings(rememberLastStorageLocation = false))
        try {
            fixture.stateHolder.saveItem(pantryItem("milk", "Freezer"))

            assertNull(fixture.settings.lastUsedStorageLocation)
            assertEquals("Pantry", fixture.settings.storageLocationForNewItem())
            assertEquals(0, fixture.settingsRepository.saveCount)
        } finally {
            fixture.close()
        }
    }
}
