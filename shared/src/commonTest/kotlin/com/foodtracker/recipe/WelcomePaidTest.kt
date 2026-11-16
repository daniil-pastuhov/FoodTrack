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
        override suspend fun claimWelcome(): ServerResult<WelcomeClaimReply> {
            claimCalls++
            return claims.removeFirst()
        }
        override suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply> = error("not here")
        override suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply> = error("not here")
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

    // --- Asking for the offer ----------------------------------------------------------------

    @Test
    fun theTabBeingShownAsksForTheOfferAndTheHintNamesIt() = runUnconfined {
        val holder = holder()
        holder.refresh()
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
        holder.refresh()
        now += WELCOME_REFRESH_MILLIS - 1
        holder.refresh()
        assertEquals(1, backend.offerCalls)
        now += 1
        holder.refresh()
        assertEquals(2, backend.offerCalls)
    }

    @Test
    fun aCachedAnswerUnderADayOldShowsAtOnceAndAnOlderOneIsNone() = runUnconfined {
        repository = InMemoryRecipePaidRepository(RecipePaidSnapshot(welcomeOffer = WelcomeAnswer(4, now - 60_000)))
        backend.offer = ServerResult.Err(ServerError.OFFLINE)
        val holder = holder()
        holder.refresh()
        assertEquals(4, holder.paid.welcome?.recipes)
        // A day after it was given, with the server unreachable: no hint at all.
        now += WELCOME_ANSWER_MAX_AGE_MILLIS
        holder.refresh()
        assertNull(holder.paid.welcome)
    }

    @Test
    fun aFailedCallKeepsTheLastAnswerAndSaysNothing() = runUnconfined {
        val holder = holder()
        holder.refresh()
        backend.offer = ServerResult.Err(ServerError.INTERNAL)
        now += WELCOME_REFRESH_MILLIS
        holder.refresh()
        assertEquals(2, backend.offerCalls)
        assertEquals(3, holder.paid.welcome?.recipes)
        assertNull(holder.paid.event)
        assertNull(holder.uiState.value.event)
    }

    @Test
    fun anOlderServerWithoutTheCallReadsAsZero() = runUnconfined {
        backend.offer = ServerResult.Err(ServerError.CALLABLE_MISSING)
        val holder = holder()
        holder.refresh()
        assertNull(holder.paid.welcome)
        assertEquals(0, repository.get().welcomeOffer?.recipes)
    }

    @Test
    fun anInstallThatCantBuyNeverAsks() = runUnconfined {
        store.packs = emptyList()
        val holder = holder()
        holder.refresh()
        assertEquals(0, backend.offerCalls)
        assertNull(holder.paid.welcome)
    }

    @Test
    fun signedInTheNumberComesBesideTheBalanceWithoutTheOfferCall() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.refresh()
        assertEquals(0, backend.offerCalls)
        assertEquals(WelcomeHint(3, WelcomeKind.CLAIM), holder.paid.welcome)
    }

    @Test
    fun signedInWithAnOlderServerThereIsNoHint() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0)
        val holder = holder()
        holder.refresh()
        assertNull(holder.paid.welcome)
    }

    @Test
    fun theHintIsHiddenForAnAccountBlockedFromBuying() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0, purchasesBlocked = true, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.refresh()
        assertNull(holder.paid.welcome)
    }

    // --- Signed out: the sheet, the sign-in, the claim ---------------------------------------

    @Test
    fun theSheetAsksAgainFirstSoItsNumberIsCurrent() = runUnconfined {
        val holder = holder()
        holder.refresh()
        backend.offer = ServerResult.Ok(5)
        holder.openWelcome()
        assertEquals(2, backend.offerCalls)
        assertEquals(WelcomeSheet(5), holder.paid.welcomeSheet)
    }

    @Test
    fun aSheetWhoseRefreshSaysZeroIsAPlainSignInThatClaimsNothing() = runUnconfined {
        val holder = holder()
        holder.refresh()
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
        holder.refresh()
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
        holder.refresh()
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
        holder.refresh()
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
        holder.refresh()
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
        holder.refresh()
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
        holder.refresh()
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
        holder.refresh()
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
        holder.refresh()
        account.signInResults += SignInResult.SignedIn
        holder.signIn(SignInProvider.GOOGLE)
        assertEquals(0, backend.claimCalls)
    }

    @Test
    fun theFirstPurchasesSignInClaimsQuietlyAndTheStoreStillOpens() = runUnconfined {
        val holder = holder()
        holder.refresh()
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

    // --- Signed in, one tap -------------------------------------------------------------------

    @Test
    fun signedInOneTapClaimsWithNoSheet() = runUnconfined {
        account = Account(AccountKind.SIGNED_IN)
        backend.balance = BalanceReply(0, acceptsWelcome = true, welcomeRecipes = 3)
        val holder = holder()
        holder.refresh()
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
        holder.refresh()
        backend.claims += claimed(WelcomeStatus.OFF, welcome = 0)
        holder.claimWelcome()
        assertEquals(PaidEvent.Welcome(WelcomeOutcome.Off(signedInNow = false)), holder.paid.event)
    }

    @Test
    fun signingOutBringsBackTheSignedOutHintWhileTheOfferStands() = runUnconfined {
        val holder = holder()
        holder.refresh()
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
