package com.foodtracker.recipe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonElement

/** The pack sheet, from opening it to the store's answer. */
sealed interface BuySheet {
    /** Asking the store for its prices. */
    data object Loading : BuySheet

    /** [purchasing] while the store's payment sheet is up or the server is checking the
     *  purchase: the packs are disabled and Continue spins. */
    data class Packs(val packs: List<StorePack>, val selected: String, val purchasing: Boolean = false) : BuySheet

    /** Where recipes should live, asked before the first purchase. [signingIn] while that
     *  sign-in runs. */
    data class Choice(val packs: List<StorePack>, val selected: String, val signingIn: SignInProvider? = null) : BuySheet

    /** The store can't be reached, or purchases are off on this device. */
    data object StoreUnavailable : BuySheet
}

/** A lasting condition of the server route (T4). */
enum class ServerIssue {
    OFFLINE,

    /** Generation is switched off on our side. Buying is hidden meanwhile. */
    PAUSED,

    /** Our server stayed too busy to take the request (every instance busy, or this account's
     *  other generation still running) for as long as the app kept asking. The request is kept,
     *  nothing was taken, and Try again sends it with its own id. */
    BUSY
}

/** Why a purchase was dropped without adding recipes. */
enum class PurchaseRejection {
    /** The server said it isn't a valid, unrefunded purchase of ours (REJECTED_PERMANENT). */
    REFUSED,

    /** This build may not redeem store purchases (STORE_MISMATCH). Nothing was recorded or
     *  consumed, so the store refunds the purchase by itself. */
    WRONG_BUILD
}

/** Where the allergen consent step was opened: before a server request (agreeing goes on to
 *  write it) or from the switch in Recipe settings (agreeing only records it). */
enum class ConsentPrompt { BEFORE_WRITING, FROM_SETTINGS }

/** A sign-in whose account already exists while this phone holds recipes: asked before the two
 *  are joined. */
data class MergePrompt(val phoneBalance: Int, val accountBalance: Int?)

sealed interface PaidEvent {
    data class RecipesAdded(val count: Int) : PaidEvent
    data object SignInFailed : PaidEvent

    /** Adding a sign-in that belongs to another account. */
    data object SignInInUse : PaidEvent
    data object AccountDeleted : PaidEvent
    data object AccountActionFailed : PaidEvent

    /** Deleting waits for a recipe that is still being written. */
    data object AccountBusy : PaidEvent

    /** A sign-in joined this install to an existing account and [moved] recipes came with it;
     *  negative when this install owed recipes after a store refund, taken from the account. */
    data class Merged(val moved: Int) : PaidEvent

    /** A request sent earlier, that the person had moved on from or the app had lost the answer
     *  to, was written after all and is now in Saved recipes. */
    data class EarlierSaved(val menu: Boolean) : PaidEvent

    /** A request sent earlier failed on the server and was counted: [taken] recipes. */
    data class EarlierRefused(val taken: Int) : PaidEvent
}

data class PaidUiState(
    /** Whether this build offers the server route at all. */
    val available: Boolean = false,
    /** The last count the server reported; null before it has reported for this account. */
    val balance: Int? = null,
    val account: AccountState = AccountState(),
    val providers: Set<SignInProvider> = emptySet(),
    val sheet: BuySheet? = null,
    /** Recipes paid for in the store that the server hasn't confirmed yet. */
    val unconfirmedRecipes: Int = 0,
    /** The server is checking a purchase right now. */
    val verifying: Boolean = false,
    val paymentPending: Boolean = false,
    /** A purchase dropped for good, and why: shown until dismissed (T6). */
    val rejection: PurchaseRejection? = null,
    /** Recipes paid for in the store by another account than the one signed in now
     *  (PURCHASE_ACCOUNT_MISMATCH): kept, and added once that account signs in. */
    val elsewhereRecipes: Int = 0,
    /** The server won't take this build's store purchases (STORE_MISMATCH): buying is hidden for
     *  the rest of the run. */
    val storeRefused: Boolean = false,
    /** The server is too busy right now and the app is asking again: a calm state, not an error. */
    val serverBusy: Boolean = false,
    val issue: ServerIssue? = null,
    /** The server is writing a recipe or a menu. */
    val writing: RecipeMode? = null,
    val merge: MergePrompt? = null,
    /** A sign-in, sign-out or deletion is running. */
    val accountBusy: Boolean = false,
    val event: PaidEvent? = null,
    /** Whether the store sells any pack to this build: null until it has answered. */
    val packsOffered: Boolean? = null,
    /** What a recipe and a menu take, as the server last said. */
    val costs: RecipeCosts = RecipeCosts(),
    /** The server won't sell to this account (earlier purchases refunded after use). */
    val purchasesBlocked: Boolean = false,
    /** The account was deleted but the server could not remove Apple's link to the app
     *  (`appleLinkRemoved: false`): the person is told how, until they dismiss it. */
    val appleLinkLeft: Boolean = false,
    /** The answer to sending allergens to our server, as recorded on this phone. */
    val allergenConsent: AllergenConsentRecord? = null,
    /** The consent step on screen, and where it was opened from; null when not shown. */
    val consentPrompt: ConsentPrompt? = null
) {
    /** Buying is offered: only once the store has shown it has packs, so there is never a Buy
     *  that leads nowhere; hidden while the server is paused, and while a payment is waiting or
     *  unconfirmed, so nobody pays twice. */
    val canBuy: Boolean
        get() = available && !purchasesBlocked && !storeRefused && packsOffered == true && issue != ServerIssue.PAUSED &&
            unconfirmedRecipes == 0 && !paymentPending

    val purchaseRejected: Boolean get() = rejection != null
}

/** How long a "paused" answer stands before the tab offers the server again. */
private const val PAUSE_RECHECK_MILLIS = 5 * 60_000L

/** How often the count may be asked for on resume. */
private const val BALANCE_REFRESH_MILLIS = 60_000L

/** How long to keep asking about a generation the server is still writing: past its reservation
 *  deadline (60 s for a recipe, 180 s for a menu) plus the sweeper's margin. */
private const val RECIPE_POLL_MILLIS = 150_000L
private const val MENU_POLL_MILLIS = 270_000L

private const val HELD_GENERATION_MAX_MILLIS = 24 * 60 * 60_000L

private const val RESUME_DEDUPE_MILLIS = 5_000L

/** `getGeneration` is asked at most this often (functions/CONTRACT.md: at least 3 s apart)... */
internal const val POLL_MILLIS = 3_000L

/** ...and a little less often once a menu has run for a while. */
internal const val SLOW_POLL_MILLIS = 5_000L
private const val SLOW_POLL_AFTER_MILLIS = 30_000L

/** Waits after a busy or failed answer: doubling from 3 s, never more than 30 s apart. */
private const val BACKOFF_MAX_MILLIS = 30_000L

/** The contract's per-minute limit: "back off and retry after a minute". */
internal const val RATE_LIMIT_WAIT_MILLIS = 60_000L

/** After a round of purchase checks or a recovery that couldn't reach the server, the next
 *  automatic one waits at least this long, however often the app comes to the front. */
private const val AUTO_RETRY_MILLIS = 30_000L

/** How long account calls (`mergeBalance`, `deleteAccount`) keep asking a busy server: past a
 *  menu's generation, which holds the account busy for up to ~2 minutes. */
private const val ACCOUNT_BUSY_MILLIS = 150_000L

/** How often a purchase check is asked again at once while every server instance is busy. */
private const val VERIFY_BUSY_RETRIES = 3

/** The wait before the [attempt]th repeat (0-based) after a busy or failed answer. */
internal fun backoffMillis(attempt: Int): Long =
    (POLL_MILLIS shl attempt.coerceIn(0, 4)).coerceAtMost(BACKOFF_MAX_MILLIS)

/** Errors that, when asking about a request the server may already hold, leave it held. */
private val KEEP_WHEN_ASKING = setOf(
    ServerError.RATE_LIMITED,
    ServerError.BUSY,
    ServerError.UNKNOWN,
    ServerError.USER_DAILY_LIMIT,
    ServerError.DAILY_CAP,
    ServerError.INFRA_FAILURE_LIMIT,
    ServerError.GENERATION_DISABLED,
    ServerError.INSUFFICIENT_CREDITS,
    ServerError.INVALID_REQUEST,
    ServerError.UNKNOWN_REQUEST,
    ServerError.CALLABLE_MISSING
)

/** The English title a menu is saved under; the app shows its own words for menus. */
const val MENU_SAVED_TITLE = "Menu for three days"

/**
 * The paid route: packs, the purchase's way to the server, the account, and generation on the
 * server. Shares [RecipeStateHolder]'s state, under [RecipeUiState.paid] and the generation
 * fields.
 *
 * Rules from functions/CONTRACT.md:
 * - The count shown is what the server last reported. It changes when a result or a purchase
 *   arrives, never on a tap.
 * - A generation keeps its request id until the server gives a final answer (DONE or FAILED) or
 *   refuses it outright; a lost connection, `internal` or IN_PROGRESS keeps it, so asking again
 *   is never charged twice. The request is kept across restarts and sent again, unchanged,
 *   before anything new.
 * - A purchase is kept on the phone until the server answers GRANTED, ALREADY_GRANTED or
 *   REJECTED_PERMANENT. The app never consumes or acknowledges it: the server does.
 * - No user, App Check token or server call exists until the person starts a purchase.
 */
internal class RecipePaid(
    private val state: MutableStateFlow<RecipeUiState>,
    private val backend: RecipeBackend,
    private val store: RecipeStore,
    private val account: RecipeAccount,
    private val repository: RecipePaidRepository,
    private val savedRecipeRepository: SavedRecipeRepository,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val newSavedId: () -> String,
    private val newRequestId: () -> String,
    private val today: () -> LocalDate,
    /** Waits the given milliseconds before asking the server again. */
    private val pollDelay: suspend (Long) -> Unit
) {
    /** What is kept on the phone. Changed only through [persist], atomically: purchases, the
     *  generation and the count are updated from different coroutines on a multi-threaded
     *  dispatcher, and a plain read-modify-write would lose one of them. */
    private val snapshots = MutableStateFlow(repository.get())
    private val snapshot: RecipePaidSnapshot get() = snapshots.value
    private val purchaseLock = Mutex()
    private val generationLock = Mutex()
    private var pendingMerge: PendingMerge? = null
    private var pausedAt: Long? = null
    private var balanceAskedAt: Long? = null
    private var resumedAt: Long? = null

    /** Set when an automatic purchase check or recovery couldn't reach the server; the next one
     *  waits [AUTO_RETRY_MILLIS]. */
    private var verifyFailedAt: Long? = null
    private var recoveryFailedAt: Long? = null

    /** Purchases the server refused as from the wrong store this run: not sent again. */
    private val wrongStoreTokens = mutableSetOf<String>()

    /** Set when this build turns out not to be able to use the paid route after all (App Check
     *  refuses it, or the store sells it nothing and nothing is held): the route hides for the
     *  rest of the run, and the free routes stay. */
    private var hiddenForRun = false

    private val available: Boolean get() = backend.isAvailable && store.isSupported && !hiddenForRun

    private var probingPacks = false

    /** Set when the server turns out to predate `getGeneration`: polls go through
     *  `generateRecipe` again for the rest of the run, as they used to. */
    private var getGenerationMissing = false

    /** The generation still waiting for its answer, unless it is too old to ask about. */
    private fun held(): GenerateRequest? {
        val request = snapshot.pendingGeneration ?: return null
        val since = snapshot.pendingSinceMillis
        if (since == null || nowMillis() - since > HELD_GENERATION_MAX_MILLIS) {
            persist { it.copy(pendingGeneration = null, pendingSinceMillis = null) }
            return null
        }
        return request
    }

    private fun updatePaid(transform: (PaidUiState) -> PaidUiState) {
        state.update { it.copy(paid = transform(it.paid)) }
    }

    private fun persist(transform: (RecipePaidSnapshot) -> RecipePaidSnapshot) {
        publish(snapshots.updateAndGet(transform))
    }

    private fun publish(kept: RecipePaidSnapshot) {
        val key = account.state.value.purchaseKey
        updatePaid {
            it.copy(
                available = shown(it.packsOffered),
                balance = kept.balance,
                costs = kept.costs ?: RecipeCosts(),
                purchasesBlocked = kept.purchasesBlocked,
                unconfirmedRecipes = kept.unverified.filterNot { purchase -> purchase.isElsewhere(key) }.sumOf { purchase -> purchase.recipes },
                elsewhereRecipes = kept.unverified.filter { purchase -> purchase.isElsewhere(key) }.sumOf { purchase -> purchase.recipes },
                paymentPending = kept.paymentPending,
                allergenConsent = kept.allergenConsent
            )
        }
    }

    /** Refused as another account's, and that account (or none) is still the one here. */
    private fun UnverifiedPurchase.isElsewhere(key: String?): Boolean = refusedFor != null && (key == null || refusedFor == key)

    /** What a reply that carries a balance says besides it: the costs, and the block, which is
     *  `true` or absent (never `false`), so absent lifts it. */
    private fun RecipePaidSnapshot.withTerms(costs: JsonElement?, blocked: JsonElement?): RecipePaidSnapshot =
        copy(costs = recipeCostsOf(costs) ?: this.costs, purchasesBlocked = purchasesBlockedOf(blocked))

    /** Whether the route is on screen: only once the store has shown it sells this build a
     *  pack, unless something is held already (a count, a purchase, a payment, a generation),
     *  which always shows, so an unreachable store never hides what was paid for. */
    private fun shown(packsOffered: Boolean?): Boolean = available && (packsOffered == true || !holdsNothing())

    fun start() {
        updatePaid { it.copy(available = shown(it.packsOffered)) }
        // Not even the account is looked at on a build without the paid route.
        if (!available) return
        updatePaid { it.copy(account = account.state.value, providers = account.providers) }
        publish(snapshot)
        scope.launch {
            account.state.collect { current ->
                if (current.kind == AccountKind.NONE && snapshot.balance != null) persist { it.copy(balance = null) }
                updatePaid { it.copy(account = current) }
                publish(snapshot)
                // Another account is here now: a purchase refused as someone else's may be its.
                if (current.purchaseKey != null && snapshot.unverified.any { it.refusedFor != null && it.refusedFor != current.purchaseKey }) {
                    scope.launch { resubmitPurchases() }
                }
            }
        }
        scope.launch { store.updates.collect { onStoreUpdate(it) } }
        // One writer, in order, always ending on the latest snapshot; it also corrects the UI
        // should two publishes have crossed.
        scope.launch {
            snapshots.drop(1).collect { kept ->
                repository.save(kept)
                publish(kept)
            }
        }
    }

    private fun markUnavailable() {
        hiddenForRun = true
        // The menu has no free route, so the tab goes back to a single recipe.
        state.update { it.copy(mode = RecipeMode.RECIPE, paid = it.paid.copy(available = false, sheet = null, writing = null)) }
    }

    // --- Resume ------------------------------------------------------------------------------

    fun resume() {
        if (!available) return
        pausedAt?.let { at ->
            if (nowMillis() - at >= PAUSE_RECHECK_MILLIS) {
                pausedAt = null
                updatePaid { if (it.issue == ServerIssue.PAUSED) it.copy(issue = null) else it }
            }
        }
        // The app's resume and the tab's own both land here; once is enough.
        val last = resumedAt
        if (last == null || nowMillis() - last >= RESUME_DEDUPE_MILLIS) {
            resumedAt = nowMillis()
            if (state.value.paid.packsOffered == null) scope.launch { probePacks() }
            if (waitedEnough(verifyFailedAt)) scope.launch { resubmitPurchases() }
            if (held() != null && state.value.paid.writing == null && waitedEnough(recoveryFailedAt)) {
                scope.launch { recoverGeneration() }
            }
        }
        val asked = balanceAskedAt
        if (account.state.value.kind != AccountKind.NONE && (asked == null || nowMillis() - asked >= BALANCE_REFRESH_MILLIS)) {
            scope.launch { refreshBalance() }
        }
    }

    private fun waitedEnough(failedAt: Long?): Boolean = failedAt == null || nowMillis() - failedAt >= AUTO_RETRY_MILLIS

    private suspend fun refreshBalance(): Boolean {
        if (account.state.value.kind == AccountKind.NONE) return false
        balanceAskedAt = nowMillis()
        return when (val result = backend.getBalance()) {
            is ServerResult.Ok -> {
                persist { it.copy(balance = result.value.balance, costs = result.value.costs ?: it.costs, purchasesBlocked = result.value.purchasesBlocked) }
                updatePaid { if (it.issue == ServerIssue.BUSY && held() == null) it.copy(issue = null) else it }
                updatePaid { if (it.issue == ServerIssue.OFFLINE) it.copy(issue = null) else it }
                true
            }
            is ServerResult.Err -> {
                handleCommonError(result.error)
                false
            }
        }
    }

    /** What every call does with these errors, whatever it was doing. */
    private suspend fun handleCommonError(error: ServerError) {
        when (error) {
            ServerError.OFFLINE -> updatePaid { if (it.issue == null) it.copy(issue = ServerIssue.OFFLINE) else it }
            ServerError.ACCOUNT_CLOSED -> {
                // Deleted or merged away elsewhere: start over as someone without an account.
                runCatching { account.signOut() }
                persist { it.copy(balance = null, pendingGeneration = null) }
            }
            ServerError.UNAUTHENTICATED -> markUnavailable()
            else -> Unit
        }
    }

    // --- Purchases ---------------------------------------------------------------------------

    /** Asks the store whether it sells this build any pack, so Buy is shown only when it does.
     *  An unreachable store is asked again on a later resume. */
    private suspend fun probePacks() {
        if (probingPacks) return
        probingPacks = true
        try {
            val packs = runCatching { store.packs() }.getOrNull() ?: return
            noteOffered(packs.any { it.productId in RECIPE_PACKS })
        } finally {
            probingPacks = false
        }
    }

    /** No packs for this build (a debug or sideloaded build Play has no products for): with
     *  nothing bought, the whole route hides; with recipes held, they stay usable, without Buy. */
    private fun noteOffered(offered: Boolean) {
        updatePaid { it.copy(packsOffered = offered, available = shown(offered)) }
        if (!offered && holdsNothing()) markUnavailable()
    }

    private fun holdsNothing(): Boolean =
        (snapshot.balance ?: 0) <= 0 && snapshot.unverified.isEmpty() && !snapshot.paymentPending && snapshot.pendingGeneration == null

    fun openPacks() {
        val paid = state.value.paid
        if (!paid.canBuy) return
        updatePaid { it.copy(sheet = BuySheet.Loading) }
        // The sheet shows what a menu takes: with no costs from the server yet, ask for them.
        if (snapshot.costs == null && account.state.value.kind != AccountKind.NONE) scope.launch { refreshBalance() }
        scope.launch {
            val packs = runCatching { store.packs() }.getOrNull()
                ?.filter { it.productId in RECIPE_PACKS }
                ?.sortedBy { it.recipes }
            if (packs != null && packs.isEmpty()) {
                // The store answered and sells this build nothing: Buy goes, rather than a sheet
                // whose Try again could never work.
                noteOffered(false)
                updatePaid { it.copy(sheet = null) }
                return@launch
            }
            updatePaid { current ->
                // Closed while the store answered.
                if (current.sheet != BuySheet.Loading) return@updatePaid current
                if (packs.isNullOrEmpty()) {
                    current.copy(sheet = BuySheet.StoreUnavailable)
                } else {
                    val selected = packs.firstOrNull { it.productId == DEFAULT_PACK }?.productId ?: packs.first().productId
                    current.copy(sheet = BuySheet.Packs(packs, selected))
                }
            }
        }
    }

    fun selectPack(productId: String) {
        updatePaid { current ->
            when (val sheet = current.sheet) {
                is BuySheet.Packs -> if (sheet.purchasing) current else current.copy(sheet = sheet.copy(selected = productId))
                else -> current
            }
        }
    }

    fun continuePurchase() {
        val sheet = state.value.paid.sheet as? BuySheet.Packs ?: return
        if (sheet.purchasing) return
        val checking = sheet.copy(purchasing = true)
        updatePaid { it.copy(sheet = checking) }
        scope.launch {
            // Before any account or payment: a build App Check refuses could take the money and
            // then never be let in to add the recipes.
            if (backend.attest() == Attestation.FAILED) {
                if (holdsNothing()) {
                    markUnavailable()
                } else {
                    // What is held stays usable; only buying is off for now.
                    updatePaid { if (it.sheet == checking) it.copy(sheet = BuySheet.StoreUnavailable) else it }
                }
                return@launch
            }
            // Closed while that was asked.
            if (state.value.paid.sheet != checking) return@launch
            if (account.state.value.kind == AccountKind.NONE) {
                updatePaid { it.copy(sheet = BuySheet.Choice(sheet.packs, sheet.selected)) }
            } else {
                launchStore(sheet.packs, sheet.selected)
            }
        }
    }

    fun backToPacks() {
        updatePaid { current ->
            when (val sheet = current.sheet) {
                is BuySheet.Choice -> if (sheet.signingIn != null) current else current.copy(sheet = BuySheet.Packs(sheet.packs, sheet.selected))
                else -> current
            }
        }
    }

    fun buyWithoutAccount() {
        val sheet = state.value.paid.sheet as? BuySheet.Choice ?: return
        if (sheet.signingIn != null) return
        updatePaid { it.copy(sheet = BuySheet.Packs(sheet.packs, sheet.selected, purchasing = true)) }
        scope.launch {
            if (account.ensureUser()) {
                persist { it.copy(balance = it.balance ?: 0) }
                launchStore(sheet.packs, sheet.selected)
            } else {
                updatePaid { it.copy(sheet = BuySheet.StoreUnavailable) }
            }
        }
    }

    fun buyWithAccount(provider: SignInProvider) {
        val sheet = state.value.paid.sheet as? BuySheet.Choice ?: return
        if (sheet.signingIn != null) return
        updatePaid { it.copy(sheet = sheet.copy(signingIn = provider)) }
        scope.launch {
            when (val result = account.signIn(provider)) {
                SignInResult.SignedIn -> {
                    refreshBalance()
                    launchStore(sheet.packs, sheet.selected)
                }
                is SignInResult.NeedsMerge -> {
                    // Only possible with an anonymous user, which the choice is never shown for;
                    // handled the same way as from Recipe settings all the same.
                    updatePaid { it.copy(sheet = null) }
                    offerMerge(result.merge)
                }
                SignInResult.Cancelled -> updatePaid { it.copy(sheet = sheet.copy(signingIn = null)) }
                SignInResult.InUse, SignInResult.Failed ->
                    updatePaid { it.copy(sheet = sheet.copy(signingIn = null), event = PaidEvent.SignInFailed) }
            }
        }
    }

    private fun launchStore(packs: List<StorePack>, selected: String) {
        updatePaid { it.copy(sheet = BuySheet.Packs(packs, selected, purchasing = true)) }
        scope.launch {
            val opened = runCatching { store.buy(selected) }.getOrDefault(false)
            if (!opened) updatePaid { it.copy(sheet = BuySheet.StoreUnavailable) }
        }
    }

    fun dismissPacks() {
        // A purchase under way carries on: its outcome shows on the tab.
        updatePaid { it.copy(sheet = null) }
    }

    fun dismissRejected() {
        updatePaid { it.copy(rejection = null) }
    }

    fun retryPurchases() {
        scope.launch { resubmitPurchases() }
    }

    private suspend fun onStoreUpdate(update: StoreUpdate) {
        when (update) {
            is StoreUpdate.Purchases -> purchaseLock.withLock {
                val completed = update.purchases.filterNot { it.pending }
                val pending = update.purchases.any { it.pending }
                persist { current ->
                    current.copy(
                        unverified = current.unverified + completed.toUnverified(current.unverified),
                        // A completed one is usually the pending one clearing.
                        paymentPending = pending || (current.paymentPending && completed.isEmpty())
                    )
                }
                if (pending && completed.isEmpty()) updatePaid { it.copy(sheet = null) }
                verifyAll()
            }
            StoreUpdate.Cancelled -> updatePaid { current ->
                val sheet = current.sheet as? BuySheet.Packs
                if (sheet != null) current.copy(sheet = sheet.copy(purchasing = false)) else current
            }
            StoreUpdate.AlreadyOwned -> {
                // A purchase of this pack is still in the store, unconsumed: send it.
                updatePaid { it.copy(sheet = null) }
                resubmitPurchases()
            }
            StoreUpdate.Unavailable -> updatePaid { it.copy(sheet = BuySheet.StoreUnavailable) }
        }
    }

    private fun List<StorePurchase>.toUnverified(known: List<UnverifiedPurchase>): List<UnverifiedPurchase> =
        filter { purchase -> purchase.token !in wrongStoreTokens && known.none { it.token == purchase.token } }
            .distinctBy { it.token }
            .map { UnverifiedPurchase(it.token, it.recipes) }

    /** Asks the store what it still holds and sends every purchase the server hasn't confirmed. */
    private suspend fun resubmitPurchases() = purchaseLock.withLock {
        if (!available) return@withLock
        val held = runCatching { store.purchases() }.getOrNull()
        if (held != null) {
            persist { current ->
                current.copy(
                    unverified = current.unverified + held.filterNot { it.pending }.toUnverified(current.unverified),
                    paymentPending = held.any { it.pending }
                )
            }
        }
        verifyAll()
    }

    /** Sends every unconfirmed purchase. Called with [purchaseLock] held. */
    private suspend fun verifyAll() {
        if (snapshot.unverified.none { it.refusedFor == null || it.refusedFor != account.state.value.purchaseKey }) {
            closePurchasingSheet()
            return
        }
        // A purchase made, then the app reinstalled or signed out: it still needs someone to
        // belong to.
        if (account.state.value.kind == AccountKind.NONE && !account.ensureUser()) {
            closePurchasingSheet()
            return
        }
        val key = account.state.value.purchaseKey
        updatePaid { it.copy(verifying = true) }
        var reachedServer = true
        purchases@ for (purchase in snapshot.unverified.filterNot { it.isElsewhere(key) }) {
            var attempt = 0
            while (true) {
                val result = try {
                    backend.verifyPurchase(purchase.token)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServerResult.Err(ServerError.OFFLINE)
                }
                if (result is ServerResult.Err && result.error == ServerError.OVERLOADED && attempt < VERIFY_BUSY_RETRIES) {
                    // Every instance busy: nothing was recorded. The same purchase again, later.
                    pollDelay(backoffMillis(attempt++))
                    continue
                }
                when (result) {
                    is ServerResult.Ok -> {
                        val reply = result.value
                        val settled = reply.status != VerifyStatus.RETRY
                        persist { current ->
                            current.copy(
                                unverified = if (settled) current.unverified.filterNot { it.token == purchase.token } else current.unverified,
                                balance = reply.balance ?: current.balance
                            ).withTerms(reply.costs, reply.purchasesBlocked)
                        }
                        when (reply.status) {
                            VerifyStatus.GRANTED -> updatePaid { it.copy(event = PaidEvent.RecipesAdded(purchase.recipes)) }
                            VerifyStatus.REJECTED_PERMANENT -> updatePaid { it.copy(rejection = PurchaseRejection.REFUSED) }
                            VerifyStatus.ALREADY_GRANTED, VerifyStatus.RETRY -> Unit
                        }
                    }
                    is ServerResult.Err -> when (result.error) {
                        ServerError.STORE_MISMATCH -> {
                            // A build that may not redeem this store's purchases: none of them
                            // will go through, so buying stops here. Nothing was recorded and the
                            // app never consumes, so the store refunds it by itself.
                            wrongStoreTokens += purchase.token
                            persist { current -> current.copy(unverified = current.unverified.filterNot { it.token == purchase.token }) }
                            updatePaid { it.copy(storeRefused = true, sheet = null, rejection = PurchaseRejection.WRONG_BUILD) }
                        }
                        ServerError.PURCHASE_ACCOUNT_MISMATCH -> {
                            // Another account bought it: kept, unconsumed, for that account to
                            // add once it signs in here (it is sent again then).
                            persist { current ->
                                current.copy(unverified = current.unverified.map { if (it.token == purchase.token) it.copy(refusedFor = key.orEmpty()) else it })
                            }
                        }
                        // Nothing more will get through for now: the rest wait for later.
                        ServerError.OFFLINE, ServerError.RATE_LIMITED, ServerError.OVERLOADED -> {
                            reachedServer = false
                            break@purchases
                        }
                        else -> {
                            handleCommonError(result.error)
                            if (!available) break@purchases
                        }
                    }
                }
                break
            }
            if (state.value.paid.storeRefused) break
        }
        verifyFailedAt = if (reachedServer) null else nowMillis()
        updatePaid { it.copy(verifying = false) }
        closePurchasingSheet()
    }

    private fun closePurchasingSheet() {
        updatePaid { current ->
            val sheet = current.sheet
            if (sheet is BuySheet.Packs && sheet.purchasing) current.copy(sheet = null) else current
        }
    }

    // --- Account -----------------------------------------------------------------------------

    fun signIn(provider: SignInProvider) {
        if (state.value.paid.accountBusy) return
        updatePaid { it.copy(accountBusy = true) }
        scope.launch {
            when (val result = account.signIn(provider)) {
                SignInResult.SignedIn -> {
                    updatePaid { it.copy(accountBusy = false) }
                    refreshBalance()
                }
                is SignInResult.NeedsMerge -> offerMerge(result.merge)
                SignInResult.InUse -> updatePaid { it.copy(accountBusy = false, event = PaidEvent.SignInInUse) }
                SignInResult.Cancelled -> updatePaid { it.copy(accountBusy = false) }
                SignInResult.Failed -> updatePaid { it.copy(accountBusy = false, event = PaidEvent.SignInFailed) }
            }
        }
    }

    private suspend fun offerMerge(merge: PendingMerge) {
        val phone = snapshot.balance ?: 0
        if (phone <= 0) {
            // Nothing on this phone to lose or to ask about. Joined all the same: below zero
            // (a store refund of recipes already used) the debt has to move with the person
            // (functions/CONTRACT.md, mergeBalance), and at zero it carries over the purchases
            // this install made, which the server ties to it.
            pendingMerge = merge
            finishMerge()
        } else {
            pendingMerge = merge
            updatePaid { it.copy(accountBusy = false, merge = MergePrompt(phone, merge.accountBalance)) }
        }
    }

    fun confirmMerge() {
        if (pendingMerge == null) return
        updatePaid { it.copy(merge = null, accountBusy = true) }
        scope.launch { finishMerge() }
    }

    private suspend fun finishMerge() {
        val merge = pendingMerge ?: return
        pendingMerge = null
        // Busy (every instance taken, or this install's own generation still being written):
        // nothing has moved, so it is asked again after a while.
        when (val result = whileBusy(setOf(ServerError.OVERLOADED, ServerError.BUSY)) { account.completeMerge(merge) }) {
            is ServerResult.Ok -> {
                val reply = result.value
                persist { it.copy(balance = reply.balance, costs = reply.costs ?: it.costs, purchasesBlocked = reply.purchasesBlocked) }
                val event = if (reply.moved != 0) PaidEvent.Merged(reply.moved) else null
                updatePaid { it.copy(accountBusy = false, merge = null, event = event ?: it.event) }
            }
            is ServerResult.Err -> {
                runCatching { account.cancelMerge(merge) }
                handleCommonError(result.error)
                updatePaid { it.copy(accountBusy = false, merge = null, event = PaidEvent.SignInFailed) }
            }
        }
    }

    /** [call], asked again with growing waits while it fails with one of [busy], for up to
     *  [ACCOUNT_BUSY_MILLIS]. */
    private suspend fun <T> whileBusy(busy: Set<ServerError>, call: suspend () -> ServerResult<T>): ServerResult<T> {
        val deadline = nowMillis() + ACCOUNT_BUSY_MILLIS
        var attempt = 0
        while (true) {
            val result = call()
            if (result !is ServerResult.Err || result.error !in busy || nowMillis() >= deadline) return result
            pollDelay(backoffMillis(attempt++))
        }
    }

    fun cancelMerge() {
        val merge = pendingMerge ?: return
        pendingMerge = null
        updatePaid { it.copy(merge = null, accountBusy = false) }
        scope.launch { account.cancelMerge(merge) }
    }

    fun signOut() {
        if (state.value.paid.accountBusy) return
        updatePaid { it.copy(accountBusy = true) }
        scope.launch {
            runCatching { account.signOut() }
            // A generation sent for this account must not be sent again for the next one.
            persist { it.copy(balance = null, pendingGeneration = null) }
            updatePaid { it.copy(accountBusy = false, issue = null) }
        }
    }

    fun deleteAccount() {
        if (state.value.paid.accountBusy) return
        updatePaid { it.copy(accountBusy = true) }
        scope.launch {
            val signedIn = account.state.value.kind == AccountKind.SIGNED_IN
            var reauthenticated = false
            if (signedIn && account.needsFreshSignIn()) {
                if (!account.reauthenticateForDeletion()) {
                    updatePaid { it.copy(accountBusy = false) }
                    return@launch
                }
                reauthenticated = true
            }
            // Every instance busy: asked again shortly; a generation still being written answers
            // BUSY, which the person is told to wait for.
            val delete = suspend { whileBusy(setOf(ServerError.OVERLOADED)) { backend.deleteAccount() } }
            var result = delete()
            if (result is ServerResult.Err && result.error == ServerError.RECENT_SIGN_IN_REQUIRED && signedIn && !reauthenticated) {
                if (!account.reauthenticateForDeletion()) {
                    updatePaid { it.copy(accountBusy = false) }
                    return@launch
                }
                result = delete()
            }
            when (result) {
                is ServerResult.Ok -> {
                    runCatching { account.signOut() }
                    persist { it.copy(balance = null, pendingGeneration = null) }
                    // Only when the server says Apple's link stayed is the person told how to
                    // remove it themselves.
                    val appleLeft = appleLinkLeftBehind(result.value)
                    updatePaid { it.copy(accountBusy = false, issue = null, event = PaidEvent.AccountDeleted, appleLinkLeft = appleLeft) }
                }
                is ServerResult.Err -> {
                    if (result.error == ServerError.ACCOUNT_CLOSED) {
                        handleCommonError(result.error)
                        updatePaid { it.copy(accountBusy = false, event = PaidEvent.AccountDeleted) }
                    } else {
                        if (result.error == ServerError.OFFLINE) handleCommonError(result.error)
                        val event = if (result.error == ServerError.BUSY) PaidEvent.AccountBusy else PaidEvent.AccountActionFailed
                        updatePaid { it.copy(accountBusy = false, event = event) }
                    }
                }
            }
        }
    }

    fun consumeEvent() {
        updatePaid { it.copy(event = null) }
    }

    fun dismissAppleLinkNotice() {
        updatePaid { it.copy(appleLinkLeft = false) }
    }

    // --- Allergen consent (AllergenConsent.kt) -----------------------------------------------

    /** The allergen list in Settings changed: a refusal is forgotten once it is empty. */
    fun onAllergensChanged(hasAllergens: Boolean) {
        val current = snapshot.allergenConsent
        val next = allergenConsentAfterListChange(current, hasAllergens)
        if (next != current) persist { it.copy(allergenConsent = next) }
    }

    /** "Agree and continue": recorded with the time and the wording's version; from the Recipe
     *  tab the request then goes ahead. */
    fun agreeToAllergenConsent() {
        val prompt = state.value.paid.consentPrompt ?: return
        persist { it.copy(allergenConsent = AllergenConsentRecord(agreed = true, atMillis = nowMillis(), version = ALLERGEN_CONSENT_VERSION)) }
        updatePaid { it.copy(consentPrompt = null) }
        if (prompt == ConsentPrompt.BEFORE_WRITING) generate()
    }

    /** "Cancel": recorded as a refusal, so the step isn't shown on every tap; the server route
     *  stays blocked while allergens are set, and the free routes are there. */
    fun declineAllergenConsent() {
        if (state.value.paid.consentPrompt == null) return
        persist { it.copy(allergenConsent = AllergenConsentRecord(agreed = false, atMillis = nowMillis(), version = ALLERGEN_CONSENT_VERSION)) }
        updatePaid { it.copy(consentPrompt = null) }
    }

    /** Recipe settings' switch: off withdraws at once; on shows the consent step first. */
    fun setAllergenConsent(on: Boolean) {
        if (on) {
            if (!allergenConsentGiven(snapshot.allergenConsent)) updatePaid { it.copy(consentPrompt = ConsentPrompt.FROM_SETTINGS) }
        } else {
            persist { it.copy(allergenConsent = AllergenConsentRecord(agreed = false, atMillis = nowMillis(), version = ALLERGEN_CONSENT_VERSION)) }
        }
    }

    // --- Generation --------------------------------------------------------------------------

    /** A request ready to send, with the items left out of it because the server refused their
     *  names earlier in this run; or why none can be sent. */
    private sealed interface Built {
        data class Ready(val request: GenerateRequest, val leftOut: List<String>) : Built
        data class Blocked(val event: RecipeEvent) : Built
    }

    /**
     * Names the server refused in this run (lower case, as sent). Left out of later requests
     * without asking: sending one again would be refused again, and repeated refusals of the
     * suspicious kind lock the account for the day (USER_DAILY_LIMIT).
     */
    private val refusedIngredients = mutableSetOf<String>()
    private val refusedAllergens = mutableSetOf<String>()

    private fun buildRequest(current: RecipeUiState): Built {
        val leftOut = mutableListOf<String>()
        val ingredients = current.selectedRows
            .mapNotNull { row ->
                cleanWireName(row.item.name)?.let { name ->
                    if (name.lowercase() in refusedIngredients) {
                        leftOut += name
                        null
                    } else {
                        WireIngredient(name, row.item.expiryDate.toString(), row.item.quantity.coerceIn(0, 9999))
                    }
                }
            }
            .take(MAX_REQUEST_INGREDIENTS)
        if (ingredients.isEmpty()) {
            return Built.Blocked(leftOut.firstOrNull()?.let { RecipeEvent.IngredientNotSent(it) } ?: RecipeEvent.ServerFailed)
        }
        // Never sent without an allergen the person set, or with part of one cut away: a recipe
        // written without it could be unsafe. More than the server takes stops the request too,
        // rather than leaving some out.
        val allergens = current.allergens.filter { it.isNotBlank() }.map { raw ->
            cleanAllergenName(raw) ?: return Built.Blocked(RecipeEvent.AllergenNotSent(raw.trim()))
        }.distinctBy { it.lowercase() }
        if (allergens.size > MAX_REQUEST_ALLERGENS) {
            return Built.Blocked(RecipeEvent.TooManyAllergens(allergens.size, MAX_REQUEST_ALLERGENS))
        }
        allergens.firstOrNull { it.lowercase() in refusedAllergens }?.let { return Built.Blocked(RecipeEvent.AllergenNotSent(it)) }
        val menu = current.mode == RecipeMode.MENU
        val request = GenerateRequest(
            requestId = newRequestId(),
            kind = if (menu) WireKind.MENU else WireKind.RECIPE,
            courses = if (menu) current.courses else null,
            config = current.config.copy(language = current.language),
            ingredients = ingredients,
            allergens = allergens,
            today = today().toString(),
            // Built only past the consent step (generate), so allergens here were agreed to.
            allergenConsent = allergenConsentFlag(allergens)
        )
        return Built.Ready(request, leftOut)
    }

    fun generate() {
        val current = state.value
        if (current.isBusy || !current.paid.available || current.selectedRows.isEmpty()) return
        if (current.paid.issue == ServerIssue.PAUSED) return
        if ((current.paid.balance ?: 0) < current.serverCost) {
            openPacks()
            return
        }
        // Allergens are health data: sent only with the person's consent, and never left out
        // instead (a recipe written without them could be unsafe).
        when (allergenConsentStep(current.allergens.any { it.isNotBlank() }, snapshot.allergenConsent)) {
            AllergenConsentStep.ASK -> {
                updatePaid { it.copy(consentPrompt = ConsentPrompt.BEFORE_WRITING) }
                return
            }
            AllergenConsentStep.DECLINED -> {
                state.update { it.copy(event = RecipeEvent.AllergenConsentDeclined) }
                return
            }
            AllergenConsentStep.NOT_NEEDED, AllergenConsentStep.GRANTED -> Unit
        }
        val built = when (val result = buildRequest(current)) {
            is Built.Blocked -> {
                state.update { it.copy(event = result.event) }
                return
            }
            is Built.Ready -> result
        }
        val fresh = built.request
        updatePaid { it.copy(writing = current.mode) }
        state.update { it.copy(event = null) }
        scope.launch {
            generationLock.withLock {
                val held = held()
                if (held != null && !held.sameAskAs(fresh)) {
                    // An earlier one never got its answer, or the form changed while it was
                    // held: the server goes on with it and charges it if it is written, so it is
                    // settled (and saved, and announced) before anything new. The server would
                    // answer BUSY to a new one meanwhile anyway.
                    when (val earlier = send(held, interactive = false)) {
                        Sent.SETTLED -> Unit
                        // Too busy even to take the earlier one: the new one would fare no better.
                        Sent.DROPPED_BUSY -> {
                            state.update { it.copy(event = RecipeEvent.ServerFailedLater, paid = it.paid.copy(writing = null, serverBusy = false)) }
                            return@withLock
                        }
                        Sent.KEPT_BUSY, Sent.KEPT_OFFLINE -> {
                            val issue = if (earlier == Sent.KEPT_BUSY) ServerIssue.BUSY else ServerIssue.OFFLINE
                            updatePaid { it.copy(writing = null, serverBusy = false, issue = if (it.issue == ServerIssue.PAUSED) it.issue else issue) }
                            return@withLock
                        }
                    }
                }
                // Settling the earlier one may have had a name refused that this one carries too:
                // built again without it rather than refused a second time.
                var next = built
                if (fresh.ingredients.any { it.name.lowercase() in refusedIngredients } ||
                    fresh.allergens.any { it.lowercase() in refusedAllergens }
                ) {
                    when (val rebuilt = buildRequest(current)) {
                        is Built.Blocked -> {
                            state.update { it.copy(event = rebuilt.event, paid = it.paid.copy(writing = null, serverBusy = false)) }
                            return@withLock
                        }
                        is Built.Ready -> next = rebuilt
                    }
                }
                val kept = snapshot.pendingGeneration?.takeIf { it.sameAskAs(next.request) }
                val request = kept ?: next.request
                if (kept == null) persist { it.copy(pendingGeneration = request, pendingSinceMillis = nowMillis()) }
                updatePaid { it.copy(writing = if (request.kind == WireKind.MENU) RecipeMode.MENU else RecipeMode.RECIPE) }
                // Already sent once with these inputs: asked about, not started again.
                send(request, interactive = true, start = kept == null, leftOut = next.leftOut)
            }
        }
    }

    fun retry() {
        val current = state.value
        if (current.isBusy || !current.paid.available) return
        val held = held()
        if (held == null) {
            scope.launch {
                if (account.state.value.kind == AccountKind.NONE) {
                    updatePaid { it.copy(issue = null) }
                } else {
                    refreshBalance()
                }
            }
            return
        }
        updatePaid { it.copy(writing = if (held.kind == WireKind.MENU) RecipeMode.MENU else RecipeMode.RECIPE, issue = null) }
        scope.launch {
            generationLock.withLock {
                // A recovery in the background may have settled it while this waited for the lock.
                val still = held()
                if (still == null || still.requestId != held.requestId) {
                    updatePaid { it.copy(writing = null) }
                } else {
                    send(still, interactive = true)
                }
            }
        }
    }

    private suspend fun recoverGeneration() {
        if (!generationLock.tryLock()) return
        try {
            val held = held() ?: return
            recoveryFailedAt = if (send(held, interactive = false) == Sent.SETTLED) null else nowMillis()
        } finally {
            generationLock.unlock()
        }
    }

    /** How a [send] ended. */
    private enum class Sent {
        /** Written, failed or refused: no longer held. */
        SETTLED,

        /** Kept, with its id: the connection was lost, or the server gave no final answer in time. */
        KEPT_OFFLINE,

        /** Kept, with its id: the server holds it but stayed too busy to say how it went. */
        KEPT_BUSY,

        /** Dropped: the server stayed too busy to take it at all, so it holds nothing for it
         *  and nothing was taken. Never kept, or it would be started later unasked. */
        DROPPED_BUSY
    }

    /**
     * Sends [request] until the server gives a final answer, polling while it says IN_PROGRESS
     * and backing off while it is busy. [interactive] runs are the ones the person is watching:
     * they report events and clear the writing state. Any other run (a recovery, or an earlier
     * request settled before a new one) saves what arrives and says so with a [PaidEvent].
     */
    private suspend fun send(
        request: GenerateRequest,
        interactive: Boolean,
        start: Boolean = false,
        /** Items left out of [request] because the server refused their names: told with the result. */
        leftOut: List<String> = emptyList(),
        /** Already sent once with a refused item left out: a second refusal isn't retried. */
        retried: Boolean = false
    ): Sent {
        // What it takes as asked, before a reply can bring new costs.
        val cost = (snapshot.costs ?: RecipeCosts()).of(request.kind, request.courses)
        val began = nowMillis()
        val deadline = began + if (request.kind == WireKind.MENU) MENU_POLL_MILLIS else RECIPE_POLL_MILLIS
        val menu = request.kind == WireKind.MENU
        var starting = start
        var failures = 0
        // Whether the last busy answer was this account's other generation (BUSY) rather than
        // every server instance (OVERLOADED): told apart when the wait runs out.
        var accountBusy = false
        fun busy(on: Boolean) {
            if (interactive && state.value.paid.serverBusy != on) updatePaid { it.copy(serverBusy = on) }
        }
        while (true) {
            var fresh = starting
            val result = try {
                ask(request, starting) { fresh = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerResult.Err(ServerError.OFFLINE)
            }
            when (result) {
                is ServerResult.Ok -> {
                    val reply = result.value
                    busy(false)
                    when (reply.status) {
                        GenerateStatus.DONE -> {
                            val saved = savedFrom(request, reply)
                            if (saved == null) {
                                // The contract promises the content with DONE; without it there is
                                // nothing to show, and asking again with this id returns the same.
                                persist { it.copy(pendingGeneration = null, balance = reply.balance).withTerms(reply.costs, reply.purchasesBlocked) }
                                finish(interactive, RecipeEvent.ServerFailed)
                                return Sent.SETTLED
                            }
                            savedRecipeRepository.save(saved)
                            persist { it.copy(pendingGeneration = null, balance = reply.balance).withTerms(reply.costs, reply.purchasesBlocked) }
                            updatePaid { if (it.issue == ServerIssue.OFFLINE || it.issue == ServerIssue.BUSY) it.copy(issue = null) else it }
                            if (interactive) {
                                state.update { current ->
                                    current.copy(
                                        saved = listOf(saved) + current.saved.filterNot { it.id == saved.id },
                                        event = RecipeEvent.Written(saved.id, reply.balance, leftOut),
                                        paid = current.paid.copy(writing = null)
                                    )
                                }
                            } else {
                                updatePaid { it.copy(event = PaidEvent.EarlierSaved(menu)) }
                            }
                            return Sent.SETTLED
                        }
                        GenerateStatus.IN_PROGRESS -> {
                            // Whatever the first call was, the rest only ask.
                            starting = false
                            failures = 0
                            // The balance it reports is held, not spent: the count waits for the
                            // result. The costs and the block stand as they are.
                            persist { it.withTerms(reply.costs, reply.purchasesBlocked) }
                            if (nowMillis() >= deadline) {
                                finish(interactive, null, ServerIssue.OFFLINE)
                                return Sent.KEPT_OFFLINE
                            }
                            pollDelay(if (nowMillis() - began < SLOW_POLL_AFTER_MILLIS) POLL_MILLIS else SLOW_POLL_MILLIS)
                        }
                        GenerateStatus.FAILED -> {
                            persist { it.copy(pendingGeneration = null, balance = reply.balance).withTerms(reply.costs, reply.purchasesBlocked) }
                            updatePaid { if (it.issue == ServerIssue.OFFLINE || it.issue == ServerIssue.BUSY) it.copy(issue = null) else it }
                            if (!interactive && reply.charged) updatePaid { it.copy(event = PaidEvent.EarlierRefused(cost)) }
                            finish(interactive, if (reply.charged) RecipeEvent.ServerRefused(cost) else RecipeEvent.ServerFailed)
                            return Sent.SETTLED
                        }
                    }
                }
                // Every server instance busy (no reason, HTTP 429), or on a first send this
                // account's other generation still running: this call took nothing. The same
                // request, same id, again after a growing wait; the person sees a calm "busy"
                // state rather than an error.
                is ServerResult.Err if result.error == ServerError.OVERLOADED || (fresh && result.error == ServerError.BUSY) -> {
                    busy(true)
                    accountBusy = result.error == ServerError.BUSY
                    if (nowMillis() >= deadline) {
                        if (fresh) {
                            // Refused as it was started: the server holds nothing for this id.
                            // Dropped, so it is never started later without the person asking.
                            persist { it.copy(pendingGeneration = null, pendingSinceMillis = null) }
                            finish(interactive, if (accountBusy) RecipeEvent.ServerBusy else RecipeEvent.ServerFailedLater)
                            return Sent.DROPPED_BUSY
                        }
                        // Asking about one the server holds: kept, as it may still be written.
                        finish(interactive, null, ServerIssue.BUSY)
                        return Sent.KEPT_BUSY
                    }
                    // Known not to be on the server: the next try starts it again directly.
                    if (fresh) starting = true
                    pollDelay(backoffMillis(failures++))
                }
                // Asking about a request the server may already hold (a poll, a recovery): a limit or
                // a refusal on that call says nothing about the request, which may still be
                // written and charged. Keep it and ask again later, as for a lost connection.
                is ServerResult.Err if !fresh && result.error in KEEP_WHEN_ASKING -> {
                    if (nowMillis() >= deadline) {
                        finish(interactive, null, ServerIssue.OFFLINE)
                        return Sent.KEPT_OFFLINE
                    }
                    pollDelay(if (result.error == ServerError.RATE_LIMITED) RATE_LIMIT_WAIT_MILLIS else backoffMillis(failures++))
                }
                is ServerResult.Err -> when (result.error) {
                    // Kept, with its id: asking again can't charge twice.
                    ServerError.OFFLINE -> {
                        finish(interactive, null, ServerIssue.OFFLINE)
                        return Sent.KEPT_OFFLINE
                    }
                    // A replayed App Check token was already asked again once with a fresh one; the
                    // request is kept like any other failure that can't have charged.
                    ServerError.INTERNAL, ServerError.APP_CHECK_REPLAYED -> {
                        finish(interactive, RecipeEvent.ServerFailed)
                        return Sent.KEPT_OFFLINE
                    }
                    // A request kept from before the consent step, sent without the flag: dropped
                    // (nothing was charged); Try again builds it anew, past the step.
                    ServerError.ALLERGEN_CONSENT_REQUIRED -> {
                        persist { it.copy(pendingGeneration = null, pendingSinceMillis = null) }
                        finish(interactive, RecipeEvent.ServerFailed)
                        return Sent.SETTLED
                    }
                    ServerError.REQUEST_ID_REUSED -> {
                        // The id is bound to other inputs: never send it again. Try again builds
                        // a new request with a new id.
                        persist { it.copy(pendingGeneration = null, pendingSinceMillis = null) }
                        finish(interactive, RecipeEvent.ServerFailed)
                        return Sent.SETTLED
                    }
                    ServerError.USER_DAILY_LIMIT, ServerError.DAILY_CAP -> {
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, RecipeEvent.ServerLimitToday)
                        return Sent.SETTLED
                    }
                    ServerError.INFRA_FAILURE_LIMIT -> {
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, RecipeEvent.ServerTroubleToday)
                        return Sent.SETTLED
                    }
                    ServerError.UNAUTHENTICATED -> {
                        markUnavailable()
                        finish(interactive, RecipeEvent.ServerFailed)
                        return Sent.KEPT_OFFLINE
                    }
                    ServerError.INSUFFICIENT_CREDITS -> {
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, null)
                        refreshBalance()
                        if (interactive) openPacks()
                        return Sent.SETTLED
                    }
                    ServerError.GENERATION_DISABLED -> {
                        persist { it.copy(pendingGeneration = null) }
                        pausedAt = nowMillis()
                        finish(interactive, null, ServerIssue.PAUSED)
                        return Sent.SETTLED
                    }
                    ServerError.ACCOUNT_CLOSED -> {
                        handleCommonError(ServerError.ACCOUNT_CLOSED)
                        finish(interactive, RecipeEvent.ServerFailed)
                        return Sent.SETTLED
                    }
                    ServerError.INVALID_REQUEST -> {
                        persist { it.copy(pendingGeneration = null, pendingSinceMillis = null) }
                        val refused = result.refusedName
                        val refusedIndex = refused?.index ?: -1
                        when (refused?.list) {
                            RefusedList.ALLERGEN -> {
                                // Never dropped: a recipe written without it could be unsafe.
                                val allergen = request.allergens.getOrNull(refusedIndex)
                                if (allergen != null) refusedAllergens += allergen.lowercase()
                                finish(interactive, allergen?.let { RecipeEvent.AllergenNotSent(it) } ?: RecipeEvent.ServerFailedLater)
                                return Sent.SETTLED
                            }
                            RefusedList.INGREDIENT -> {
                                val ingredient = request.ingredients.getOrNull(refusedIndex)
                                if (ingredient == null) {
                                    finish(interactive, RecipeEvent.ServerFailedLater)
                                    return Sent.SETTLED
                                }
                                val refusedKey = ingredient.name.lowercase()
                                refusedIngredients += refusedKey
                                // Every row of that name: another carton of the same milk would be
                                // refused just the same.
                                val rest = request.ingredients.filterNot { it.name.lowercase() == refusedKey }
                                // Once, and only while the person waits: repeated refusals lock the
                                // account for the day, so this never loops.
                                if (!interactive || retried || rest.isEmpty()) {
                                    finish(interactive, RecipeEvent.IngredientNotSent(ingredient.name))
                                    return Sent.SETTLED
                                }
                                // Other inputs: a new id (REQUEST_ID_REUSED otherwise).
                                val again = request.copy(requestId = newRequestId(), ingredients = rest)
                                persist { it.copy(pendingGeneration = again, pendingSinceMillis = nowMillis()) }
                                return send(again, interactive, start = true, leftOut = leftOut + ingredient.name, retried = true)
                            }
                            null -> {
                                finish(interactive, RecipeEvent.ServerFailed)
                                return Sent.SETTLED
                            }
                        }
                    }
                    else -> {
                        // On a first send: the per-minute limit, a reason this app doesn't know
                        // yet. Refused before anything was held; asking again at once won't help.
                        persist { it.copy(pendingGeneration = null) }
                        val event = if (result.error == ServerError.RATE_LIMITED) RecipeEvent.ServerRateLimited else RecipeEvent.ServerFailedLater
                        finish(interactive, event)
                        return Sent.SETTLED
                    }
                }
            }
        }
    }

    /**
     * One call for [request]: `generateRecipe` to [start] it (a fresh limited-use App Check
     * token, which is a Play Integrity request), `getGeneration` for everything after, so polls
     * and recoveries spend none. Falls back to `generateRecipe`, same id and inputs, when the
     * server never got the request or predates `getGeneration`.
     */
    private suspend fun ask(request: GenerateRequest, start: Boolean, onFreshStart: () -> Unit): ServerResult<GenerateReply> {
        if (start || getGenerationMissing) return backend.generate(request)
        val polled = backend.getGeneration(request.requestId)
        if (polled is ServerResult.Err) {
            when (polled.error) {
                ServerError.UNKNOWN_REQUEST -> {
                    // The server never got it: this send starts it, and may be refused as one.
                    onFreshStart()
                    return backend.generate(request)
                }
                ServerError.CALLABLE_MISSING -> {
                    getGenerationMissing = true
                    return backend.generate(request)
                }
                else -> Unit
            }
        }
        return polled
    }

    private fun finish(interactive: Boolean, event: RecipeEvent?, issue: ServerIssue? = null) {
        state.update { current ->
            val paid = current.paid.let { paid ->
                val withIssue = when {
                    issue == ServerIssue.PAUSED -> paid.copy(issue = ServerIssue.PAUSED)
                    issue != null && interactive && paid.issue == null -> paid.copy(issue = issue)
                    else -> paid
                }
                if (interactive) withIssue.copy(writing = null, serverBusy = false) else withIssue
            }
            current.copy(paid = paid, event = if (interactive && event != null) event else current.event)
        }
    }

    private fun savedFrom(request: GenerateRequest, reply: GenerateReply): SavedRecipe? {
        val created = nowMillis()
        return when (request.kind) {
            // `fromPantry` is the server's (it checks the model's claim against what was sent); the
            // phone's own matching is only for recipes the phone wrote (parseRecipeReply).
            WireKind.RECIPE -> reply.recipe?.let { recipe ->
                SavedRecipe.of(newSavedId(), recipe.copy(source = RecipeSource.SERVER), created)
            }
            WireKind.MENU -> reply.menu?.takeIf { it.days.isNotEmpty() }?.let { menu ->
                val serverMenu = Menu(
                    menu.days.map { day ->
                        day.copy(courses = day.courses.map { it.copy(recipe = it.recipe.copy(source = RecipeSource.SERVER)) })
                    }
                )
                SavedRecipe.of(newSavedId(), MENU_SAVED_TITLE, serverMenu, created)
            }
        }
    }

    private companion object {
        /** The pack the sheet selects first, as the design has it. */
        const val DEFAULT_PACK = "recipes_50"
    }
}
