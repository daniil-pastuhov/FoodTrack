package com.foodtracker.recipe

import com.foodtracker.model.AppSettings
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Free recipes for signing in, through the Recipe tab's state holder: when the offer is asked,
 *  what shows, and every way a claim can end. */
@OptIn(ExperimentalCoroutinesApi::class)
class WelcomePaidTest {

    private class Config : RecipeConfigRepository {
        override fun get(): RecipeConfig = RecipeConfig()
        override fun save(config: RecipeConfig) = Unit
        override fun getOptions(): RecipeOptions = RecipeOptions(introSeen = true)
        override fun saveOptions(options: RecipeOptions) = Unit
        override fun getTaste(): TasteProfile = TasteProfile()
        override fun saveTaste(taste: TasteProfile) = Unit
        override fun getTasteQuiz(language: String): String? = null
        override fun saveTasteQuiz(language: String, json: String) = Unit
        override fun getTasteSeen(): List<String> = emptyList()
        override fun saveTasteSeen(seen: List<String>) = Unit
    }

    private object Saved : SavedRecipeRepository {
        override fun getAllFlow(): Flow<List<SavedRecipe>> = MutableStateFlow(emptyList())
        override suspend fun save(recipe: SavedRecipe) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun clearAll() = Unit
    }

    private object NoModel : OnDeviceRecipeModel {
        override suspend fun availability(): OnDeviceAvailability = OnDeviceAvailability.UNSUPPORTED
        override fun supportsLanguage(languageCode: String): Boolean = false
        override suspend fun download(): Boolean = false
        override suspend fun generate(prompt: String): String = error("not here")
    }

    private class Backend : RecipeBackend {
        override val isAvailable: Boolean = true
        var offer: ServerResult<Int> = ServerResult.Ok(3)
        var offerCalls = 0
        val claims = ArrayDeque<ServerResult<WelcomeClaimReply>>()
        var claimCalls = 0
        var balance = BalanceReply(0)
        var balanceCalls = 0
        override suspend fun getWelcomeOffer(): ServerResult<Int> {
            offerCalls++
            return offer
        }
        /** Holds the claim's answer until completed, to order it against a purchase's. */
        var claimGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun claimWelcome(): ServerResult<WelcomeClaimReply> {
            claimCalls++
            val reply = claims.removeFirst()
            claimGate?.await()
            return reply
        }
        override suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply> = error("not here")
        var verifyBalance = 0
        var verifyGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply> {
            verifyGate?.await()
            return ServerResult.Ok(VerifyReply(VerifyStatus.GRANTED, balance = verifyBalance))
        }
        override suspend fun getBalance(): ServerResult<BalanceReply> {
            balanceCalls++
            return ServerResult.Ok(balance)
        }
        override suspend fun deleteAccount(): ServerResult<DeleteReply> = ServerResult.Ok(DeleteReply())
    }

    private class Store(var packs: List<StorePack>? = listOf(StorePack("recipes_10", 10, "£1.99", 1_990_000, "GBP"))) : RecipeStore {
        override val isSupported: Boolean = true
        override val updates = MutableSharedFlow<StoreUpdate>(extraBufferCapacity = 8)
        val bought = mutableListOf<String>()
        override suspend fun packs(): List<StorePack>? = packs
        override suspend fun buy(productId: String): Boolean {
            bought += productId
            return true
        }
        override suspend fun purchases(): List<StorePurchase>? = emptyList()
    }

    private class PendingAccount(override val accountBalance: Int?) : PendingMerge

    private class Account(kind: AccountKind = AccountKind.NONE) : RecipeAccount {
        val mutable = MutableStateFlow(stateOf(kind))
        override val state: StateFlow<AccountState> = mutable
        override val providers: Set<SignInProvider> = setOf(SignInProvider.GOOGLE)
        val signInResults = ArrayDeque<SignInResult>()
        var signIns = 0
        var usersCreated = 0
        var mergeResult: ServerResult<MergeReply> = ServerResult.Ok(MergeReply(5, moved = 5))
        override suspend fun ensureUser(): Boolean {
            usersCreated++
            if (mutable.value.kind == AccountKind.NONE) mutable.value = stateOf(AccountKind.ANONYMOUS)
            return true
        }
        override suspend fun signIn(provider: SignInProvider): SignInResult {
            signIns++
            val result = signInResults.removeFirst()
            if (result == SignInResult.SignedIn) mutable.value = stateOf(AccountKind.SIGNED_IN)
            return result
        }
        override suspend fun completeMerge(merge: PendingMerge): ServerResult<MergeReply> {
            if (mergeResult is ServerResult.Ok) mutable.value = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE), "a@b.c", "key-merged")
            return mergeResult
        }
        override suspend fun cancelMerge(merge: PendingMerge) = Unit
        override fun needsFreshSignIn(): Boolean = false
        override suspend fun reauthenticateForDeletion(): Boolean = true
        override suspend fun signOut() {
            mutable.value = stateOf(AccountKind.NONE)
        }

        companion object {
            fun stateOf(kind: AccountKind) = when (kind) {
                AccountKind.NONE -> AccountState()
                AccountKind.ANONYMOUS -> AccountState(AccountKind.ANONYMOUS, purchaseKey = "key-anon")
                AccountKind.SIGNED_IN -> AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE), "a@b.c", "key-signed-in")
            }
        }
    }

    private val today = LocalDate(2026, 11, 16)
    private val pantry = MutableStateFlow(
        PantryUiState(
            isLoading = false,
            allRows = listOf(
                PantryRow(PantryItem(id = "bread", name = "Bread", expiryDate = today, addedDate = today), 0, ExpiryStatus.compute(0, 3), null, "bread")
            ),
            settings = AppSettings()
        )
    )
    private val backend = Backend()
    private val store = Store()
    private var account = Account()
    private var repository = InMemoryRecipePaidRepository()
    private var now = 100L * WELCOME_ANSWER_MAX_AGE_MILLIS

    private fun TestScope.holder() = RecipeStateHolder(
        pantry = pantry,
        onDeviceModel = NoModel,
        savedRecipeRepository = Saved,
        configRepository = Config(),
        backend = backend,
        store = store,
        account = account,
        paidRepository = repository,
        coroutineScope = backgroundScope,
        nowMillis = { now },
        today = { today },
        pollDelay = { now += it }
    )

    private fun runUnconfined(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    private fun claimed(status: WelcomeStatus, granted: Int = 0, balance: Int = granted, welcome: Int = 3) =
        ServerResult.Ok(WelcomeClaimReply(status, granted, BalanceReply(balance, acceptsWelcome = true, welcomeRecipes = welcome)))

    private val RecipeStateHolder.paid: PaidUiState get() = uiState.value.paid

    /** The Recipe tab shown, as RecipeScreen's resume does it. */
    private fun RecipeStateHolder.showTab() {
        refresh()
        onRecipeTabShown()
    }

    // --- Only when the Recipe tab is shown ---------------------------------------------------

    @Test
    fun launchingAndResumingTheAppNeverAsksForTheOffer() = runUnconfined {
        val holder = holder()
        // What a launch does without the tab: the holder's own refresh, the app's resume.
        holder.refresh()
        holder.onAppResume()
        now += WELCOME_REFRESH_MILLIS
        holder.onAppResume()
        assertEquals(0, backend.offerCalls)
        assertNull(repository.get().welcomeAskedAtMillis)
        holder.showTab()
        assertEquals(1, backend.offerCalls)
    }

    @Test
    fun aColdStartWithinFifteenMinutesOfAFailedAskDoesntAsk() = runUnconfined {
        backend.offer = ServerResult.Err(ServerError.INTERNAL)
        holder().showTab()
        assertEquals(1, backend.offerCalls)
        assertEquals(now, repository.get().welcomeAskedAtMillis)
        // A new launch with what was kept.
        now += WELCOME_REFRESH_MILLIS - 1
        holder().showTab()
        assertEquals(1, backend.offerCalls)
        now += 1
        holder().showTab()
        assertEquals(2, backend.offerCalls)
    }

    @Test
    fun aClockMovedBackWithFailingCallsAsksOnceThenWaits() = runUnconfined {
        // An answer dated in the future (the clock was set back since), and a server that fails.
        repository = InMemoryRecipePaidRepository(RecipePaidSnapshot(welcomeOffer = WelcomeAnswer(3, now + WELCOME_ANSWER_MAX_AGE_MILLIS)))
        backend.offer = ServerResult.Err(ServerError.INTERNAL)
        val holder = holder()
        holder.showTab()
        assertEquals(1, backend.offerCalls)
        now += 60_000
        holder.showTab()
        assertEquals(1, backend.offerCalls)
        now += WELCOME_REFRESH_MILLIS
        holder.showTab()
        assertEquals(2, backend.offerCalls)
    }

    // --- Asking for the offer ----------------------------------------------------------------

    @Test
    fun theTabBeingShownAsksForTheOfferAndTheHintNamesIt() = runUnconfined {
        val holder = holder()
        holder.showTab()
        assertEquals(1, backend.offerCalls)
        assertEquals(WelcomeHint(3, WelcomeKind.SIGN_IN), holder.paid.welcome)
        assertEquals(WelcomeAnswer(3, now), repository.get().welcomeOffer)
        // It creates no account to ask.
        assertEquals(0, account.usersCreated)
        assertEquals(AccountKind.NONE, account.state.value.kind)
    }

    @Test
    fun notMoreOftenThanEveryFifteenMinutes() = runUnconfined {
        val holder = holder()
        holder.showTab()
        now += WELCOME_REFRESH_MILLIS - 1
        holder.showTab()
        assertEquals(1, backend.offerCalls)
        now += 1
        holder.showTab()
        assertEquals(2, backend.offerCalls)
    }

    @Test
    fun aCachedAnswerUnderADayOldShowsAtOnceAndAnOlderOneIsNone() = runUnconfined {
        repository = InMemoryRecipePaidRepository(RecipePaidSnapshot(welcomeOffer = WelcomeAnswer(4, now - 60_000)))
        backend.offer = ServerResult.Err(ServerError.OFFLINE)
        val holder = holder()
        holder.showTab()
        assertEquals(4, holder.paid.welcome?.recipes)
        // A day after it was given, with the server unreachable: no hint at all.
        now += WELCOME_ANSWER_MAX_AGE_MILLIS
        holder.showTab()
        assertNull(holder.paid.welcome)
    }

    @Test
    fun aFailedCallKeepsTheLastAnswerAndSaysNothing() = runUnconfined {
        val holder = holder()
        holder.showTab()
        backend.offer = ServerResult.Err(ServerError.INTERNAL)
        now += WELCOME_REFRESH_MILLIS
        holder.showTab()
        assertEquals(2, backend.offerCalls)
        assertEquals(3, holder.paid.welcome?.recipes)
        assertNull(holder.paid.event)
        assertNull(holder.uiState.value.event)
    }

    @Test
    fun anOlderServerWithoutTheCallReadsAsZero() = runUnconfined {
        backend.offer = ServerResult.Err(ServerError.CALLABLE_MISSING)
        val holder = holder()
        holder.showTab()
        assertNull(holder.paid.welcome)
        assertEquals(0, repository.get().welcomeOffer?.recipes)
    }

    @Test
    fun anInstallThatCantBuyNeverAsks() = runUnconfined {
        store.packs = emptyList()
        val holder = holder()
        holder.showTab()
        assertEquals(0, backend.offerCalls)
        assertNull(holder.paid.welcome)
    }

    @Test
    fun signedInTheNumberComesBesideTheBalanceWithoutTheOfferCall() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.showTab()
        assertEquals(0, backend.offerCalls)
        assertEquals(WelcomeHint(3, WelcomeKind.CLAIM), holder.paid.welcome)
    }

    @Test
    fun signedInWithAnOlderServerThereIsNoHint() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0)
        val holder = holder()
        holder.showTab()
        assertNull(holder.paid.welcome)
    }

    @Test
    fun theHintIsHiddenForAnAccountBlockedFromBuying() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0, purchasesBlocked = true, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.showTab()
        assertNull(holder.paid.welcome)
    }

    // --- Signed out: the sheet, the sign-in, the claim ---------------------------------------

    @Test
    fun theSheetAsksAgainFirstSoItsNumberIsCurrent() = runUnconfined {
        val holder = holder()
        holder.showTab()
        backend.offer = ServerResult.Ok(5)
        holder.openWelcome()
        assertEquals(2, backend.offerCalls)
        assertEquals(WelcomeSheet(5), holder.paid.welcomeSheet)
    }

    @Test
    fun aSheetWhoseRefreshSaysZeroIsAPlainSignInThatClaimsNothing() = runUnconfined {
        val holder = holder()
        holder.showTab()
        backend.offer = ServerResult.Ok(0)
        holder.openWelcome()
        assertEquals(WelcomeSheet(0), holder.paid.welcomeSheet)
        account.signInResults += SignInResult.SignedIn
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(0, backend.claimCalls)
        assertNull(holder.paid.welcomeSheet)
    }

    @Test
    fun grantedAddsTheRecipesSaysSoAndTheHintGoes() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3)
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(1, backend.claimCalls)
        assertEquals(3, holder.paid.balance)
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Granted(3)), holder.paid.event)
        assertNull(holder.paid.welcomeSheet)
        assertNull(holder.paid.welcome)
        assertTrue(holder.paid.welcomeClaimed)
        assertEquals(listOf("key-signed-in"), repository.get().welcomeClaimed)
        // The server's action is now the primary, as after buying.
        assertEquals(RecipeAction.SERVER, holder.uiState.value.actions.primary)
    }

    @Test
    fun alreadyOpensTheDialogAndTheHintNeverComesBackForThisAccount() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.ALREADY)
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertTrue(holder.paid.welcomeAlready)
        assertNull(holder.paid.event)
        assertNull(holder.paid.welcome)
        holder.dismissWelcomeAlready()
        assertFalse(holder.paid.welcomeAlready)
        // A later tap can't claim again for it.
        holder.claimWelcome()
        assertEquals(1, backend.claimCalls)
    }

    @Test
    fun offSaysSoAndThatThePersonIsSignedIn() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.OFF, welcome = 0)
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Off(signedInNow = true)), holder.paid.event)
        assertFalse(holder.paid.welcomeClaimed)
        // At 0 beside the balance, the hint leaves the form.
        assertNull(holder.paid.welcome)
    }

    @Test
    fun aClaimNotReachedOffersTryAgainWhichNeverGrantsTwice() = runUnconfined {
        backend.balance = BalanceReply(0, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.SignedIn
        backend.claims += ServerResult.Err(ServerError.OFFLINE)
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.NotReached), holder.paid.event)
        // Signed in and not claimed: the offer now reads "Get 3 free recipes".
        assertEquals(WelcomeHint(3, WelcomeKind.CLAIM), holder.paid.welcome)
        holder.consumePaidEvent()
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3)
        holder.claimWelcome()
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Granted(3)), holder.paid.event)
        assertEquals(2, backend.claimCalls)
    }

    @Test
    fun aCancelledSignInChangesNothingAndAFailedOneSaysSo() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.Cancelled
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(WelcomeSheet(3), holder.paid.welcomeSheet)
        assertNull(holder.paid.event)
        account.signInResults += SignInResult.Failed
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(WelcomeSheet(3, signInFailed = true), holder.paid.welcomeSheet)
        assertEquals(PaidEvent.SignInFailed, holder.paid.event)
        assertEquals(0, backend.claimCalls)
    }

    @Test
    fun recipesOnThisDeviceAreJoinedFirstThenClaimed() = runUnconfined {
        account = Account(AccountKind.ANONYMOUS)
        repository = InMemoryRecipePaidRepository(RecipePaidSnapshot(balance = 0))
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.NeedsMerge(PendingAccount(2))
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3, balance = 8)
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(1, backend.claimCalls)
        assertEquals(8, holder.paid.balance)
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Granted(3)), holder.paid.event)
    }

    // --- Any sign-in while the offer shows ----------------------------------------------------

    @Test
    fun aSignInFromRecipeSettingsWhileTheOfferShowsClaims() = runUnconfined {
        val holder = holder()
        holder.showTab()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3)
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(1, backend.claimCalls)
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Granted(3)), holder.paid.event)
    }

    @Test
    fun aSignInWithoutAnOfferClaimsNothing() = runUnconfined {
        backend.offer = ServerResult.Ok(0)
        val holder = holder()
        holder.showTab()
        account.signInResults += SignInResult.SignedIn
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(0, backend.claimCalls)
    }

    @Test
    fun theFirstPurchasesSignInClaimsQuietlyAndTheStoreStillOpens() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openPacks()
        holder.continuePurchase()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.ALREADY)
        holder.buyWithAccount(SignInProvider.GOOGLE)
        assertEquals(1, backend.claimCalls)
        assertEquals(listOf("recipes_10"), store.bought)
        // Buying anyway: "already had them" isn't worth a dialog over the store's sheet.
        assertFalse(holder.paid.welcomeAlready)
        assertTrue(holder.paid.welcomeClaimed)
    }

    @Test
    fun signingInAgainWithAnAccountThatHadThemSaysSo() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3)
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        holder.consumePaidEvent()
        holder.signOut()
        // The offer shows again signed out; the same account signs in from it.
        holder.openWelcome()
        account.signInResults += SignInResult.SignedIn
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertEquals(1, backend.claimCalls)
        assertTrue(holder.paid.welcomeAlready)
        assertNull(holder.paid.welcomeSheet)
    }

    @Test
    fun aRecipeSettingsSignInWithAnAccountThatHadThemSaysSoToo() = runUnconfined {
        repository = InMemoryRecipePaidRepository(RecipePaidSnapshot(welcomeClaimed = listOf("key-signed-in")))
        val holder = holder()
        holder.showTab()
        account.signInResults += SignInResult.SignedIn
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(0, backend.claimCalls)
        assertTrue(holder.paid.welcomeAlready)
    }

    @Test
    fun aClaimAnsweredAfterThePurchaseNeverLowersTheCount() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openPacks()
        holder.continuePurchase()
        account.signInResults += SignInResult.SignedIn
        // The claim's balance was worked out before the purchase; it arrives last.
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3, balance = 3)
        backend.claimGate = kotlinx.coroutines.CompletableDeferred()
        backend.verifyBalance = 13
        backend.balance = BalanceReply(13, acceptsWelcome = true, welcomeRecipes = 3)
        holder.buyWithAccount(SignInProvider.GOOGLE)
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("t1", listOf("recipes_10"), 1, pending = false))))
        assertEquals(13, holder.paid.balance)
        backend.claimGate!!.complete(Unit)
        assertEquals(13, holder.paid.balance)
        // Still quiet beside a purchase except for the grant itself.
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Granted(3)), holder.paid.event)
    }

    @Test
    fun aClaimAnsweredBeforeThePurchaseIsOverwrittenByIt() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openPacks()
        holder.continuePurchase()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3, balance = 3)
        backend.verifyBalance = 13
        backend.verifyGate = kotlinx.coroutines.CompletableDeferred()
        holder.buyWithAccount(SignInProvider.GOOGLE)
        assertEquals(3, holder.paid.balance)
        store.updates.emit(StoreUpdate.Purchases(listOf(StorePurchase("t1", listOf("recipes_10"), 1, pending = false))))
        backend.verifyGate!!.complete(Unit)
        assertEquals(13, holder.paid.balance)
    }

    // --- Signed in, one tap -------------------------------------------------------------------

    @Test
    fun signedInOneTapClaimsWithNoSheet() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.showTab()
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3)
        holder.openWelcome()
        assertNull(holder.paid.welcomeSheet)
        assertEquals(0, account.signIns)
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Granted(3)), holder.paid.event)
        assertFalse(holder.paid.welcomeClaiming)
        assertEquals(3, holder.paid.balance)
    }

    @Test
    fun offWhenAlreadySignedInSaysNothingElseChanged() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.showTab()
        backend.claims += claimed(WelcomeStatus.OFF, welcome = 0)
        holder.claimWelcome()
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Off(signedInNow = false)), holder.paid.event)
    }

    @Test
    fun signingOutBringsBackTheSignedOutHintWhileTheOfferStands() = runUnconfined {
        val holder = holder()
        holder.showTab()
        holder.openWelcome()
        account.signInResults += SignInResult.SignedIn
        backend.claims += claimed(WelcomeStatus.GRANTED, granted = 3)
        holder.welcomeSignIn(SignInProvider.GOOGLE)
        assertNull(holder.paid.welcome)
        holder.signOut()
        assertEquals(WelcomeHint(3, WelcomeKind.SIGN_IN), holder.paid.welcome)
        // Signing in again with the same account: still settled here.
        account.mutable.value = Account.stateOf(AccountKind.SIGNED_IN)
        assertNull(holder.paid.welcome)
    }
}
