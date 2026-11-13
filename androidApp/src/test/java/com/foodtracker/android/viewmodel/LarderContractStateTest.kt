package com.foodtracker.android.viewmodel

import com.foodtracker.android.perf.FakeCategoryPreferenceRepository
import com.foodtracker.android.perf.FakeLocalProductRepository
import com.foodtracker.android.perf.FakePantryRepository
import com.foodtracker.android.perf.FakeProductNameRepository
import com.foodtracker.android.perf.FakeSettingsRepository
import com.foodtracker.android.perf.FakeShoppingListRepository
import com.foodtracker.android.perf.FakeShoppingSectionRepository
import com.foodtracker.api.OpenFoodFactsApi
import com.foodtracker.api.ProductLookupResult
import com.foodtracker.backup.BackupDocument
import com.foodtracker.backup.BackupSerializer
import com.foodtracker.backup.RestoreMode
import com.foodtracker.model.AppSettings
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.HomeConfirmation
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.PantryUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** The state the Larder screens are built on: lookups, onboarding, bulk delete, confirmations. */
class LarderContractStateTest {

    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    private fun item(id: String, name: String = id, quantity: Int = 1) = PantryItem(
        id = id,
        name = name,
        expiryDate = DateUtils.today(),
        addedDate = DateUtils.today(),
        storageLocation = "Fridge",
        quantity = quantity
    )

    private inner class Fixture(
        items: List<PantryItem> = emptyList(),
        settings: AppSettings = AppSettings(),
        api: OpenFoodFactsApi = offlineApi()
    ) {
        val pantry = FakePantryRepository(items)
        val localProducts = FakeLocalProductRepository()
        val settingsRepository = FakeSettingsRepository(settings)
        val stateHolder = PantryStateHolder(
            repository = pantry,
            settingsRepository = settingsRepository,
            shoppingListRepository = FakeShoppingListRepository(),
            productNameRepository = FakeProductNameRepository(),
            localProductRepository = localProducts,
            categoryPreferenceRepository = FakeCategoryPreferenceRepository(),
            shoppingSectionRepository = FakeShoppingSectionRepository(),
            uncategorizedLabel = "Other",
            openFoodFactsApi = api,
            coroutineScope = scope,
            computationContext = EmptyCoroutineContext
        )

        val state get() = stateHolder.uiState.value

        /** The mock engine answers on its own thread, so a lookup finishes after the call. */
        fun awaitLookups() = runBlocking {
            withTimeout(5_000) {
                stateHolder.uiState.first { state ->
                    !state.isLookingUpBarcode && state.batchQueue.none { it.isLookingUp }
                }
            }
        }

        fun itemIds() = runBlocking { pantry.getItemsFlow().first().map { it.id }.toSet() }
    }

    private fun offlineApi() = OpenFoodFactsApi(HttpClient(MockEngine { throw IOException("no network") }))

    private fun notFoundApi() =
        OpenFoodFactsApi(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))

    @After
    fun tearDown() = scope.cancel()

    // ---- Lookup ---------------------------------------------------------------------------

    @Test
    fun testAFailedLookupIsOfflineAndStillOffersAddingByHand() {
        val fixture = Fixture(api = offlineApi())

        fixture.stateHolder.onBarcodeScanned("123")
        fixture.awaitLookups()

        assertEquals(ProductLookupResult.Offline, fixture.state.lookupResult)
        assertEquals("123", fixture.state.productNotFoundBarcode)
        assertNull(fixture.state.scannedProduct)
        assertFalse(fixture.state.isLookingUpBarcode)
    }

    @Test
    fun testAnUnknownBarcodeIsNotFound() {
        val fixture = Fixture(api = notFoundApi())

        fixture.stateHolder.onBarcodeScanned("123")
        fixture.awaitLookups()

        assertEquals(ProductLookupResult.NotFound, fixture.state.lookupResult)
        assertEquals("123", fixture.state.productNotFoundBarcode)
    }

    @Test
    fun testAProductScannedBeforeStillResolvesOffline() {
        val fixture = Fixture(api = offlineApi())
        val cached = ProductInfo(barcode = "123", name = "Milk")
        fixture.localProducts.seed(cached)

        fixture.stateHolder.onBarcodeScanned("123")

        assertEquals(ProductLookupResult.Found(cached), fixture.state.lookupResult)
        assertEquals(cached, fixture.state.scannedProduct)
        assertNull(fixture.state.productNotFoundBarcode)
    }

    @Test
    fun testAnOfflineResultHoldsTheScanUntilCleared() {
        val fixture = Fixture(api = offlineApi())
        fixture.stateHolder.onBarcodeScanned("123")
        fixture.awaitLookups()

        assertFalse(fixture.stateHolder.onBarcodeScanned("456"))

        fixture.stateHolder.clearScanResults()
        assertNull(fixture.state.lookupResult)
        assertNull(fixture.state.productNotFoundBarcode)
        assertTrue(fixture.stateHolder.onBarcodeScanned("456"))
    }

    @Test
    fun testABatchRowStaysUsableWhenItsLookupFails() {
        val fixture = Fixture(api = offlineApi())

        fixture.stateHolder.enqueueBatchScan("123")
        fixture.awaitLookups()

        val row = fixture.state.batchQueue.single()
        assertEquals("123", row.name)
        assertFalse(row.isLookingUp)
    }

    // ---- Onboarding -----------------------------------------------------------------------

    @Test
    fun testAFreshInstallNeedsOnboarding() {
        val fixture = Fixture()

        assertTrue(fixture.state.needsOnboarding)
        assertFalse(fixture.state.settings.onboardingCompleted)
    }

    @Test
    fun testNothingAsksForOnboardingWhileThePantryIsStillLoading() {
        // Before the pantry first loads, an upgrade with items can't be told from a fresh
        // install, so onboarding must wait rather than flash up.
        assertFalse(PantryUiState(isLoading = true).needsOnboarding)
        assertTrue(PantryUiState(isLoading = false).needsOnboarding)
    }

    @Test
    fun testUpgradingWithItemsCountsAsDoneAndIsPersisted() {
        val fixture = Fixture(items = listOf(item("a")))

        assertFalse(fixture.state.needsOnboarding)
        assertTrue(fixture.state.settings.onboardingCompleted)
        assertTrue(fixture.settingsRepository.getSettings().onboardingCompleted)
    }

    @Test
    fun testCompletingIsPersistedAndKeepsOtherSettings() {
        val fixture = Fixture(settings = AppSettings(expiringSoonDays = 7))

        fixture.stateHolder.completeOnboarding()

        assertFalse(fixture.state.needsOnboarding)
        val saved = fixture.settingsRepository.getSettings()
        assertTrue(saved.onboardingCompleted)
        assertEquals(7, saved.expiringSoonDays)
    }

    @Test
    fun testSomeoneWhoFinishedDoesNotSeeItAgainWithAnEmptyPantry() {
        val fixture = Fixture(settings = AppSettings(onboardingCompleted = true))

        assertFalse(fixture.state.needsOnboarding)
    }

    @Test
    fun testEmptyingThePantryDoesNotBringOnboardingBack() {
        val fixture = Fixture(items = listOf(item("a")))

        fixture.stateHolder.deleteItem("a")

        assertTrue(fixture.itemIds().isEmpty())
        assertFalse(fixture.state.needsOnboarding)
    }

    @Test
    fun testReplacingFromABackupCountsAsDoneEvenWhenTheBackupPredatesOnboarding() {
        val fixture = Fixture()
        val backup = BackupSerializer.encode(
            BackupDocument(settings = AppSettings(onboardingCompleted = false))
        )

        fixture.stateHolder.restoreBackup(backup, RestoreMode.REPLACE) { _, _ -> }

        assertFalse(fixture.state.needsOnboarding)
    }

    // ---- Bulk delete ----------------------------------------------------------------------

    @Test
    fun testDeleteItemsRemovesAllAndReportsThemOnce() {
        val fixture = Fixture(items = listOf(item("a"), item("b"), item("c")))
        val reports = mutableListOf<List<PantryItem>>()

        fixture.stateHolder.deleteItems(setOf("a", "c")) { reports += it }

        assertEquals(setOf("b"), fixture.itemIds())
        assertEquals(1, reports.size)
        assertEquals(setOf("a", "c"), reports.single().map { it.id }.toSet())
    }

    @Test
    fun testOneRestoreBringsTheWholeSelectionBackIntact() {
        val original = listOf(item("a", name = "Milk", quantity = 3), item("b"))
        val fixture = Fixture(items = original)
        var removed = emptyList<PantryItem>()
        fixture.stateHolder.deleteItems(setOf("a", "b")) { removed = it }

        fixture.stateHolder.restoreItems(removed)

        assertEquals(setOf("a", "b"), fixture.itemIds())
        assertEquals(
            original.toSet(),
            runBlocking { fixture.pantry.getItemsFlow().first() }.toSet()
        )
    }

    @Test
    fun testIdsAlreadyGoneAreIgnored() {
        val fixture = Fixture(items = listOf(item("a")))
        var removed = emptyList<PantryItem>()

        fixture.stateHolder.deleteItems(listOf("a", "missing")) { removed = it }

        assertEquals(listOf("a"), removed.map { it.id })
    }

    @Test
    fun testNothingToDeleteReportsNothing() {
        val fixture = Fixture(items = listOf(item("a")))
        var calls = 0

        fixture.stateHolder.deleteItems(emptyList()) { calls++ }
        fixture.stateHolder.deleteItems(listOf("missing")) { calls++ }

        assertEquals(0, calls)
        assertEquals(setOf("a"), fixture.itemIds())
    }

    // ---- Confirmations --------------------------------------------------------------------

    @Test
    fun testSavingAnItemConfirmsItsNameAndStoredLocation() {
        val fixture = Fixture()

        fixture.stateHolder.saveItem(item("a", name = "Mature cheddar"))

        assertEquals(HomeConfirmation.Saved("Mature cheddar", "Fridge"), fixture.state.homeConfirmation)
    }

    @Test
    fun testAConfirmationClearsOnceShown() {
        val fixture = Fixture()
        fixture.stateHolder.saveItem(item("a"))

        fixture.stateHolder.consumeHomeConfirmation()

        assertNull(fixture.state.homeConfirmation)
    }

    @Test
    fun testABatchConfirmsOnceForTheWholeQueue() {
        val fixture = Fixture()
        val seen = mutableListOf<HomeConfirmation?>()
        fixture.stateHolder.enqueueBatchScan("1")
        fixture.stateHolder.enqueueBatchScan("2")
        fixture.stateHolder.enqueueBatchScan("3")
        fixture.awaitLookups()

        fixture.stateHolder.commitBatchQueue { seen += fixture.state.homeConfirmation }

        assertEquals(listOf<HomeConfirmation?>(HomeConfirmation.BatchAdded(3)), seen)
        assertEquals(HomeConfirmation.BatchAdded(3), fixture.state.homeConfirmation)
        assertEquals(3, fixture.itemIds().size)
    }

    @Test
    fun testAnEmptyBatchConfirmsNothing() {
        val fixture = Fixture()

        fixture.stateHolder.commitBatchQueue()

        assertNull(fixture.state.homeConfirmation)
    }

    @Test
    fun testAddOneMoreConfirmsTheNewQuantity() {
        val fixture = Fixture(items = listOf(item("a", name = "Greek yoghurt", quantity = 2)))

        fixture.stateHolder.addOneMore("a")

        assertEquals(HomeConfirmation.OneMoreAdded("Greek yoghurt", 3), fixture.state.homeConfirmation)
    }

    @Test
    fun testAddOneMoreStopsAtTheQuantityLimit() {
        val fixture = Fixture(items = listOf(item("a", name = "Eggs", quantity = 99)))

        fixture.stateHolder.addOneMore("a")

        assertEquals(HomeConfirmation.OneMoreAdded("Eggs", 99), fixture.state.homeConfirmation)
    }

    @Test
    fun testAnOlderItemAboveTheLimitKeepsItsQuantity() {
        val fixture = Fixture(items = listOf(item("a", quantity = 120)))

        fixture.stateHolder.incrementQuantity("a")

        assertEquals(120, runBlocking { fixture.pantry.getItem("a") }?.quantity)
    }

    @Test
    fun testAnUndoThatAddsAUnitBackConfirmsNothing() {
        val fixture = Fixture(items = listOf(item("a", quantity = 2)))

        fixture.stateHolder.incrementQuantity("a")

        assertNull(fixture.state.homeConfirmation)
    }
}
