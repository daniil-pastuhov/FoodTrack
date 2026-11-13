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
    PAUSED
}

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
    /** A purchase the server rejected for good: shown until dismissed (T6). */
    val purchaseRejected: Boolean = false,
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
    val costs: RecipeCosts = RecipeCosts()
) {
    /** Buying is offered: only once the store has shown it has packs, so there is never a Buy
     *  that leads nowhere; hidden while the server is paused, and while a payment is waiting or
     *  unconfirmed, so nobody pays twice. */
    val canBuy: Boolean
        get() = available && packsOffered == true && issue != ServerIssue.PAUSED && unconfirmedRecipes == 0 && !paymentPending
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
    private val pollDelay: suspend () -> Unit
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
        updatePaid {
            it.copy(
                available = shown(it.packsOffered),
                balance = kept.balance,
                costs = kept.costs ?: RecipeCosts(),
                unconfirmedRecipes = kept.unverified.sumOf { purchase -> purchase.recipes },
                paymentPending = kept.paymentPending
            )
        }
    }

    /** Whether the route is on screen: only once the store has shown it sells this build a
     *  pack, unless something is held already (a count, a purchase, a payment, a generation),
     *  which always shows, so an unreachable store never hides what was paid for. */
    private fun shown(packsOffered: Boolean?): Boolean = available && (packsOffered == true || !holdsNothing())

    fun start() {
        updatePaid { it.copy(available = shown(it.packsOffered)) }
        // Not even the account is looked at on a build without the paid route.
        if (!available) return
        updatePaid {
            it.copy(
                balance = snapshot.balance,
                costs = snapshot.costs ?: RecipeCosts(),
                account = account.state.value,
                providers = account.providers,
                unconfirmedRecipes = snapshot.unverified.sumOf { purchase -> purchase.recipes },
                paymentPending = snapshot.paymentPending
            )
        }
        scope.launch {
            account.state.collect { current ->
                if (current.kind == AccountKind.NONE && snapshot.balance != null) persist { it.copy(balance = null) }
                updatePaid { it.copy(account = current) }
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
            scope.launch { resubmitPurchases() }
            if (held() != null && state.value.paid.writing == null) {
                scope.launch { recoverGeneration() }
            }
        }
        val asked = balanceAskedAt
        if (account.state.value.kind != AccountKind.NONE && (asked == null || nowMillis() - asked >= BALANCE_REFRESH_MILLIS)) {
            scope.launch { refreshBalance() }
        }
    }

    private suspend fun refreshBalance(): Boolean {
        if (account.state.value.kind == AccountKind.NONE) return false
        balanceAskedAt = nowMillis()
        return when (val result = backend.getBalance()) {
            is ServerResult.Ok -> {
                persist { it.copy(balance = result.value.balance, costs = result.value.costs ?: it.costs) }
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
                markUnavailable()
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
        updatePaid { it.copy(purchaseRejected = false) }
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
        filter { purchase -> known.none { it.token == purchase.token } }
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
        if (snapshot.unverified.isEmpty()) {
            closePurchasingSheet()
            return
        }
        // A purchase made, then the app reinstalled or signed out: it still needs someone to
        // belong to.
        if (account.state.value.kind == AccountKind.NONE && !account.ensureUser()) {
            closePurchasingSheet()
            return
        }
        updatePaid { it.copy(verifying = true) }
        for (purchase in snapshot.unverified.toList()) {
            val result = try {
                backend.verifyPurchase(purchase.token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerResult.Err(ServerError.OFFLINE)
            }
            when (result) {
                is ServerResult.Ok -> {
                    val reply = result.value
                    val settled = reply.status != VerifyStatus.RETRY
                    persist { current ->
                        current.copy(
                            unverified = if (settled) current.unverified.filterNot { it.token == purchase.token } else current.unverified,
                            balance = reply.balance ?: current.balance,
                            costs = recipeCostsOf(reply.costs) ?: current.costs
                        )
                    }
                    when (reply.status) {
                        VerifyStatus.GRANTED -> updatePaid { it.copy(event = PaidEvent.RecipesAdded(purchase.recipes)) }
                        VerifyStatus.REJECTED_PERMANENT -> updatePaid { it.copy(purchaseRejected = true) }
                        VerifyStatus.ALREADY_GRANTED, VerifyStatus.RETRY -> Unit
                    }
                }
                is ServerResult.Err -> {
                    if (result.error != ServerError.OFFLINE) handleCommonError(result.error)
                    if (!available) break
                }
            }
        }
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
            // Nothing on this phone to lose or to ask about.
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
        when (val result = account.completeMerge(merge)) {
            is ServerResult.Ok -> {
                persist { it.copy(balance = result.value) }
                updatePaid { it.copy(accountBusy = false, merge = null) }
            }
            is ServerResult.Err -> {
                handleCommonError(result.error)
                updatePaid { it.copy(accountBusy = false, merge = null, event = PaidEvent.SignInFailed) }
            }
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
            var result = backend.deleteAccount()
            if (result is ServerResult.Err && result.error == ServerError.RECENT_SIGN_IN_REQUIRED && signedIn && !reauthenticated) {
                if (!account.reauthenticateForDeletion()) {
                    updatePaid { it.copy(accountBusy = false) }
                    return@launch
                }
                result = backend.deleteAccount()
            }
            when (result) {
                is ServerResult.Ok -> {
                    runCatching { account.signOut() }
                    persist { it.copy(balance = null, pendingGeneration = null) }
                    updatePaid { it.copy(accountBusy = false, issue = null, event = PaidEvent.AccountDeleted) }
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

    // --- Generation --------------------------------------------------------------------------

    private fun buildRequest(current: RecipeUiState): GenerateRequest? {
        val ingredients = current.selectedRows
            .mapNotNull { row ->
                cleanWireName(row.item.name)?.let { name ->
                    WireIngredient(name, row.item.expiryDate.toString(), row.item.quantity.coerceIn(0, 9999))
                }
            }
            .take(MAX_REQUEST_INGREDIENTS)
        if (ingredients.isEmpty()) return null
        val allergens = current.allergens.mapNotNull(::cleanWireName).distinctBy { it.lowercase() }.take(MAX_REQUEST_ALLERGENS)
        val menu = current.mode == RecipeMode.MENU
        return GenerateRequest(
            requestId = newRequestId(),
            kind = if (menu) WireKind.MENU else WireKind.RECIPE,
            courses = if (menu) current.courses else null,
            config = current.config.copy(language = current.language),
            ingredients = ingredients,
            allergens = allergens,
            today = today().toString()
        )
    }

    fun generate() {
        val current = state.value
        if (current.isBusy || !current.paid.available || current.selectedRows.isEmpty()) return
        if (current.paid.issue == ServerIssue.PAUSED) return
        if ((current.paid.balance ?: 0) < current.serverCost) {
            openPacks()
            return
        }
        val fresh = buildRequest(current)
        if (fresh == null) {
            state.update { it.copy(event = RecipeEvent.ServerFailed) }
            return
        }
        updatePaid { it.copy(writing = current.mode) }
        state.update { it.copy(event = null) }
        scope.launch {
            generationLock.withLock {
                val held = held()
                if (held != null && !held.sameAskAs(fresh)) {
                    // An earlier one never got its answer and may have been written and paid
                    // for: settle it before anything new.
                    if (!send(held, interactive = false)) {
                        updatePaid { it.copy(writing = null, issue = it.issue ?: ServerIssue.OFFLINE) }
                        return@withLock
                    }
                }
                val kept = snapshot.pendingGeneration?.takeIf { it.sameAskAs(fresh) }
                val request = kept ?: fresh
                if (kept == null) persist { it.copy(pendingGeneration = request, pendingSinceMillis = nowMillis()) }
                updatePaid { it.copy(writing = if (request.kind == WireKind.MENU) RecipeMode.MENU else RecipeMode.RECIPE) }
                // Already sent once with these inputs: asked about, not started again.
                send(request, interactive = true, start = kept == null)
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
            send(held, interactive = false)
        } finally {
            generationLock.unlock()
        }
    }

    /**
     * Sends [request] until the server gives a final answer, polling while it says IN_PROGRESS.
     * Returns true once the request is settled (written, failed or refused) and no longer held.
     * [interactive] runs are the ones the person is watching: they report events and clear the
     * writing state; a recovery in the background only saves what arrives.
     */
    private suspend fun send(request: GenerateRequest, interactive: Boolean, start: Boolean = false): Boolean {
        // What it takes as asked, before a reply can bring new costs.
        val cost = (snapshot.costs ?: RecipeCosts()).of(request.kind, request.courses)
        val deadline = nowMillis() + if (request.kind == WireKind.MENU) MENU_POLL_MILLIS else RECIPE_POLL_MILLIS
        var starting = start
        while (true) {
            val result = try {
                ask(request, starting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerResult.Err(ServerError.OFFLINE)
            }
            when (result) {
                is ServerResult.Ok -> {
                    val reply = result.value
                    when (reply.status) {
                        GenerateStatus.DONE -> {
                            val saved = savedFrom(request, reply)
                            if (saved == null) {
                                // The contract promises the content with DONE; without it there is
                                // nothing to show, and asking again with this id returns the same.
                                persist { it.copy(pendingGeneration = null, balance = reply.balance, costs = recipeCostsOf(reply.costs) ?: it.costs) }
                                finish(interactive, RecipeEvent.ServerFailed)
                                return true
                            }
                            savedRecipeRepository.save(saved)
                            persist { it.copy(pendingGeneration = null, balance = reply.balance, costs = recipeCostsOf(reply.costs) ?: it.costs) }
                            updatePaid { if (it.issue == ServerIssue.OFFLINE) it.copy(issue = null) else it }
                            if (interactive) {
                                state.update { current ->
                                    current.copy(
                                        saved = listOf(saved) + current.saved.filterNot { it.id == saved.id },
                                        event = RecipeEvent.Written(saved.id, reply.balance),
                                        paid = current.paid.copy(writing = null)
                                    )
                                }
                            }
                            return true
                        }
                        GenerateStatus.IN_PROGRESS -> {
                            // Whatever the first call was, the rest only ask.
                            starting = false
                            // The balance it reports is held, not spent: the count waits for the
                            // result.
                            if (nowMillis() >= deadline) {
                                finish(interactive, null, ServerIssue.OFFLINE)
                                return false
                            }
                            pollDelay()
                        }
                        GenerateStatus.FAILED -> {
                            persist { it.copy(pendingGeneration = null, balance = reply.balance, costs = recipeCostsOf(reply.costs) ?: it.costs) }
                            updatePaid { if (it.issue == ServerIssue.OFFLINE) it.copy(issue = null) else it }
                            finish(interactive, if (reply.charged) RecipeEvent.ServerRefused(cost) else RecipeEvent.ServerFailed)
                            return true
                        }
                    }
                }
                is ServerResult.Err -> when (result.error) {
                    // Kept, with its id: asking again can't charge twice.
                    ServerError.OFFLINE -> {
                        finish(interactive, null, ServerIssue.OFFLINE)
                        return false
                    }
                    // A replayed App Check token was already asked again once with a fresh one; the
                    // request is kept like any other failure that can't have charged.
                    ServerError.INTERNAL, ServerError.APP_CHECK_REPLAYED -> {
                        finish(interactive, RecipeEvent.ServerFailed)
                        return false
                    }
                    ServerError.REQUEST_ID_REUSED -> {
                        // The id is bound to other inputs: never send it again. Try again builds
                        // a new request with a new id.
                        persist { it.copy(pendingGeneration = null, pendingSinceMillis = null) }
                        finish(interactive, RecipeEvent.ServerFailed)
                        return true
                    }
                    ServerError.USER_DAILY_LIMIT, ServerError.DAILY_CAP -> {
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, RecipeEvent.ServerLimitToday)
                        return true
                    }
                    ServerError.INFRA_FAILURE_LIMIT -> {
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, RecipeEvent.ServerTroubleToday)
                        return true
                    }
                    ServerError.UNAUTHENTICATED -> {
                        markUnavailable()
                        finish(interactive, RecipeEvent.ServerFailed)
                        return false
                    }
                    ServerError.INSUFFICIENT_CREDITS -> {
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, null)
                        refreshBalance()
                        if (interactive) openPacks()
                        return true
                    }
                    ServerError.GENERATION_DISABLED -> {
                        persist { it.copy(pendingGeneration = null) }
                        pausedAt = nowMillis()
                        finish(interactive, null, ServerIssue.PAUSED)
                        return true
                    }
                    ServerError.ACCOUNT_CLOSED -> {
                        handleCommonError(ServerError.ACCOUNT_CLOSED)
                        finish(interactive, RecipeEvent.ServerFailed)
                        return true
                    }
                    ServerError.INVALID_REQUEST -> {
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, RecipeEvent.ServerFailed)
                        return true
                    }
                    else -> {
                        // Busy, the per-minute limit, a reason this app doesn't know yet: refused
                        // before anything was held, and asking again at once won't help.
                        persist { it.copy(pendingGeneration = null) }
                        finish(interactive, RecipeEvent.ServerFailedLater)
                        return true
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
    private suspend fun ask(request: GenerateRequest, start: Boolean): ServerResult<GenerateReply> {
        if (start || getGenerationMissing) return backend.generate(request)
        val polled = backend.getGeneration(request.requestId)
        if (polled is ServerResult.Err) {
            when (polled.error) {
                ServerError.UNKNOWN_REQUEST -> return backend.generate(request)
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
                    issue == ServerIssue.OFFLINE && interactive && paid.issue == null -> paid.copy(issue = ServerIssue.OFFLINE)
                    else -> paid
                }
                if (interactive) withIssue.copy(writing = null) else withIssue
            }
            current.copy(paid = paid, event = if (interactive && event != null) event else current.event)
        }
    }

    private fun savedFrom(request: GenerateRequest, reply: GenerateReply): SavedRecipe? {
        val created = nowMillis()
        return when (request.kind) {
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
