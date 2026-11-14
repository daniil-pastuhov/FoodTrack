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
    var balanceBlocked = false
    var balanceCalls = 0
    val deleteReplies = ArrayDeque<ServerResult<Unit>>()
    var deleteCalls = 0
    var attestation = Attestation.OK
    var attests = 0

    /** Null: a server that predates getGeneration. */
    var polls: ArrayDeque<ServerResult<GenerateReply>>? = null
    val polled = mutableListOf<String>()

    override suspend fun getGeneration(requestId: String): ServerResult<GenerateReply> {
        val replies = polls ?: return ServerResult.Err(ServerError.CALLABLE_MISSING)
        polled += requestId
        return replies.removeFirst()
    }

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
            is ServerResult.Ok -> ServerResult.Ok(BalanceReply(current.value, balanceCosts, balanceBlocked))
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

private class FakeAccount(
    kind: AccountKind = AccountKind.NONE,
    carried: Set<SignInProvider> = if (kind == AccountKind.SIGNED_IN) setOf(SignInProvider.GOOGLE) else emptySet()
) : RecipeAccount {
    val mutableState = MutableStateFlow(AccountState(kind, carried, purchaseKey = if (kind == AccountKind.NONE) null else "key-$kind"))
    override val state: StateFlow<AccountState> = mutableState
    /** As on Android: Google only, never Apple. */
    override val providers: Set<SignInProvider> = setOf(SignInProvider.GOOGLE)
    var anonymousCreated = 0
    var canCreate = true
    val signInResults = ArrayDeque<SignInResult>()
    var mergeResult: ServerResult<MergeReply> = ServerResult.Ok(MergeReply(0))

    /** Answers before [mergeResult], one per call. */
    val mergeQueue = ArrayDeque<ServerResult<MergeReply>>()
    var merged = 0
    var cancelledMerges = 0
    var freshSignInNeeded = false
    var reauthResults = ArrayDeque<Boolean>()
    var reauths = 0
    /** The sign-ins asked for, in order. */
    val signIns = mutableListOf<SignInProvider>()
    var signOuts = 0

    override suspend fun ensureUser(): Boolean {
        if (mutableState.value.kind != AccountKind.NONE) return true
        if (!canCreate) return false
        anonymousCreated++
        mutableState.value = AccountState(AccountKind.ANONYMOUS, purchaseKey = "key-anon-$anonymousCreated")
        return true
    }
    override suspend fun signIn(provider: SignInProvider): SignInResult {
        signIns += provider
        val result = signInResults.removeFirst()
        if (result == SignInResult.SignedIn) {
            mutableState.value = AccountState(AccountKind.SIGNED_IN, mutableState.value.providers + provider, "a@b.c", "key-signed-in")
        }
        return result
    }
    override suspend fun completeMerge(merge: PendingMerge): ServerResult<MergeReply> {
        merged++
        val result = mergeQueue.removeFirstOrNull() ?: mergeResult
        if (result is ServerResult.Ok) mutableState.value = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE), purchaseKey = "key-merged")
        return result
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

    /** Every wait the paid route asked for, in milliseconds. */
    private val waits = mutableListOf<Long>()

    /** Called at each wait, before the clock moves on. */
    private var onWait: (RecipeStateHolder?) -> Unit = {}
    private var current: RecipeStateHolder? = null

    private fun TestScope.holder() = makeHolder().also { current = it }

    private fun TestScope.makeHolder() = RecipeStateHolder(
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
        pollDelay = { millis ->
            polls++
            waits += millis
            onWait(current)
            now += millis
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
        assertEquals(listOf("Bread", "Spinach", "Greek yoghurt 2%"), request.ingredients.map { it.name })
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
        assertEquals(PaidEvent.EarlierSaved(menu = false), second.uiState.value.paid.event, "but says it arrived")
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
        assertEquals(PaidEvent.EarlierSaved(menu = false), holder.uiState.value.paid.event, "the earlier one is announced, not just saved")
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
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
    fun thePerMinuteLimitSaysTooManyInAShortTime() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.RATE_LIMITED)
        holder.generateOnServer()
        assertEquals(RecipeEvent.ServerRateLimited, holder.uiState.value.event)
        assertEquals(5, holder.uiState.value.paid.balance, "nothing taken")
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun anotherRecipeStillBeingWrittenForTheWholeWaitSaysSo() = runUnconfined {
        withBalance(5)
        val holder = holder()
        repeat(100) { backend.generateReplies += ServerResult.Err(ServerError.BUSY) }
        holder.generateOnServer()

        val state = holder.uiState.value
        assertEquals(RecipeEvent.ServerBusy, state.event)
        assertEquals(5, state.paid.balance, "nothing taken")
        assertNull(state.paid.writing)
        assertNull(paidRepository.get().pendingGeneration, "refused as it was started: not kept")
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

        account.mergeResult = ServerResult.Ok(MergeReply(50, 12))
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
        holder.signIn(SignInProvider.GOOGLE)
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
        account.mergeResult = ServerResult.Ok(MergeReply(38))
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
        assertEquals(1, account.merged, "a refusal isn't asked again")
        assertEquals(1, account.cancelledMerges, "the account signed in for the join is let go")
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
        holder.signIn(SignInProvider.GOOGLE)
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
    fun androidOffersGoogleOnlyEvenToAnAccountThatCarriesApple() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        account = FakeAccount(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE, SignInProvider.APPLE))
        val holder = holder()
        val paid = holder.uiState.value.paid
        assertEquals(setOf(SignInProvider.GOOGLE), paid.providers, "no Apple sign-in to start or add")
        assertEquals(setOf(SignInProvider.GOOGLE, SignInProvider.APPLE), paid.account.providers, "but the account shows what it carries")
        assertEquals(AccountKind.SIGNED_IN, paid.account.kind)
        assertEquals(12, paid.balance)
    }

    @Test
    fun anAccountWithAppleAttachedIsDeletedAfterSigningInAgainWithGoogle() = runUnconfined {
        withBalance(12, AccountKind.SIGNED_IN)
        account = FakeAccount(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE, SignInProvider.APPLE))
        val holder = holder()
        // Recent enough on the phone's clock, but the server disagrees: signed in again once.
        backend.deleteReplies += ServerResult.Err(ServerError.RECENT_SIGN_IN_REQUIRED)
        backend.deleteReplies += ServerResult.Ok(Unit)
        account.reauthResults += true
        holder.deleteAccount()
        assertEquals(1, account.reauths)
        assertEquals(2, backend.deleteCalls)
        assertEquals(emptyList(), account.signIns, "re-authentication isn't a new sign-in, and never Apple")
        assertEquals(1, account.signOuts)
        assertNull(holder.uiState.value.paid.balance)
        assertEquals(PaidEvent.AccountDeleted, holder.uiState.value.paid.event)
        assertFalse(holder.uiState.value.paid.accountBusy)
    }

    @Test
    fun anAppleAttachedAccountWhoseLastSignInIsRecentIsDeletedWithoutAskingAgain() = runUnconfined {
        withBalance(3, AccountKind.SIGNED_IN)
        account = FakeAccount(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE, SignInProvider.APPLE))
        val holder = holder()
        backend.deleteReplies += ServerResult.Ok(Unit)
        holder.deleteAccount()
        assertEquals(0, account.reauths, "Apple attached no longer forces a sign-in on Android")
        assertEquals(1, backend.deleteCalls)
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
    fun pollsAskWithoutStartingAgain() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.polls!! += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.polls!! += done(4)
        holder.generateOnServer()

        assertEquals(listOf("req0"), backend.generated.map { it.requestId }, "one generateRecipe, one limited-use token")
        assertEquals(listOf("req0", "req0"), backend.polled)
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
        assertEquals(4, holder.uiState.value.paid.balance)
    }

    @Test
    fun aHeldRequestIsAskedAboutAtLaunchNotStartedAgain() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val first = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        first.generateOnServer()

        backend.polls!! += done(4)
        val second = holder()
        second.onAppResume()

        assertEquals(listOf("req0"), backend.generated.map { it.requestId }, "only the first send")
        assertEquals(listOf("req0"), backend.polled)
        assertEquals(1, saved.rows.value.size)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun aRequestTheServerNeverGotIsSentAgainWithTheSameIdAndInputs() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        holder.consumeEvent()

        backend.polls!! += ServerResult.Err(ServerError.UNKNOWN_REQUEST)
        backend.generateReplies += done(4)
        holder.retryServer()

        assertEquals(listOf("req0", "req0"), backend.generated.map { it.requestId })
        assertEquals(backend.generated[0], backend.generated[1])
        assertEquals(listOf("req0"), backend.polled)
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
    }

    @Test
    fun anOlderServerWithoutGetGenerationIsPolledTheOldWay() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.generateReplies += done(4)
        holder.generateOnServer()

        assertEquals(listOf("req0", "req0"), backend.generated.map { it.requestId })
        assertTrue(backend.polled.isEmpty())
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
    }

    @Test
    fun aLimitOnAPollKeepsTheHeldRequestAndAsksAgain() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.polls!! += ServerResult.Err(ServerError.RATE_LIMITED)
        backend.polls!! += ServerResult.Err(ServerError.fromReason("SOMETHING_NEW", ServerError.INTERNAL))
        backend.polls!! += done(4)
        holder.generateOnServer()

        assertEquals(listOf("req0"), backend.generated.map { it.requestId })
        assertEquals(3, backend.polled.size, "asked again after each, never dropped")
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
        assertEquals(1, saved.rows.value.size, "the recipe that was charged arrives")
    }

    @Test
    fun aLimitOnAPollPastTheDeadlineKeepsTheRequestForLater() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        repeat(80) { backend.polls!! += ServerResult.Err(ServerError.RATE_LIMITED) }
        holder.generateOnServer()

        assertEquals("req0", paidRepository.get().pendingGeneration?.requestId, "still held: it may yet be written")
        assertNull(holder.uiState.value.paid.writing)
    }

    @Test
    fun aRefusalOnTheFirstSendOfARequestTheServerNeverGotStillDropsIt() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        holder.consumeEvent()

        backend.polls!! += ServerResult.Err(ServerError.UNKNOWN_REQUEST)
        backend.generateReplies += ServerResult.Err(ServerError.USER_DAILY_LIMIT)
        holder.retryServer()
        assertEquals(RecipeEvent.ServerLimitToday, holder.uiState.value.event)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun anAppCheckRefusalWithRecipesHeldKeepsThemAndOnlyStopsBuying() = runUnconfined {
        withBalance(5)
        backend.attestation = Attestation.FAILED
        val holder = holder()
        holder.openPacks()
        holder.continuePurchase()

        val state = holder.uiState.value
        assertTrue(state.paidRouteAvailable, "what was paid for stays")
        assertEquals(5, state.paid.balance)
        assertEquals(BuySheet.StoreUnavailable, state.paid.sheet)
        assertTrue(store.bought.isEmpty())
    }

    @Test
    fun aBlockedBuyerKeepsTheirRecipesButCantBuy() = runUnconfined {
        withBalance(6)
        backend.balanceBlocked = true
        val holder = holder()
        holder.onAppResume()

        val paid = holder.uiState.value.paid
        assertTrue(paid.purchasesBlocked)
        assertTrue(paid.available)
        assertFalse(paid.canBuy)
        assertTrue(holder.uiState.value.canGenerateOnServer, "the balance stays usable")
        holder.openPacks()
        assertNull(holder.uiState.value.paid.sheet, "no pack sheet")
        assertTrue(paidRepository.get().purchasesBlocked, "kept with the balance")
        assertTrue(holder().uiState.value.paid.purchasesBlocked, "and after a restart")
    }

    @Test
    fun aPurchaseThatStillArrivesForABlockedBuyerIsVerified() = runUnconfined {
        account = FakeAccount(AccountKind.ANONYMOUS)
        paidRepository = InMemoryRecipePaidRepository(RecipePaidSnapshot(balance = 0, purchasesBlocked = true, paymentPending = true))
        val holder = holder()
        backend.verifyReplies += ServerResult.Ok(
            VerifyReply(VerifyStatus.GRANTED, 10, purchasesBlocked = kotlinx.serialization.json.JsonPrimitive(true))
        )
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("tok", listOf("recipes_10"), 1, pending = false))))

        assertEquals(listOf("tok"), backend.verified)
        assertEquals(10, holder.uiState.value.paid.balance)
        assertTrue(holder.uiState.value.paid.purchasesBlocked)
    }

    @Test
    fun theBlockIsLiftedWhenTheServerStopsSayingIt() = runUnconfined {
        withBalance(6)
        paidRepository = InMemoryRecipePaidRepository(RecipePaidSnapshot(balance = 6, purchasesBlocked = true))
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.DONE, recipe = sampleRecipe, balance = 5, charged = true))
        holder.generateOnServer()
        assertFalse(holder.uiState.value.paid.purchasesBlocked, "absent means not blocked")
    }

    @Test
    fun purchasesBlockedIsReadLeniently() {
        assertTrue(purchasesBlockedOf(kotlinx.serialization.json.JsonPrimitive(true)))
        assertFalse(purchasesBlockedOf(null))
        assertFalse(purchasesBlockedOf(kotlinx.serialization.json.JsonPrimitive(false)))
        assertFalse(purchasesBlockedOf(kotlinx.serialization.json.JsonPrimitive("true")))
        assertFalse(purchasesBlockedOf(kotlinx.serialization.json.JsonPrimitive(1)))
        assertFalse(purchasesBlockedOf(buildJsonObject { }))
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
    fun wireNamesKeepWhatTheServerAcceptsAndNothingElse() {
        assertEquals("Ben & Jerry's", cleanWireName("Ben & Jerry's"))
        assertEquals("Хлеб на закваске", cleanWireName("  Хлеб на закваске! "))
        assertEquals("semi-skimmed", cleanWireName("semi-skimmed"))
        assertNull(cleanWireName("🍞 ,."))
        assertEquals("abcdefghijklmnopqrstuvwxyzab", cleanWireName("abcdefghijklmnopqrstuvwxyzab cdefghijklmnopqrstuvwxyz"), "cut at a word end")
    }

    // --- Final contract: merge -------------------------------------------------------------

    @Test
    fun aNegativePhoneBalanceIsJoinedWithoutAskingAndTheDebtCostsAndBlockAreApplied() = runUnconfined {
        withBalance(-3)
        val holder = holder()
        account.mergeResult = ServerResult.Ok(MergeReply(balance = 35, moved = -3, costs = RecipeCosts(1, 3, 4), purchasesBlocked = true))
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = 38))
        holder.signIn(SignInProvider.GOOGLE)

        val paid = holder.uiState.value.paid
        assertNull(paid.merge, "nothing to ask: the debt has to move")
        assertEquals(1, account.merged)
        assertEquals(35, paid.balance)
        assertEquals(RecipeCosts(1, 3, 4), paid.costs)
        assertTrue(paid.purchasesBlocked)
        assertEquals(PaidEvent.Merged(-3), paid.event)
    }

    @Test
    fun aJoinThatMovedRecipesSaysHowMany() = runUnconfined {
        withBalance(12)
        val holder = holder()
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = 38))
        holder.signIn(SignInProvider.GOOGLE)
        account.mergeResult = ServerResult.Ok(MergeReply(balance = 50, moved = 12))
        holder.confirmMerge()
        assertEquals(PaidEvent.Merged(12), holder.uiState.value.paid.event)
        assertFalse(holder.uiState.value.paid.purchasesBlocked, "absent means not blocked")
    }

    @Test
    fun aBusyServerOnJoiningIsAskedAgainWithGrowingWaitsNotDropped() = runUnconfined {
        withBalance(0)
        val holder = holder()
        account.mergeQueue += ServerResult.Err(ServerError.OVERLOADED)
        account.mergeQueue += ServerResult.Err(ServerError.BUSY)
        account.mergeResult = ServerResult.Ok(MergeReply(38))
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = 38))
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(3, account.merged)
        assertEquals(listOf(3_000L, 6_000L), waits)
        assertEquals(0, account.cancelledMerges)
        assertEquals(38, holder.uiState.value.paid.balance)
    }

    @Test
    fun aJoinThatStaysBusyGivesUpAfterAWhileWithoutLoopingTightly() = runUnconfined {
        withBalance(0)
        val holder = holder()
        account.mergeResult = ServerResult.Err(ServerError.OVERLOADED)
        account.signInResults += SignInResult.NeedsMerge(FakeMerge(accountBalance = 38))
        holder.signIn(SignInProvider.GOOGLE)
        assertTrue(account.merged in 3..12, "asked a bounded number of times: ${account.merged}")
        assertTrue(waits.all { it >= 3_000L })
        assertEquals(1, account.cancelledMerges)
        assertEquals(PaidEvent.SignInFailed, holder.uiState.value.paid.event)
    }

    // --- Final contract: purchases ---------------------------------------------------------

    @Test
    fun aPurchaseFromTheWrongStoreIsDroppedBuyingHiddenAndNotSentAgain() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.verifyReplies += ServerResult.Err(ServerError.STORE_MISMATCH)
        val purchase = StorePurchase("tok", listOf("recipes_10"), 1, pending = false)
        store.updates.emit(StoreUpdate.Purchases(listOf(purchase)))

        val paid = holder.uiState.value.paid
        assertEquals(PurchaseRejection.WRONG_BUILD, paid.rejection)
        assertTrue(paid.storeRefused)
        assertFalse(paid.canBuy)
        assertEquals(0, paid.unconfirmedRecipes)
        assertTrue(paidRepository.get().unverified.isEmpty())
        assertEquals(5, paid.balance, "nothing granted")

        // Play still holds it (never consumed): a later resume doesn't send it again.
        store.held = listOf(purchase)
        now += 120_000
        holder.onAppResume()
        assertEquals(listOf("tok"), backend.verified)
    }

    @Test
    fun aPurchaseOfAnotherAccountIsKeptAndSentAgainOnlyWhenAnotherAccountSignsIn() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.verifyReplies += ServerResult.Err(ServerError.PURCHASE_ACCOUNT_MISMATCH)
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("tok", listOf("recipes_10"), 1, pending = false))))

        var paid = holder.uiState.value.paid
        assertEquals(10, paid.elsewhereRecipes)
        assertEquals(0, paid.unconfirmedRecipes, "not \"being confirmed\": it waits for its own account")
        assertNull(paid.rejection, "neither granted nor rejected")
        assertNull(paid.event)
        assertEquals(1, paidRepository.get().unverified.size, "kept")

        now += 120_000
        holder.onAppResume()
        assertEquals(listOf("tok"), backend.verified, "not sent again for the same account")

        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.GRANTED, 15))
        account.mutableState.value = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE), "a@b.c", "key-buyer")
        paid = holder.uiState.value.paid
        assertEquals(listOf("tok", "tok"), backend.verified, "sent again for the account now signed in")
        assertEquals(0, paid.elsewhereRecipes)
        assertEquals(15, paid.balance)
    }

    @Test
    fun aBusyServerOnAPurchaseIsAskedAgainAFewTimesThenLeftForLater() = runUnconfined {
        withBalance(0)
        val holder = holder()
        repeat(10) { backend.verifyReplies += ServerResult.Err(ServerError.OVERLOADED) }
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("tok", listOf("recipes_10"), 1, pending = false))))
        assertEquals(4, backend.verified.size, "the first try and three more")
        assertEquals(listOf(3_000L, 6_000L, 12_000L), waits)
        assertEquals(10, holder.uiState.value.paid.unconfirmedRecipes, "still kept")

        // Coming back to the app at once doesn't send it again; after a while it does.
        now += 5_000
        holder.onAppResume()
        assertEquals(4, backend.verified.size)
        now += 30_000
        backend.verifyReplies.clear()
        backend.verifyReplies += ServerResult.Ok(VerifyReply(VerifyStatus.GRANTED, 10))
        holder.onAppResume()
        assertEquals(5, backend.verified.size)
        assertEquals(10, holder.uiState.value.paid.balance)
    }

    // --- Final contract: busy server, polling ----------------------------------------------

    @Test
    fun aBusyServerOnAFirstSendIsAskedAgainWithTheSameIdAndShowsACalmState() = runUnconfined {
        withBalance(5)
        val holder = holder()
        val busySeen = mutableListOf<Boolean>()
        onWait = { busySeen += it?.uiState?.value?.paid?.serverBusy == true }
        backend.generateReplies += ServerResult.Err(ServerError.OVERLOADED)
        backend.generateReplies += ServerResult.Err(ServerError.OVERLOADED)
        backend.generateReplies += done(4)
        holder.generateOnServer()

        assertEquals(listOf("req0", "req0", "req0"), backend.generated.map { it.requestId })
        assertEquals(listOf(3_000L, 6_000L), waits)
        assertEquals(listOf(true, true), busySeen, "busy, trying again, while it waits")
        val state = holder.uiState.value
        assertIs<RecipeEvent.Written>(state.event)
        assertFalse(state.paid.serverBusy)
        assertNull(state.paid.issue)
        assertEquals(4, state.paid.balance)
    }

    @Test
    fun aServerTooBusyEverToTakeARequestDropsItSoItIsNeverStartedUnasked() = runUnconfined {
        withBalance(5)
        val holder = holder()
        repeat(100) { backend.generateReplies += ServerResult.Err(ServerError.OVERLOADED) }
        holder.generateOnServer()

        val state = holder.uiState.value
        assertEquals(RecipeEvent.ServerFailedLater, state.event, "try later, nothing taken")
        assertNull(state.paid.issue)
        assertNull(state.paid.writing)
        assertFalse(state.paid.serverBusy)
        assertNull(paidRepository.get().pendingGeneration, "the server holds nothing for it: not kept")
        assertTrue(backend.generated.size <= 10, "backed off: ${backend.generated.size} calls")
        assertTrue(waits.all { it in 3_000L..30_000L })

        val calls = backend.generated.size
        now += 120_000
        holder.onAppResume()
        assertEquals(calls, backend.generated.size, "nothing is started in the background")
    }

    @Test
    fun aHeldRequestTheServerStaysTooBusyToAnswerForIsKeptAndFetchedOnTryAgain() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        repeat(100) { backend.polls!! += ServerResult.Err(ServerError.OVERLOADED) }
        holder.generateOnServer()

        val state = holder.uiState.value
        assertEquals(ServerIssue.BUSY, state.paid.issue)
        assertNull(state.event, "not an error")
        assertEquals("req0", paidRepository.get().pendingGeneration?.requestId, "the server holds it: kept with its id")

        backend.polls!!.clear()
        backend.polls!! += done(4)
        holder.retryServer()
        assertEquals(listOf("req0"), backend.generated.map { it.requestId }, "fetched, never started again")
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
        assertNull(holder.uiState.value.paid.issue)
    }

    @Test
    fun anEarlierRequestTooBusyToBeTakenIsDroppedAndTheNewOneNotSent() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        holder.updateConfig { it.copy(servings = 4) }

        backend.polls!! += ServerResult.Err(ServerError.UNKNOWN_REQUEST)
        repeat(100) { backend.generateReplies += ServerResult.Err(ServerError.OVERLOADED) }
        holder.generateOnServer()
        assertTrue(backend.generated.all { it.requestId == "req0" }, "the new ask isn't sent behind a busy server")
        assertNull(paidRepository.get().pendingGeneration, "the old ask, never taken, is dropped")
        assertEquals(RecipeEvent.ServerFailedLater, holder.uiState.value.event)
        assertNull(holder.uiState.value.paid.writing)
    }

    @Test
    fun aBusyAnswerWhileAskingAboutAHeldRequestKeepsItAndBacksOff() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.polls!! += ServerResult.Err(ServerError.OVERLOADED)
        backend.polls!! += ServerResult.Err(ServerError.OVERLOADED)
        backend.polls!! += done(4)
        holder.generateOnServer()
        assertEquals(listOf(3_000L, 3_000L, 6_000L), waits)
        assertEquals(listOf("req0"), backend.generated.map { it.requestId }, "only asked about, never started again")
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
    }

    @Test
    fun pollsAreNeverCloserThanThreeSecondsAndSlowDownForALongMenu() = runUnconfined {
        withBalance(10)
        backend.polls = ArrayDeque()
        val holder = holder()
        holder.setMode(RecipeMode.MENU)
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 5, charged = true))
        repeat(20) { backend.polls!! += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 5, charged = true)) }
        backend.polls!! += done(5, recipe = null, menu = sampleMenu(3))
        holder.generateOnServer()
        assertTrue(waits.all { it >= POLL_MILLIS })
        assertEquals(SLOW_POLL_MILLIS, waits.last())
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
    }

    @Test
    fun aBlockOrNewCostsOnAPollApplyAtOnceButTheHeldBalanceDoesnt() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        val costs = buildJsonObject {
            put("recipe", 2)
            put("menu2", 4)
            put("menu3", 5)
        }
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 3, charged = true, costs = costs, purchasesBlocked = JsonPrimitive(true)))
        var seen: PaidUiState? = null
        onWait = { seen = it?.uiState?.value?.paid }
        backend.polls!! += done(3)
        holder.generateOnServer()
        assertEquals(true, seen?.purchasesBlocked)
        assertEquals(2, seen?.costs?.recipe)
        assertEquals(5, seen?.balance, "the held balance isn't shown")
        assertFalse(holder.uiState.value.paid.purchasesBlocked, "the final reply has no block: lifted")
    }

    @Test
    fun anEarlierRequestThatFailedAndWasChargedIsAnnounced() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        holder.updateConfig { it.copy(servings = 4) }

        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.FAILED, balance = 4, charged = true))
        backend.generateReplies += done(3)
        holder.generateOnServer()
        assertEquals(PaidEvent.EarlierRefused(1), holder.uiState.value.paid.event)
        assertIs<RecipeEvent.Written>(holder.uiState.value.event)
    }

    @Test
    fun anEarlierRequestThatStaysBusyHoldsTheNewOneBackAndSaysBusy() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()
        holder.updateConfig { it.copy(servings = 4) }
        repeat(100) { backend.polls!! += ServerResult.Err(ServerError.OVERLOADED) }
        holder.generateOnServer()
        assertEquals(listOf("req0"), backend.generated.map { it.requestId }, "the new one isn't sent past the earlier one")
        assertEquals(ServerIssue.BUSY, holder.uiState.value.paid.issue)
        assertEquals("req0", paidRepository.get().pendingGeneration?.requestId)
    }

    @Test
    fun backoffDoublesFromThreeSecondsUpToThirty() {
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L), (0..5).map(::backoffMillis))
    }

    @Test
    fun wireNamesKeepEveryScriptAndFoldFractions() {
        assertEquals("किवी", cleanWireName("किवी"), "every script is sent as written")
        assertEquals("1/2 lemon", cleanWireName("½ lemon"))
        assertNull(cleanWireName("🍋"))
    }

    @Test
    fun itemsAndAllergensInOtherScriptsAreSentAsWritten() = runUnconfined {
        withBalance(5)
        pantry.value = pantry.value.copy(
            allRows = listOf(row("feta", -1, "Φέτα ΠΟΠ"), row("tofu", 0, "豆腐"), row("milk", 1, "חלב 3%")),
            settings = AppSettings(allergens = setOf("φιστίκια", "花生"))
        )
        val holder = holder()
        backend.generateReplies += done(4)
        holder.generateOnServer()

        assertEquals(listOf("Φέτα ΠΟΠ", "豆腐", "חלב 3%"), backend.generated.single().ingredients.map { it.name })
        assertEquals(setOf("φιστίκια", "花生"), backend.generated.single().allergens.toSet())
    }

    // --- Refused names -------------------------------------------------------------------------

    private fun refusedIngredient(index: Int, rule: String = "INSTRUCTION") =
        ServerResult.Err(ServerError.INVALID_REQUEST, RefusedName(RefusedList.INGREDIENT, index, rule))

    @Test
    fun aRefusedIngredientIsLeftOutOnceWithANewIdAndThePersonIsTold() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += refusedIngredient(1)
        backend.generateReplies += done(4)
        holder.generateOnServer()

        assertEquals(listOf("req0", "req1"), backend.generated.map { it.requestId }, "a changed request needs a new id")
        assertEquals(listOf("Bread", "Greek yoghurt 2%"), backend.generated[1].ingredients.map { it.name })
        assertEquals(backend.generated[0].allergens, backend.generated[1].allergens)
        val event = assertIs<RecipeEvent.Written>(holder.uiState.value.event)
        assertEquals(listOf("Spinach"), event.leftOut)
        assertEquals(4, holder.uiState.value.paid.balance)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun everyRowOfARefusedNameIsLeftOut() = runUnconfined {
        withBalance(5)
        pantry.value = pantry.value.copy(allRows = listOf(row("bread", -1), row("milk1", 0, "Milk"), row("milk2", 1, "milk")))
        val holder = holder()
        backend.generateReplies += refusedIngredient(1)
        backend.generateReplies += done(4)
        holder.generateOnServer()

        assertEquals(listOf("Bread"), backend.generated[1].ingredients.map { it.name }, "the other carton would be refused too")
        assertEquals(listOf("Milk"), assertIs<RecipeEvent.Written>(holder.uiState.value.event).leftOut)
    }

    @Test
    fun aSecondRefusalIsNotRetriedAgain() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += refusedIngredient(0)
        backend.generateReplies += refusedIngredient(0)
        holder.generateOnServer()

        assertEquals(2, backend.generated.size, "one retry, never a loop")
        assertEquals(RecipeEvent.IngredientNotSent("Spinach"), holder.uiState.value.event)
        assertNull(holder.uiState.value.paid.writing)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun aRefusedNameIsLeftOutOfLaterRequestsWithoutAskingAgain() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += refusedIngredient(0)
        backend.generateReplies += done(4)
        holder.generateOnServer()
        holder.consumeEvent()

        backend.generateReplies += done(3)
        holder.generateOnServer()
        assertEquals(3, backend.generated.size)
        assertEquals(listOf("Spinach", "Greek yoghurt 2%"), backend.generated[2].ingredients.map { it.name })
        assertEquals(listOf("Bread"), assertIs<RecipeEvent.Written>(holder.uiState.value.event).leftOut)
    }

    @Test
    fun theOnlyItemRefusedIsNotRetried() = runUnconfined {
        withBalance(5)
        pantry.value = pantry.value.copy(allRows = listOf(row("bread", -1)))
        val holder = holder()
        backend.generateReplies += refusedIngredient(0)
        holder.generateOnServer()

        assertEquals(1, backend.generated.size)
        assertEquals(RecipeEvent.IngredientNotSent("Bread"), holder.uiState.value.event)

        holder.consumeEvent()
        holder.generateOnServer()
        assertEquals(1, backend.generated.size, "known refused: nothing left to send")
        assertEquals(RecipeEvent.IngredientNotSent("Bread"), holder.uiState.value.event)
    }

    @Test
    fun theRetryIsKeptAsPendingWhileItIsOut() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += refusedIngredient(1)
        backend.generateReplies += ServerResult.Err(ServerError.OFFLINE)
        holder.generateOnServer()

        val pending = paidRepository.get().pendingGeneration
        assertEquals("req1", pending?.requestId)
        assertEquals(listOf("Bread", "Greek yoghurt 2%"), pending?.ingredients?.map { it.name })
    }

    /** An earlier request still waiting for its answer, different from what the form asks now. */
    private fun heldBread() = GenerateRequest(
        requestId = "held",
        kind = WireKind.RECIPE,
        config = RecipeConfig().copy(language = "en"),
        ingredients = listOf(WireIngredient("Bread", "2026-11-03", 2), WireIngredient("Milk", "2026-11-06", 1)),
        allergens = listOf("peanuts"),
        today = "2026-11-04"
    )

    @Test
    fun aRefusalWhileNobodyWaitsIsNotRetried() = runUnconfined {
        withBalance(5)
        paidRepository = InMemoryRecipePaidRepository(RecipePaidSnapshot(balance = 5, pendingGeneration = heldBread(), pendingSinceMillis = now))
        // The recovery on opening the tab finds the server never got it, and sends it.
        backend.polls = ArrayDeque(listOf(ServerResult.Err(ServerError.UNKNOWN_REQUEST)))
        backend.generateReplies += refusedIngredient(0)
        holder()

        assertEquals(listOf("held"), backend.generated.map { it.requestId }, "no new generation started in the background")
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun aNameRefusedWhileSettlingAnEarlierRequestIsLeftOutOfTheNewOne() = runUnconfined {
        withBalance(5)
        paidRepository = InMemoryRecipePaidRepository(RecipePaidSnapshot(balance = 5, pendingGeneration = heldBread(), pendingSinceMillis = now))
        // The recovery on opening the tab can't reach the server; Generate then settles the
        // earlier request first, and that is where Bread is refused.
        backend.polls = ArrayDeque(listOf(ServerResult.Err(ServerError.OFFLINE), ServerResult.Err(ServerError.UNKNOWN_REQUEST)))
        backend.generateReplies += refusedIngredient(0)
        backend.generateReplies += done(4)
        val holder = holder()
        assertTrue(backend.generated.isEmpty())
        holder.generateOnServer()

        assertEquals(2, backend.generated.size)
        assertEquals(listOf("Spinach", "Greek yoghurt 2%"), backend.generated[1].ingredients.map { it.name })
        assertEquals(listOf("Bread"), assertIs<RecipeEvent.Written>(holder.uiState.value.event).leftOut)
    }

    @Test
    fun aRefusedAllergenIsNeverDroppedAndStopsTheRequest() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.INVALID_REQUEST, RefusedName(RefusedList.ALLERGEN, 0, "INSTRUCTION"))
        holder.generateOnServer()

        assertEquals(1, backend.generated.size, "never sent again without it")
        assertEquals(RecipeEvent.AllergenNotSent("peanuts"), holder.uiState.value.event)
        assertNull(holder.uiState.value.paid.writing)
        assertEquals(5, holder.uiState.value.paid.balance)
        assertNull(paidRepository.get().pendingGeneration)

        holder.consumeEvent()
        holder.generateOnServer()
        assertEquals(1, backend.generated.size, "known refused: not sent at all")
        assertEquals(RecipeEvent.AllergenNotSent("peanuts"), holder.uiState.value.event)
    }

    @Test
    fun moreAllergensThanTheServerTakesStopTheRequestRatherThanDropAny() = runUnconfined {
        withBalance(5)
        val many = (1..MAX_REQUEST_ALLERGENS + 1).map { "allergen $it" }.toSet()
        pantry.value = pantry.value.copy(settings = AppSettings(allergens = many))
        val holder = holder()
        holder.generateOnServer()

        assertTrue(backend.generated.isEmpty(), "never sent with one left out")
        assertEquals(RecipeEvent.TooManyAllergens(MAX_REQUEST_ALLERGENS + 1, MAX_REQUEST_ALLERGENS), holder.uiState.value.event)
        assertNull(holder.uiState.value.paid.writing)
        assertEquals(5, holder.uiState.value.paid.balance)
        assertNull(paidRepository.get().pendingGeneration)
    }

    @Test
    fun allergensThatCleanToTheSameNameAreSentAndCountedOnce() = runUnconfined {
        withBalance(5)
        val many = (1..MAX_REQUEST_ALLERGENS).map { "allergen $it" }.toSet()
        // A fullwidth copy and one with a zero-width space clean to names already in the list.
        pantry.value = pantry.value.copy(settings = AppSettings(allergens = many + "\uFF41llergen 1" + "aller\u200Bgen 2"))
        val holder = holder()
        holder.generateOnServer()

        assertEquals(1, backend.generated.size, "not refused as too many")
        assertEquals(many, backend.generated[0].allergens.toSet())
        assertEquals(MAX_REQUEST_ALLERGENS, backend.generated[0].allergens.size)
    }

    @Test
    fun exactlyAsManyAllergensAsTheServerTakesAreAllSent() = runUnconfined {
        withBalance(5)
        val many = (1..MAX_REQUEST_ALLERGENS).map { "allergen $it" }.toSet()
        pantry.value = pantry.value.copy(settings = AppSettings(allergens = many))
        val holder = holder()
        holder.generateOnServer()

        assertEquals(1, backend.generated.size)
        assertEquals(many, backend.generated[0].allergens.toSet())
    }

    @Test
    fun anAllergenCleaningWouldDropOrShortenIsNeverSentWithout() = runUnconfined {
        withBalance(5)
        for (allergen in listOf("Schalenfruchtunverträglichkeit", "Жq", "one two three four five six seven eight nine")) {
            pantry.value = pantry.value.copy(settings = AppSettings(allergens = setOf("milk", allergen)))
            val holder = holder()
            holder.generateOnServer()

            assertTrue(backend.generated.isEmpty(), allergen)
            assertEquals(RecipeEvent.AllergenNotSent(allergen), holder.uiState.value.event, allergen)
        }
    }

    @Test
    fun anInvalidRequestWithoutAFieldIsNotRetried() = runUnconfined {
        withBalance(5)
        val holder = holder()
        backend.generateReplies += ServerResult.Err(ServerError.INVALID_REQUEST)
        holder.generateOnServer()
        assertEquals(1, backend.generated.size)
        assertEquals(RecipeEvent.ServerFailed, holder.uiState.value.event)
    }

    @Test
    fun refusedFieldsAreReadFromTheContractsDetails() {
        assertEquals(RefusedName(RefusedList.INGREDIENT, 3, "SCRIPT"), refusedNameOf("ingredients[3].name", "SCRIPT"))
        assertEquals(RefusedName(RefusedList.ALLERGEN, 0, "INSTRUCTION"), refusedNameOf("allergens[0]", "INSTRUCTION"))
        assertNull(refusedNameOf("config.servings", null))
        assertNull(refusedNameOf(null, "SCRIPT"))
        assertNull(refusedNameOf("ingredients[x].name", "SCRIPT"))
    }

    @Test
    fun aPurchaseRefusedAsAnotherAccountsWithNoUserKeyIsStillShownAsElsewhere() = runUnconfined {
        withBalance(5)
        account.mutableState.value = AccountState(AccountKind.ANONYMOUS)
        val holder = holder()
        backend.verifyReplies += ServerResult.Err(ServerError.PURCHASE_ACCOUNT_MISMATCH)
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("tok", listOf("recipes_10"), 1, pending = false))))
        assertEquals(10, holder.uiState.value.paid.elsewhereRecipes)
        assertEquals(0, holder.uiState.value.paid.unconfirmedRecipes)
        now += 120_000
        holder.onAppResume()
        assertEquals(listOf("tok"), backend.verified, "not sent again")
    }

    @Test
    fun aRateLimitWhileAskingAboutAHeldRequestWaitsAMinute() = runUnconfined {
        withBalance(5)
        backend.polls = ArrayDeque()
        val holder = holder()
        backend.generateReplies += ServerResult.Ok(GenerateReply(GenerateStatus.IN_PROGRESS, balance = 4, charged = true))
        backend.polls!! += ServerResult.Err(ServerError.RATE_LIMITED)
        backend.polls!! += done(4)
        holder.generateOnServer()
        assertEquals(listOf(POLL_MILLIS, RATE_LIMIT_WAIT_MILLIS), waits)
    }

    @Test
    fun theClientsOwnNamesAreNeverReadAsServerReasons() {
        for (name in listOf("OVERLOADED", "OFFLINE", "UNAUTHENTICATED", "CALLABLE_MISSING", "UNKNOWN")) {
            assertEquals(ServerError.UNKNOWN, ServerError.fromReason(name, ServerError.INTERNAL), name)
        }
        assertEquals(ServerError.STORE_MISMATCH, ServerError.fromReason("STORE_MISMATCH", ServerError.INTERNAL))
    }
}
