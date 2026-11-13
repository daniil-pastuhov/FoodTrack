package com.foodtracker.recipe

import com.foodtracker.model.AppSettings
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PaidConfigRepository : RecipeConfigRepository {
    var stored = RecipeConfig()
    override fun get(): RecipeConfig = stored
    override fun save(config: RecipeConfig) {
        stored = config
    }
}

private class PaidSavedRepository : SavedRecipeRepository {
    val rows = MutableStateFlow<List<SavedRecipe>>(emptyList())
    override fun getAllFlow(): Flow<List<SavedRecipe>> = rows.map { list -> list.sortedByDescending { it.createdAtEpochMillis } }
    override suspend fun save(recipe: SavedRecipe) {
        rows.value = rows.value.filterNot { it.id == recipe.id } + recipe
    }
    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }
    override suspend fun clearAll() {
        rows.value = emptyList()
    }
}

private object NoModel : OnDeviceRecipeModel {
    override suspend fun availability(): OnDeviceAvailability = OnDeviceAvailability.UNSUPPORTED
    override fun supportsLanguage(languageCode: String): Boolean = false
    override suspend fun download(): Boolean = false
    override suspend fun generate(prompt: String): String = error("not here")
}

/** The server, answering from queues; an empty queue repeats the last answer. */
private class FakeBackend(var available: Boolean = true) : RecipeBackend {
    override val isAvailable: Boolean get() = available
    val generated = mutableListOf<GenerateRequest>()
    val generateReplies = ArrayDeque<ServerResult<GenerateReply>>()
    var generateGate: CompletableDeferred<Unit>? = null
    val verified = mutableListOf<String>()
    val verifyReplies = ArrayDeque<ServerResult<VerifyReply>>()
    var balance: ServerResult<Int> = ServerResult.Ok(0)
    var balanceCosts: RecipeCosts? = null
    var balanceCalls = 0
    val deleteReplies = ArrayDeque<ServerResult<Unit>>()
    var deleteCalls = 0
    var attestation = Attestation.OK
    var attests = 0

    override suspend fun attest(): Attestation {
        attests++
        return attestation
    }

    override suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply> {
        generated += request
        generateGate?.await()
        return generateReplies.removeFirst()
    }
    override suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply> {
        verified += purchaseToken
        return verifyReplies.removeFirst()
    }
    override suspend fun getBalance(): ServerResult<BalanceReply> {
        balanceCalls++
        return when (val current = balance) {
            is ServerResult.Ok -> ServerResult.Ok(BalanceReply(current.value, balanceCosts))
            is ServerResult.Err -> current
        }
    }
    override suspend fun deleteAccount(): ServerResult<Unit> {
        deleteCalls++
        return deleteReplies.removeFirst()
    }
}

private class FakeStore : RecipeStore {
    override val isSupported: Boolean = true
    override val updates = MutableSharedFlow<StoreUpdate>(extraBufferCapacity = 8)
    var packs: List<StorePack>? = listOf(
        StorePack("recipes_10", 10, "£1.99", 1_990_000, "GBP"),
        StorePack("recipes_50", 50, "£7.99", 7_990_000, "GBP"),
        StorePack("recipes_100", 100, "£12.99", 12_990_000, "GBP")
    )
    var held: List<StorePurchase>? = emptyList()
    val bought = mutableListOf<String>()
    var opens = true
    override suspend fun packs(): List<StorePack>? = packs
    override suspend fun buy(productId: String): Boolean {
        bought += productId
        return opens
    }
    override suspend fun purchases(): List<StorePurchase>? = held
}

private class FakeMerge(override val accountBalance: Int?) : PendingMerge

private class FakeAccount(kind: AccountKind = AccountKind.NONE) : RecipeAccount {
    val mutableState = MutableStateFlow(AccountState(kind))
    override val state: StateFlow<AccountState> = mutableState
    override val providers: Set<SignInProvider> = setOf(SignInProvider.GOOGLE, SignInProvider.APPLE)
    var anonymousCreated = 0
    var canCreate = true
    val signInResults = ArrayDeque<SignInResult>()
    var mergeResult: ServerResult<Int> = ServerResult.Ok(0)
    var merged = 0
    var cancelledMerges = 0
    var freshSignInNeeded = false
    var reauthResults = ArrayDeque<Boolean>()
    var reauths = 0
    var signOuts = 0

    override suspend fun ensureUser(): Boolean {
        if (mutableState.value.kind != AccountKind.NONE) return true
        if (!canCreate) return false
        anonymousCreated++
        mutableState.value = AccountState(AccountKind.ANONYMOUS)
        return true
    }
    override suspend fun signIn(provider: SignInProvider): SignInResult {
        val result = signInResults.removeFirst()
        if (result == SignInResult.SignedIn) {
            mutableState.value = AccountState(AccountKind.SIGNED_IN, mutableState.value.providers + provider, "a@b.c")
        }
        return result
    }
    override suspend fun completeMerge(merge: PendingMerge): ServerResult<Int> {
        merged++
        if (mergeResult is ServerResult.Ok) mutableState.value = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE))
        return mergeResult
    }
    override suspend fun cancelMerge(merge: PendingMerge) {
        cancelledMerges++
    }
    override fun needsFreshSignIn(): Boolean = freshSignInNeeded
    override suspend fun reauthenticateForDeletion(): Boolean {
        reauths++
        return reauthResults.removeFirst()
    }
    override suspend fun signOut() {
        signOuts++
        mutableState.value = AccountState()
    }
}

private val sampleRecipe = Recipe(title = "Spinach toast", minutes = 10, steps = listOf("Toast."))

private fun done(balance: Int, recipe: Recipe? = sampleRecipe, menu: Menu? = null) =
    ServerResult.Ok(GenerateReply(GenerateStatus.DONE, recipe = recipe, menu = menu, balance = balance, charged = true))

private fun sampleMenu(courses: Int): Menu = Menu(
    (0 until 3).map { day ->
        MenuDay(
            date = LocalDate(2026, 11, 4 + day),
            uses = listOf("bread"),
            courses = MenuCourseKind.entries.take(courses).map { MenuCourse(it, sampleRecipe.copy(title = "Dish $day $it")) }
        )
    }
)

@OptIn(ExperimentalCoroutinesApi::class)
class RecipePaidTest {

    private val today = LocalDate(2026, 11, 4)

    private fun row(id: String, days: Int, name: String = id.replaceFirstChar { it.uppercase() }): PantryRow {
        val item = PantryItem(
            id = id,
            name = name,
            expiryDate = LocalDate.fromEpochDays(today.toEpochDays() + days),
            addedDate = today,
            quantity = 2
        )
        return PantryRow(item, days, ExpiryStatus.compute(days, 3), null, id)
    }

    private val pantry = MutableStateFlow(
        PantryUiState(
            isLoading = false,
            allRows = listOf(row("bread", -1), row("spinach", 0), row("yoghurt", 1, "Greek yoghurt (2%)"), row("milk", 2)),
            settings = AppSettings(allergens = setOf("peanuts"))
        )
    )
    private val backend = FakeBackend()
    private val store = FakeStore()
    private var account = FakeAccount()
    private val saved = PaidSavedRepository()
    private var paidRepository = InMemoryRecipePaidRepository()
    private var now = 1_000_000L
    private var nextRequest = 0
    private var nextSaved = 0
    private var polls = 0

    private fun TestScope.holder() = RecipeStateHolder(
        pantry = pantry,
        onDeviceModel = NoModel,
        savedRecipeRepository = saved,
        configRepository = PaidConfigRepository(),
        backend = backend,
        store = store,
        account = account,
        paidRepository = paidRepository,
        coroutineScope = backgroundScope,
        nowMillis = { now },
        newId = { "saved${nextSaved++}" },
        newRequestId = { "req${nextRequest++}" },
        today = { today },
        pollDelay = {
            polls++
            now += 3_000
        }
    )

    private fun runUnconfined(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    private fun withBalance(balance: Int, kind: AccountKind = AccountKind.ANONYMOUS) {
        account = FakeAccount(kind)
        paidRepository = InMemoryRecipePaidRepository(RecipePaidSnapshot(balance = balance))
        backend.balance = ServerResult.Ok(balance)
    }

    // --- Availability ----------------------------------------------------------------------

    @Test
    fun aBuildThatCantAttestHidesThePaidRouteAndNeverCallsAnything() = runUnconfined {
        backend.available = false
        val holder = holder()
        holder.refresh()
        holder.openPacks()
        holder.setMode(RecipeMode.MENU)

        val state = holder.uiState.value
        assertFalse(state.paidRouteAvailable)
        assertNull(state.paid.sheet)
        assertEquals(RecipeMode.RECIPE, state.mode, "no menu without the server")
        assertEquals(0, backend.balanceCalls)
        assertEquals(0, account.anonymousCreated)
    }

    @Test
    fun nobodyIsCreatedOrCalledBeforeAPurchaseStarts() = runUnconfined {
        val holder = holder()
        holder.refresh()
        holder.onAppResume()

        assertTrue(holder.uiState.value.paidRouteAvailable)
        assertEquals(0, account.anonymousCreated)
        assertEquals(0, backend.balanceCalls)
        assertTrue(backend.verified.isEmpty())
        assertNull(holder.uiState.value.paid.balance)
    }

    // --- Buying ------------------------------------------------------------------------------

    @Test
    fun theFirstPurchaseAsksWhereRecipesLiveThenGoesToTheStoreWithoutAnAccount() = runUnconfined {
        val holder = holder()
        holder.openPacks()
        val packs = assertIs<BuySheet.Packs>(holder.uiState.value.paid.sheet)
        assertEquals("recipes_50", packs.selected, "the middle pack is chosen first")
        holder.selectPack("recipes_10")
        holder.continuePurchase()
        assertIs<BuySheet.Choice>(holder.uiState.value.paid.sheet)
        assertEquals(0, account.anonymousCreated, "the choice comes before anyone exists")

        holder.buyWithoutAccount()
        assertEquals(1, account.anonymousCreated)
        assertEquals(listOf("recipes_10"), store.bought)
        assertTrue(assertIs<BuySheet.Packs>(holder.uiState.value.paid.sheet).purchasing)
    }

    @Test
    fun laterPurchasesGoStraightToTheStore() = runUnconfined {
        withBalance(2)
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()
        assertEquals(listOf("recipes_50"), store.bought)
    }

    @Test
    fun buyingInAnAccountSignsInFirstAndAFailedSignInStaysOnTheChoice() = runUnconfined {
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()
        account.signInResults += SignInResult.Failed
        holder.buyWithAccount(SignInProvider.GOOGLE)
        assertEquals(PaidEvent.SignInFailed, holder.uiState.value.paid.event)
        assertIs<BuySheet.Choice>(holder.uiState.value.paid.sheet)
        assertTrue(store.bought.isEmpty())

        backend.balance = ServerResult.Ok(38)
        account.signInResults += SignInResult.SignedIn
        holder.buyWithAccount(SignInProvider.GOOGLE)
        assertEquals(listOf("recipes_50"), store.bought)
        assertEquals(38, holder.uiState.value.paid.balance, "the account's own count shows")
    }

    @Test
    fun aStoreThatCantBeReachedShowsInTheSheet() = runUnconfined {
        val holder = holder()
        store.packs = null
        holder.openPacks()
        assertEquals(BuySheet.StoreUnavailable, holder.uiState.value.paid.sheet)
    }

    @Test
    fun aStoreThatSellsThisBuildNothingHidesTheRouteWhenNothingIsHeld() = runUnconfined {
        store.packs = emptyList()
        val holder = holder()
        holder.openPacks()
        holder.setMode(RecipeMode.MENU)

        val state = holder.uiState.value
        assertFalse(state.paidRouteAvailable, "a debug build Play has no products for keeps the free routes only")
        assertFalse(state.paid.canBuy)
        assertNull(state.paid.sheet)
        assertEquals(RecipeMode.RECIPE, state.mode)
        assertEquals(0, account.anonymousCreated)
    }

    @Test
    fun recipesHeldStayUsableWithoutBuyWhenTheStoreSellsNothing() = runUnconfined {
        withBalance(5)
        store.packs = emptyList()
        val holder = holder()

        val paid = holder.uiState.value.paid
        assertTrue(paid.available)
        assertEquals(false, paid.packsOffered)
        assertFalse(paid.canBuy, "no Buy that leads nowhere")
        assertTrue(holder.uiState.value.canGenerateOnServer)
    }

    @Test
    fun buyWaitsForTheStoreToAnswerAndIsAskedAgainOnALaterResume() = runUnconfined {
        store.packs = null
        val holder = holder()
        assertFalse(holder.uiState.value.paidRouteAvailable, "nothing held and no packs known: nothing to show yet")
        assertFalse(holder.uiState.value.paid.canBuy, "unknown packs: no Buy yet")

        store.packs = listOf(StorePack("recipes_10", 10, "£1.99", 1_990_000, "GBP"))
        now += 10_000
        holder.onAppResume()
        assertTrue(holder.uiState.value.paidRouteAvailable)
        assertTrue(holder.uiState.value.paid.canBuy)
    }

    @Test
    fun recipesHeldStayOnScreenWhileTheStoreCantBeReached() = runUnconfined {
        withBalance(4)
        store.packs = null
        val holder = holder()

        assertTrue(holder.uiState.value.paidRouteAvailable)
        assertEquals(4, holder.uiState.value.paid.balance)
        assertFalse(holder.uiState.value.paid.canBuy)
    }

    @Test
    fun aBuildAppCheckRefusesHidesTheRouteBeforeAnyAccountOrPayment() = runUnconfined {
        backend.attestation = Attestation.FAILED
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()

        val state = holder.uiState.value
        assertEquals(1, backend.attests)
        assertFalse(state.paidRouteAvailable)
        assertNull(state.paid.sheet)
        assertEquals(0, account.anonymousCreated)
        assertTrue(store.bought.isEmpty())
    }

    @Test
    fun anAttestationThatCouldNotBeAskedLetsThePurchaseGoOn() = runUnconfined {
        withBalance(0)
        backend.attestation = Attestation.UNKNOWN
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()

        assertTrue(holder.uiState.value.paidRouteAvailable)
        assertEquals(listOf("recipes_50"), store.bought)
    }

    @Test
    fun aGrantedPurchaseAddsTheServersCountAndClosesTheSheet() = runUnconfined {
        withBalance(0)
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()
        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.GRANTED, 50))
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("tok", listOf("recipes_50"), 1, pending = false))))

        val paid = holder.uiState.value.paid
        assertEquals(listOf("tok"), backend.verified)
        assertEquals(50, paid.balance)
        assertEquals(PaidEvent.RecipesAdded(50), paid.event)
        assertNull(paid.sheet)
        assertEquals(0, paid.unconfirmedRecipes)
        assertTrue(paidRepository.get().unverified.isEmpty())
    }

    @Test
    fun aPurchaseTheServerCantConfirmYetIsKeptAcrossARestartAndSentAgain() = runUnconfined {
        withBalance(0)
        val first = holder()
        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.RETRY, 0))
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("tok", listOf("recipes_50"), 1, pending = false))))
        assertEquals(50, first.uiState.value.paid.unconfirmedRecipes)
        assertFalse(first.uiState.value.paid.canBuy, "no second purchase while one is unconfirmed")

        // The server unreachable at the next launch: still shown, still kept.
        backend.verifyReplies += ServerResult.Err(ServerError.OFFLINE)
        val second = holder()
        assertEquals(50, second.uiState.value.paid.unconfirmedRecipes, "shown straight away after a restart")
        // Sent again at launch: the new holder resumes as it starts.
        assertEquals(50, second.uiState.value.paid.unconfirmedRecipes)

        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.ALREADY_GRANTED, 50))
        second.retryPurchases()
        assertEquals(listOf("tok", "tok", "tok"), backend.verified, "the same token every time")
        assertEquals(0, second.uiState.value.paid.unconfirmedRecipes)
        assertEquals(50, second.uiState.value.paid.balance)
        assertTrue(second.uiState.value.paid.canBuy)
    }

    @Test
    fun aRejectedPurchaseIsDroppedAndExplained() = runUnconfined {
        withBalance(0)
        val holder = holder()
        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.REJECTED_PERMANENT, 0))
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("refunded", listOf("recipes_10"), 1, pending = false))))

        assertTrue(holder.uiState.value.paid.purchaseRejected)
        assertEquals(0, holder.uiState.value.paid.unconfirmedRecipes)
        holder.dismissRejectedPurchase()
        assertFalse(holder.uiState.value.paid.purchaseRejected)
    }

    @Test
    fun aPendingPaymentIsNeverSentAndHidesBuyingUntilItClears() = runUnconfined {
        withBalance(0)
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("wait", listOf("recipes_10"), 1, pending = true))))

        assertTrue(holder.uiState.value.paid.paymentPending)
        assertNull(holder.uiState.value.paid.sheet)
        assertTrue(backend.verified.isEmpty())
        assertFalse(holder.uiState.value.paid.canBuy)

        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.GRANTED, 10))
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("wait", listOf("recipes_10"), 1, pending = false))))
        assertFalse(holder.uiState.value.paid.paymentPending)
        assertEquals(10, holder.uiState.value.paid.balance)
    }

    @Test
    fun aHeldPurchaseFoundAtLaunchIsSentEvenWithoutAUser() = runUnconfined {
        store.held = listOf(StorePurchase("old", listOf("recipes_100"), 2, pending = false))
        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.GRANTED, 200))
        backend.balance = ServerResult.Ok(200)
        val holder = holder()
        // The launch itself resubmits it.

        assertEquals(1, account.anonymousCreated, "someone has to own what was paid for")
        assertEquals(200, holder.uiState.value.paid.balance)
        assertEquals(PaidEvent.RecipesAdded(200), holder.uiState.value.paid.event)
    }

    @Test
    fun cancellingInTheStoreGoesBackToThePacks() = runUnconfined {
        withBalance(1)
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()
        store.updates.emit(StoreUpdate.Cancelled)
        assertFalse(assertIs<BuySheet.Packs>(holder.uiState.value.paid.sheet).purchasing)
    }

    // --- Generating on the server --------------------------------------------------------------

    @Test
    fun aRecipeIsSentCleanedAndTheCountDropsOnlyWhenItArrives() = runUnconfined {
        withBalance(12)
        val holder = holder()
        val gate = CompletableDeferred<Unit>()
        backend.generateGate = gate
        backend.generateReplies += done(11)
        holder.generateOnServer()

        assertEquals(RecipeMode.RECIPE, holder.uiState.value.paid.writing)
        assertEquals(12, holder.uiState.value.paid.balance, "nothing drops while it is being written")
        val request = backend.generated.single()
        assertEquals(WireKind.RECIPE, request.kind)
        assertNull(request.courses)
        assertEquals(listOf("Bread", "Spinach", "Greek yoghurt 2"), request.ingredients.map { it.name })
        assertEquals("2026-11-03", request.ingredients.first().expiresOn)
        assertEquals(2, request.ingredients.first().quantity)
        assertEquals(listOf("peanuts"), request.allergens)
        assertEquals("en", request.config.language, "the server needs a language code")
        assertEquals("2026-11-04", request.today)

        gate.complete(Unit)
        val state = holder.uiState.value
        assertEquals(11, state.paid.balance)
        assertNull(state.paid.writing)
        val event = assertIs<RecipeEvent.Written>(state.event)
        assertEquals(11, event.recipesLeft)
        assertEquals(RecipeSource.SERVER, saved.rows.value.single().recipe?.source)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun aLostConnectionKeepsTheRequestAndTryAgainReusesItsId() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()

        assertEquals(ServerIssue.OFFLINE, holder.uiState.value.paid.issue)
        assertEquals(5, holder.uiState.value.paid.balance)
        assertEquals("req0", paidRepository.get().pendingGeneration?.requestId)

        backend.generateReplies += ServerResult.Err(ServerError.INTERNAL)
        holder.retryServer()
        assertEquals(RecipeEvent.ServerFailed, holder.uiState.value.event)

        holder.consumeEvent()
        backend.generateReplies += done(4)
        holder.generateOnServer()
        assertEquals(listOf("req0", "req0", "req0"), backend.generated.map { it.requestId })
        assertEquals(4, holder.uiState.value.paid.balance)
        assertNull(holder.uiState.value.paid.issue)
    }

    @Test
    fun anUnansweredRequestIsAskedAboutAgainAfterARestartAndSavedIfItWasWritten() = runUnconfined {
        withBalance(5)
        val first = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        first.generateOnServer()

        backend.generateReplies += done(4)
        backend.balance = ServerResult.Ok(4)
        val second = holder()
        second.onAppResume()
        assertEquals(listOf("req0", "req0"), backend.generated.map { it.requestId })
        assertEquals(1, saved.rows.value.size, "paid for, so it is kept")
        assertEquals(4, second.uiState.value.paid.balance)
        assertNull(second.uiState.value.event, "a recovery in the background doesn't open anything")
    }

    @Test
    fun tryAgainDuringABackgroundRecoveryDoesntSendTheSettledRequestAgain() = runUnconfined {
        withBalance(5)
        val first = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        first.generateOnServer()

        // The next launch asks about it in the background, and the answer takes a while.
        val gate = CompletableDeferred<Unit>()
        backend.generateGate = gate
        backend.generateReplies += done(4)
        backend.balance = ServerResult.Ok(4)
        val second = holder()
        second.retryServer()
        gate.complete(Unit)

        assertEquals(listOf("req0", "req0"), backend.generated.map { it.requestId }, "sent once at launch, not again by Try again")
        assertEquals(1, saved.rows.value.size)
        assertNull(second.uiState.value.paid.writing)
    }

    @Test
    fun aNewAskSettlesTheHeldOneFirst() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        holder.updateConfig { it.copy(servings = 4) }

        backend.generateReplies += done(4)
        backend.generateReplies += done(3, sampleRecipe.copy(title = "Second"))
        holder.generateOnServer()
        assertEquals(listOf("req0", "req0", "req1"), backend.generated.map { it.requestId })
        assertEquals(backend.generated[0], backend.generated[1], "a held id is only ever sent with the inputs it was first sent with")
        assertEquals(4, backend.generated.last().config.servings)
        assertEquals(2, saved.rows.value.size)
        assertEquals(3, holder.uiState.value.paid.balance)
    }

    @Test
    fun aHeldRequestOlderThanADayIsDropped() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        now += 25 * 60 * 60_000L
        holder.onAppResume()
        assertNull(paidRepository.get().pendingGeneration)
        assertEquals(1, backend.generated.size)
    }

    @Test
    fun inProgressIsPolledWithTheSameIdAndItsHeldBalanceIsNotShown() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.generateReplies += done(4)
        holder.generateOnServer()

        assertEquals(2, polls)
        assertEquals(listOf("req0", "req0", "req0"), backend.generated.map { it.requestId })
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
    }

    @Test
    fun aFailedGenerationNeedsANewIdAndSaysNothingWasTaken() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.FAILED, balance = 5, charged = false))
        holder.generateOnServer()
        assertEquals(RecipeEvent.ServerFailed, holder.uiState.value.event)
        assertNull(paidRepository.get().pendingGeneration)

        holder.consumeEvent()
        backend.generateReplies += done(4)
        holder.generateOnServer()
        assertEquals(listOf("req0", "req1"), backend.generated.map { it.requestId })
    }

    @Test
    fun aRefusalThatWasCountedSaysSo() = runUnconfined {
        withBalance(12)
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.FAILED, balance = 11, charged = true))
        holder.generateOnServer()
        assertEquals(RecipeEvent.ServerRefused(1), holder.uiState.value.event)
        assertEquals(11, holder.uiState.value.paid.balance)
    }

    @Test
    fun aPausedServerHidesBuyingAndTheServerRouteForAWhile() = runUnconfined {
        withBalance(12)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.GENERATION_DISABLED)
        holder.generateOnServer()

        val state = holder.uiState.value
        assertEquals(ServerIssue.PAUSED, state.paid.issue)
        assertFalse(state.paid.canBuy)
        assertFalse(state.canGenerateOnServer)
        assertEquals(12, state.paid.balance, "nothing was taken")
        assertNull(paidRepository.get().pendingGeneration)
        holder.generateOnServer()
        assertEquals(1, backend.generated.size, "not asked again while paused")

        now += 5 * 60_000L
        holder.onAppResume()
        assertNull(holder.uiState.value.paid.issue)
    }

    @Test
    fun tooFewRecipesOpensThePacksInsteadOfCalling() = runUnconfined {
        withBalance(0)
        val holder = holder()
        holder.generateOnServer()
        assertTrue(backend.generated.isEmpty())
        assertIs<BuySheet.Packs>(holder.uiState.value.paid.sheet)
    }

    @Test
    fun theServerSayingTooFewDropsTheRequestAndOpensThePacks() = runUnconfined {
        withBalance(3)
        val holder = holder()
        backend.balance = ServerResult.Ok(0)
        backend.generateReplies += ServerResult.Err(ServerError.INSUFFICIENT_CREDITS)
        holder.generateOnServer()
        assertEquals(0, holder.uiState.value.paid.balance)
        assertIs<BuySheet>(holder.uiState.value.paid.sheet)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun aMenuCostsWhatItsCoursesTakeAndIsSentWithThem() = runUnconfined {
        withBalance(4)
        val holder = holder()
        holder.setMode(RecipeMode.MENU)
        holder.setCourses(3)
        assertEquals(5, holder.uiState.value.serverCost)
        assertFalse(holder.uiState.value.canGenerateOnServer, "four left is not enough for three courses")
        holder.generateOnServer()
        assertTrue(backend.generated.isEmpty())
        holder.dismissPacks()
        holder.setCourses(2)
        assertEquals(4, holder.uiState.value.serverCost)
        assertTrue(holder.uiState.value.canGenerateOnServer, "but it is for two")
        holder.setMode(RecipeMode.RECIPE)
        assertEquals(1, holder.uiState.value.serverCost)

        backend.balance = ServerResult.Ok(9)
        paidRepository.save(RecipePaidSnapshot(balance = 9))
        val rich = holder()
        rich.setMode(RecipeMode.MENU)
        rich.setCourses(2)
        backend.generateReplies += done(6, recipe = null, menu = sampleMenu(2))
        rich.generateOnServer()

        val request = backend.generated.single()
        assertEquals(WireKind.MENU, request.kind)
        assertEquals(2, request.courses)
        val menu = saved.rows.value.single()
        assertEquals(SavedRecipeKind.MENU, menu.kind)
        assertEquals(6, menu.dishCount)
        assertTrue(menu.menu!!.days.all { day -> day.courses.all { it.recipe.source == RecipeSource.SERVER } })
        assertEquals(6, rich.uiState.value.paid.balance)
    }

    @Test
    fun aRefusedMenuCountsWhatItsCoursesTake() = runUnconfined {
        withBalance(9)
        val holder = holder()
        holder.setMode(RecipeMode.MENU)
        holder.setCourses(3)
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.FAILED, balance = 4, charged = true))
        holder.generateOnServer()
        assertEquals(RecipeEvent.ServerRefused(5), holder.uiState.value.event)
    }

    @Test
    fun costsComeFromTheServerAndAreKept() = runUnconfined {
        withBalance(20)
        val holder = holder()
        assertEquals(RecipeCosts(1, 4, 5), holder.uiState.value.paid.costs, "the defaults before the server has said")

        val costs = buildJsonObject {
            put("recipe", 2)
            put("menu2", 6)
            put("menu3", 8)
        }
        backend.generateReplies += ServerResult.Ok(
            GenerateReply(GenerateStatus.DONE, recipe = sampleRecipe, balance = 18, charged = true, costs = costs)
        )
        holder.generateOnServer()

        assertEquals(RecipeCosts(2, 6, 8), holder.uiState.value.paid.costs)
        assertEquals(RecipeCosts(2, 6, 8), paidRepository.get().costs, "kept for the next launch")
        assertEquals(2, holder.uiState.value.serverCost)
        holder.setMode(RecipeMode.MENU)
        holder.setCourses(2)
        assertEquals(6, holder.uiState.value.serverCost)

        val again = holder()
        assertEquals(RecipeCosts(2, 6, 8), again.uiState.value.paid.costs)
    }

    @Test
    fun aRefusalCountsWhatTheServerLastSaidItCosts() = runUnconfined {
        account = FakeAccount(AccountKind.ANONYMOUS)
        paidRepository = InMemoryRecipePaidRepository(RecipePaidSnapshot(balance = 10, costs = RecipeCosts(recipe = 2)))
        backend.balance = ServerResult.Ok(10)
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.FAILED, balance = 8, charged = true))
        holder.generateOnServer()
        assertEquals(RecipeEvent.ServerRefused(2), holder.uiState.value.event)
    }

    @Test
    fun costsFromGetBalanceReplaceTheKeptOnes() = runUnconfined {
        withBalance(7)
        backend.balanceCosts = RecipeCosts(1, 3, 4)
        val holder = holder()
        holder.onAppResume()
        assertEquals(RecipeCosts(1, 3, 4), holder.uiState.value.paid.costs)
    }

    @Test
    fun costsAreReadLeniently() {
        assertNull(recipeCostsOf(null))
        assertNull(recipeCostsOf(JsonPrimitive(3)))
        assertEquals(RecipeCosts(1, 4, 5), recipeCostsOf(buildJsonObject { }))
        val odd = buildJsonObject {
            put("recipe", "two")
            put("menu2", 0)
            put("menu3", 6.0)
            put("extra", 9)
        }
        assertEquals(RecipeCosts(1, 4, 6), recipeCostsOf(odd), "bad fields keep their defaults, whole doubles count")
        assertEquals(RecipeCosts(1, 4, 5), recipeCostsOf(buildJsonObject { put("menu2", -4); put("menu3", 5.5) }))
        assertEquals(RecipeCosts(1, 4, 5), recipeCostsOf(buildJsonObject { put("recipe", 1_000_000) }))
    }

    @Test
    fun aReasonThisAppDoesntKnowSaysTryLaterAndTakesNothing() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.fromReason("TOO_MANY_FAILURES_TODAY", ServerError.INTERNAL))
        holder.generateOnServer()

        assertEquals(RecipeEvent.ServerFailedLater, holder.uiState.value.event)
        assertEquals(5, holder.uiState.value.paid.balance)
        assertNull(paidRepository.get().pendingGeneration, "refused before anything was held")
        assertTrue(holder.uiState.value.paidRouteAvailable)
    }

    @Test
    fun reasonsAreMatchedOnlyToTheContractsOwn() {
        assertEquals(ServerError.DAILY_CAP, ServerError.fromReason("DAILY_CAP", ServerError.INTERNAL))
        assertEquals(ServerError.INTERNAL, ServerError.fromReason(null, ServerError.INTERNAL))
        assertEquals(ServerError.OFFLINE, ServerError.fromReason("", ServerError.OFFLINE))
        assertEquals(ServerError.UNKNOWN, ServerError.fromReason("SOMETHING_NEW", ServerError.INTERNAL))
        assertEquals(ServerError.UNKNOWN, ServerError.fromReason("OFFLINE", ServerError.INTERNAL), "the client's own names aren't reasons")
    }

    @Test
    fun aLimitTheServerSetsSaysTryLater() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.RATE_LIMITED)
        holder.generateOnServer()
        assertEquals(RecipeEvent.ServerFailedLater, holder.uiState.value.event)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun anAppCheckRejectionHidesThePaidRouteForTheRun() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.UNAUTHENTICATED)
        holder.generateOnServer()
        assertFalse(holder.uiState.value.paidRouteAvailable)
        assertEquals(RecipeEvent.ServerFailed, holder.uiState.value.event)
    }

    // --- Account ---------------------------------------------------------------------------

    @Test
    fun signingInWithAnAccountThatHasRecipesAsksBeforeJoiningThem() = runUnconfined {
        withBalance(12)
        val holder = holder()
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = 38))
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(MergePrompt(12, 38), holder.uiState.value.paid.merge)
        assertEquals(0, account.merged, "nothing moves before the person agrees")

        account.mergeResult = ServerResult.Ok(50)
        holder.confirmMerge()
        assertEquals(1, account.merged)
        assertEquals(50, holder.uiState.value.paid.balance)
        assertNull(holder.uiState.value.paid.merge)
        assertFalse(holder.uiState.value.paid.accountBusy)
    }

    @Test
    fun cancellingTheJoinLeavesThePhonesRecipesWhereTheyAre() = runUnconfined {
        withBalance(12)
        val holder = holder()
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = 38))
        holder.signIn(SignInProvider.APPLE)
        holder.cancelMerge()
        assertEquals(1, account.cancelledMerges)
        assertEquals(0, account.merged)
        assertEquals(12, holder.uiState.value.paid.balance)
        assertEquals(AccountKind.ANONYMOUS, holder.uiState.value.paid.account.kind)
    }

    @Test
    fun withNothingOnThePhoneTheJoinNeedsNoQuestion() = runUnconfined {
        withBalance(0)
        val holder = holder()
        account.mergeResult = ServerResult.Ok(38)
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = 38))
        holder.signIn(SignInProvider.GOOGLE)
        assertNull(holder.uiState.value.paid.merge)
        assertEquals(1, account.merged)
        assertEquals(38, holder.uiState.value.paid.balance)
    }

    @Test
    fun aFailedJoinSaysSoAndKeepsThePhonesCount() = runUnconfined {
        withBalance(12)
        val holder = holder()
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = null))
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(MergePrompt(12, null), holder.uiState.value.paid.merge)
        account.mergeResult = ServerResult.Err(ServerError.MERGE_NOT_ALLOWED)
        holder.confirmMerge()
        assertEquals(PaidEvent.SignInFailed, holder.uiState.value.paid.event)
        assertEquals(12, holder.uiState.value.paid.balance)
    }

    @Test
    fun linkingAnAnonymousInstallKeepsItsCountAndAddingATakenSignInIsExplained() = runUnconfined {
        withBalance(12)
        val holder = holder()
        backend.balance = ServerResult.Ok(12)
        account.signInResults += SignInResult.SignedIn
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(AccountKind.SIGNED_IN, holder.uiState.value.paid.account.kind)
        assertEquals(12, holder.uiState.value.paid.balance)

        account.signInResults += SignInResult.InUse
        holder.signIn(SignInProvider.APPLE)
        assertEquals(PaidEvent.SignInInUse, holder.uiState.value.paid.event)
    }

    @Test
    fun signingOutForgetsTheCountAndAnyHeldRequest() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        holder.signOut()
        assertEquals(1, account.signOuts)
        assertNull(holder.uiState.value.paid.balance)
        assertNull(paidRepository.get().pendingGeneration, "never sent again for someone else")
        assertNull(holder.uiState.value.paid.issue)
    }

    @Test
    fun deletingAnAccountSignsInAgainFirstWhenTheLastSignInIsOld() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        account.freshSignInNeeded = true
        val holder = holder()

        account.reauthResults += false
        holder.deleteAccount()
        assertEquals(0, backend.deleteCalls, "a cancelled sign-in deletes nothing")
        assertEquals(12, holder.uiState.value.paid.balance)

        account.reauthResults += true
        backend.deleteReplies += ServerResult.Ok(Unit)
        holder.deleteAccount()
        assertEquals(1, backend.deleteCalls)
        assertEquals(1, account.signOuts)
        assertNull(holder.uiState.value.paid.balance)
        assertEquals(PaidEvent.AccountDeleted, holder.uiState.value.paid.event)
    }

    @Test
    fun deletingRetriesOnceAfterTheServerAsksForAFreshSignIn() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        val holder = holder()
        backend.deleteReplies += ServerResult.Err(ServerError.RECENT_SIGN_IN_REQUIRED)
        backend.deleteReplies += ServerResult.Ok(Unit)
        account.reauthResults += true
        holder.deleteAccount()
        assertEquals(1, account.reauths)
        assertEquals(2, backend.deleteCalls)
        assertEquals(PaidEvent.AccountDeleted, holder.uiState.value.paid.event)
    }

    @Test
    fun aDeletionWhileARecipeIsBeingWrittenSaysToWait() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        val holder = holder()
        backend.deleteReplies += ServerResult.Err(ServerError.BUSY)
        holder.deleteAccount()
        assertEquals(PaidEvent.AccountBusy, holder.uiState.value.paid.event)
        assertEquals(0, account.signOuts)
        assertEquals(12, holder.uiState.value.paid.balance)
    }

    @Test
    fun aRequestIdBoundToOtherInputsIsDroppedAndTheNextAskGetsANewOne() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.REQUEST_ID_REUSED)
        holder.generateOnServer()

        assertEquals(RecipeEvent.ServerFailed, holder.uiState.value.event)
        assertNull(paidRepository.get().pendingGeneration)
        assertEquals(5, holder.uiState.value.paid.balance, "nothing was taken")
        holder.consumeEvent()
        backend.generateReplies += done(4)
        holder.generateOnServer()
        assertEquals(listOf("req0", "req1"), backend.generated.map { it.requestId })
    }

    @Test
    fun aReplayedAppCheckTokenKeepsTheRouteAndTheRequest() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.APP_CHECK_REPLAYED)
        holder.generateOnServer()

        assertTrue(holder.uiState.value.paidRouteAvailable, "not a build that can't attest")
        assertEquals(RecipeEvent.ServerFailed, holder.uiState.value.event)
        assertEquals("req0", paidRepository.get().pendingGeneration?.requestId, "kept: it can't have been charged twice")
    }

    @Test
    fun dailyLimitsSayTomorrowAndTakeNothing() = runUnconfined {
        withBalance(5)
        val holder = holder()
        for (error in listOf(ServerError.USER_DAILY_LIMIT, ServerError.DAILY_CAP)) {
            backend.generateReplies += ServerResult.Err(error)
            holder.generateOnServer()
            assertEquals(RecipeEvent.ServerLimitToday, holder.uiState.value.event, "$error")
            assertNull(paidRepository.get().pendingGeneration)
            holder.consumeEvent()
        }
        backend.generateReplies += ServerResult.Err(ServerError.INFRA_FAILURE_LIMIT)
        holder.generateOnServer()
        assertEquals(RecipeEvent.ServerTroubleToday, holder.uiState.value.event)
        assertEquals(5, holder.uiState.value.paid.balance)
        assertTrue(holder.uiState.value.paidRouteAvailable)
    }

    @Test
    fun aSpendingCeilingPausesLikeTheKillSwitch() = runUnconfined {
        withBalance(12)
        val holder = holder()
        holder.setMode(RecipeMode.MENU)
        backend.generateReplies += ServerResult.Err(ServerError.GENERATION_DISABLED)
        holder.generateOnServer()
        assertEquals(ServerIssue.PAUSED, holder.uiState.value.paid.issue)
        assertEquals(12, holder.uiState.value.paid.balance)
    }

    @Test
    fun aFailedDeletionKeepsTheAccount() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        val holder = holder()
        backend.deleteReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.deleteAccount()
        assertEquals(PaidEvent.AccountActionFailed, holder.uiState.value.paid.event)
        assertEquals(0, account.signOuts)
        assertEquals(12, holder.uiState.value.paid.balance)
    }

    @Test
    fun anAccountClosedElsewhereStartsOverWithoutOne() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        val holder = holder()
        backend.balance = ServerResult.Err(ServerError.ACCOUNT_CLOSED)
        now += 60_000
        holder.onAppResume()
        assertEquals(1, account.signOuts)
        assertNull(holder.uiState.value.paid.balance)
    }

    // --- Clear all data ----------------------------------------------------------------------

    @Test
    fun clearAllResetsTheFormAndSavedRecipesButNotWhatWasBought() = runUnconfined {
        withBalance(12)
        paidRepository.save(RecipePaidSnapshot(balance = 12, unverified = listOf(UnverifiedPurchase("tok", 10))))
        val config = PaidConfigRepository().apply { stored = RecipeConfig(servings = 6, language = "de") }
        val holder = RecipeStateHolder(
            pantry = pantry, onDeviceModel = NoModel, savedRecipeRepository = saved, configRepository = config,
            backend = backend, store = store, account = account, paidRepository = paidRepository,
            coroutineScope = backgroundScope, today = { today }
        )
        saved.save(SavedRecipe.of("a", sampleRecipe, 1L))
        holder.setMode(RecipeMode.MENU)
        holder.clearAll()

        assertEquals(RecipeConfig(), config.stored)
        assertEquals(RecipeConfig(), holder.uiState.value.config)
        assertEquals(RecipeMode.RECIPE, holder.uiState.value.mode)
        assertTrue(saved.rows.value.isEmpty())
        assertEquals(12, paidRepository.get().balance)
        assertEquals(1, paidRepository.get().unverified.size)
    }

    @Test
    fun wireNamesKeepLettersDigitsSpacesAndHyphensOnly() {
        assertEquals("Ben Jerry s", cleanWireName("Ben & Jerry's"))
        assertEquals("Хлеб на закваске", cleanWireName("  Хлеб на закваске! "))
        assertEquals("semi-skimmed", cleanWireName("semi-skimmed"))
        assertNull(cleanWireName("🍞 ,."))
        assertEquals(40, cleanWireName("a".repeat(60))!!.length)
    }
}
