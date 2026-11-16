package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.android.perf.FakeStapleRepository
import com.foodtracker.model.Staple
import com.foodtracker.backup.BackupReadResult
import com.foodtracker.backup.RestoreMode
import com.foodtracker.backup.RestoreSummary
import com.foodtracker.model.AppSettings
import com.foodtracker.model.CategoryPreference
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Exporting and restoring the local database. This is the only safety net for device-only data,
 * so the cases that matter are the ones where a restore could silently lose or duplicate
 * something the user still has.
 */
class BackupRestoreTest {

    private val today = DateUtils.today()

    private class Fixture(
        items: List<PantryItem> = emptyList(),
        entries: List<ShoppingListEntry> = emptyList(),
        categories: List<CategoryPreference> = emptyList(),
        settings: AppSettings = AppSettings(),
        staples: List<Staple> = emptyList()
    ) {
        val pantry = FakePantryRepository(items)
        val stapleRepo = FakeStapleRepository(staples)
        val shopping = FakeShoppingListRepository(entries)
        val localProducts = FakeLocalProductRepository()
        val names = FakeProductNameRepository()
        val settingsRepository = FakeSettingsRepository(settings)
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = settingsRepository,
            shoppingListRepository = shopping,
            productNameRepository = names,
            localProductRepository = localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(categories),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            stapleRepository = stapleRepo,
            uncategorizedLabel = "Other",
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        fun exportJson(): String {
            var json: String? = null
            stateHolder.exportBackup { encoded, _ -> json = encoded }
            return requireNotNull(json) { "export did not complete" }
        }

        fun restore(json: String, mode: RestoreMode): Pair<BackupReadResult, RestoreSummary> {
            var result: Pair<BackupReadResult, RestoreSummary>? = null
            stateHolder.restoreBackup(json, mode) { read, summary -> result = read to summary }
            return requireNotNull(result) { "restore did not complete" }
        }

        fun close() = scope.cancel()
    }

    private fun item(id: String, name: String = id, barcode: String? = null) = PantryItem(
        id = id,
        name = name,
        barcode = barcode,
        expiryDate = LocalDate.fromEpochDays(DateUtils.today().toEpochDays() + 7),
        addedDate = DateUtils.today(),
        storageLocation = "Fridge"
    )

    private fun entry(id: String, name: String) = ShoppingListEntry(
        id = id,
        productName = name,
        addedDate = DateUtils.today()
    )

    @Test
    fun testExportCapturesEverythingIncludingWhatTheUiStateNeverHolds() {
        val fixture = Fixture(
            items = listOf(item("a", "Milk")),
            entries = listOf(entry("s1", "Bread")),
            categories = listOf(CategoryPreference("Dairy", "Dairy", defaultShelfLifeDays = 9))
        )
        try {
            // The product cache and known names are not part of PantryUiState, so a backup built
            // from the ui state would quietly forget every learned shelf life.
            fixture.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            fixture.localProducts.recordShelfLife("111", 12)
            fixture.names.namesFlow.value = listOf("Milk", "Bread")

            var captured: com.foodtracker.backup.BackupDocument? = null
            fixture.stateHolder.exportBackup { _, document -> captured = document }
            val document = requireNotNull(captured)

            assertEquals(listOf("a"), document.items.map { it.id })
            assertEquals(listOf("s1"), document.shoppingList.map { it.id })
            assertEquals(12, document.products.single { it.barcode == "111" }.shelfLifeDays)
            // The state holder also auto-registers the uncategorized sentinel, so select by
            // origin rather than assuming this is the only preference.
            assertEquals(9, document.categoryPreferences.single { it.originName == "Dairy" }.defaultShelfLifeDays)
            assertTrue(document.knownProductNames.containsAll(listOf("Milk", "Bread")))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun testRoundTripOntoAnEmptyDeviceRestoresEverything() {
        val source = Fixture(
            items = listOf(item("a", "Milk"), item("b", "Cheese")),
            entries = listOf(entry("s1", "Bread")),
            categories = listOf(CategoryPreference("Dairy", "Meieri"))
        )
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture()
        try {
            val (result, summary) = target.restore(json, RestoreMode.REPLACE)
            assertTrue(result is BackupReadResult.Success)
            assertEquals(2, summary.itemsRestored)
            assertEquals(1, summary.shoppingEntriesRestored)
            assertEquals(setOf("a", "b"), target.pantry.itemsFlow.value.map { it.id }.toSet())
            assertEquals("Meieri", target.stateHolder.uiState.value.categoryPreferences
                .single { it.originName == "Dairy" }.displayName)
        } finally {
            target.close()
        }
    }

    @Test
    fun testMergeDoesNotDuplicateItemsTheDeviceAlreadyHas() {
        // Re-importing the same file twice is a plausible user action; it must not double the
        // pantry.
        val source = Fixture(items = listOf(item("a", "Milk")))
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture(items = listOf(item("a", "Milk")))
        try {
            val (_, summary) = target.restore(json, RestoreMode.MERGE)
            assertEquals(0, summary.itemsRestored)
            assertEquals(1, target.pantry.itemsFlow.value.size)
        } finally {
            target.close()
        }
    }

    @Test
    fun testMergeKeepsLocalItemsThatAreNotInTheBackup() {
        val source = Fixture(items = listOf(item("a", "Milk")))
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture(items = listOf(item("local", "Yoghurt")))
        try {
            target.restore(json, RestoreMode.MERGE)
            assertEquals(setOf("local", "a"), target.pantry.itemsFlow.value.map { it.id }.toSet())
        } finally {
            target.close()
        }
    }

    @Test
    fun testReplaceDropsLocalItemsThatAreNotInTheBackup() {
        val source = Fixture(items = listOf(item("a", "Milk")))
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture(items = listOf(item("local", "Yoghurt")))
        try {
            target.restore(json, RestoreMode.REPLACE)
            assertEquals(listOf("a"), target.pantry.itemsFlow.value.map { it.id })
        } finally {
            target.close()
        }
    }

    @Test
    fun testMergeLeavesTheDevicesOwnSettingsAlone() {
        // Merge is the safe mode; silently adopting a foreign device's notification time and
        // storage locations would be the opposite of safe.
        val source = Fixture(settings = AppSettings(expiringSoonDays = 9))
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture(settings = AppSettings(expiringSoonDays = 2))
        try {
            val (_, summary) = target.restore(json, RestoreMode.MERGE)
            assertEquals(2, target.stateHolder.uiState.value.settings.expiringSoonDays)
            assertEquals(false, summary.settingsRestored)
        } finally {
            target.close()
        }
    }

    @Test
    fun testReplaceRestoresTheBackupsSettings() {
        val source = Fixture(settings = AppSettings(expiringSoonDays = 9))
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture(settings = AppSettings(expiringSoonDays = 2))
        try {
            val (_, summary) = target.restore(json, RestoreMode.REPLACE)
            assertEquals(9, target.stateHolder.uiState.value.settings.expiringSoonDays)
            assertTrue(summary.settingsRestored)
        } finally {
            target.close()
        }
    }

    @Test
    fun testLearnedShelfLivesSurviveARestore() {
        val source = Fixture(items = listOf(item("a", "Milk", barcode = "111")))
        val json = try {
            source.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            source.localProducts.recordShelfLife("111", 12)
            source.exportJson()
        } finally {
            source.close()
        }

        val target = Fixture()
        try {
            target.restore(json, RestoreMode.REPLACE)
            assertEquals(12, target.localProducts.shelfLives["111"])
        } finally {
            target.close()
        }
    }

    @Test
    fun testRememberedStorageLocationsSurviveARestore() {
        val source = Fixture(items = listOf(item("a", "Milk", barcode = "111")))
        val json = try {
            source.localProducts.seed(ProductInfo(barcode = "111", name = "Milk"))
            source.localProducts.storageLocations["111"] = "Freezer"
            source.exportJson()
        } finally {
            source.close()
        }

        val target = Fixture()
        try {
            target.restore(json, RestoreMode.REPLACE)
            assertEquals("Freezer", target.localProducts.storageLocations["111"])
        } finally {
            target.close()
        }
    }

    private fun staple(id: String, name: String, presetId: String? = null, at: Long = 1_763_300_000_000L) = Staple(id, name, presetId, at)

    @Test
    fun testStaplesGoIntoTheBackupAndReplaceRestoresThemInOrder() {
        val source = Fixture(
            staples = listOf(
                staple("own", "Smoked paprika", at = 2),
                staple("salt", "Salt", "salt", at = 5),
                staple("future", "Truffle oil", "truffle_oil", at = 1)
            )
        )
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture(staples = listOf(staple("local", "Honey")))
        try {
            val (_, summary) = target.restore(json, RestoreMode.REPLACE)
            assertEquals(3, summary.staplesRestored)
            // Known presets first, then the rest by time; an unknown preset is kept as it is.
            assertEquals(listOf("salt", "future", "own"), target.stateHolder.uiState.value.staples.map { it.id })
            assertEquals("truffle_oil", target.stapleRepo.staplesFlow.value.single { it.id == "future" }.presetId)
        } finally {
            target.close()
        }
    }

    @Test
    fun testMergeAddsOnlyStaplesThatAreNotThereByAnyName() {
        val source = Fixture(
            staples = listOf(
                staple("salt-ru", "Соль", "salt"),
                staple("oil", "Olive oil", "olive_oil"),
                staple("own", "Soy sauce", at = 3)
            )
        )
        val json = try { source.exportJson() } finally { source.close() }

        // "salt" typed by hand already: the Russian-ticked preset is the same staple.
        val target = Fixture(staples = listOf(staple("mine", "salt"), staple("soy", "SOY SAUCE ")))
        try {
            val (_, summary) = target.restore(json, RestoreMode.MERGE)
            assertEquals(1, summary.staplesRestored)
            assertEquals(setOf("mine", "soy", "oil"), target.stapleRepo.staplesFlow.value.map { it.id }.toSet())
        } finally {
            target.close()
        }
    }

    @Test
    fun testReplaceRestoresTwoRowsOfOneStapleAsOne() {
        val raw = """{"version":1,"staples":[""" +
            """{"id":"a","name":"Salt","presetId":"salt","addedAtEpochMillis":1},""" +
            """{"id":"b","name":"Соль","presetId":"salt","addedAtEpochMillis":2},""" +
            """{"id":"c","name":"salt","presetId":null,"addedAtEpochMillis":3}]}"""
        val target = Fixture(staples = listOf(staple("local", "Honey")))
        try {
            val (_, summary) = target.restore(raw, RestoreMode.REPLACE)
            assertEquals(1, summary.staplesRestored)
            assertEquals(listOf("a"), target.stapleRepo.staplesFlow.value.map { it.id })
        } finally {
            target.close()
        }
    }

    @Test
    fun testMergeStopsAtTwentyStaples() {
        val source = Fixture(staples = (1..5).map { staple("b$it", "Backup $it", at = it.toLong()) })
        val json = try { source.exportJson() } finally { source.close() }

        val target = Fixture(staples = (1..18).map { staple("l$it", "Local $it", at = it.toLong()) })
        try {
            val (_, summary) = target.restore(json, RestoreMode.MERGE)
            assertEquals(2, summary.staplesRestored)
            assertEquals(20, target.stapleRepo.staplesFlow.value.size)
        } finally {
            target.close()
        }
    }

    @Test
    fun testARejectedFileChangesNothing() {
        val fixture = Fixture(items = listOf(item("local", "Yoghurt")))
        try {
            val (result, summary) = fixture.restore("not a backup", RestoreMode.REPLACE)
            assertTrue(result is BackupReadResult.Malformed)
            assertTrue(summary.isEmpty)
            // Crucially the REPLACE wipe must not have run before the file was found unreadable.
            assertEquals(listOf("local"), fixture.pantry.itemsFlow.value.map { it.id })
        } finally {
            fixture.close()
        }
    }
}
