package com.foodtracker.recipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The paid route: our server writing recipes from packs bought in the store, and the account the
 * recipes belong to. The wire shapes follow functions/CONTRACT.md, which is authoritative; iOS
 * mirrors them by hand.
 *
 * Nothing here is touched by someone who never starts a purchase: no Auth user, App Check token
 * or server call exists until then (see RecipePaid).
 */

/** The three packs, as the store sells them, and how many recipes one unit of each gives. The
 *  server keeps the same table; the client only shows it. */
val RECIPE_PACKS: Map<String, Int> = linkedMapOf("recipes_10" to 10, "recipes_50" to 50, "recipes_100" to 100)

/** What one generation takes from a pack. The server decides; this is only what the app says. */
const val RECIPE_COST = 1
const val MENU_COST = 3

// --- Wire types -------------------------------------------------------------------------------

@Serializable
enum class WireKind {
    @SerialName("recipe") RECIPE,
    @SerialName("menu") MENU
}

@Serializable
data class WireIngredient(
    val name: String,
    /** `YYYY-MM-DD`, or null for an item without a date. */
    val expiresOn: String?,
    val quantity: Int?
)

/** `generateRecipe`'s request. Kept as it was sent, so a retry sends exactly the same thing with
 *  the same [requestId]. */
@Serializable
data class GenerateRequest(
    val requestId: String,
    val kind: WireKind,
    /** 2 or 3 for a menu; null for a recipe. */
    val courses: Int? = null,
    /** With [RecipeConfig.language] resolved, as the server requires a code. */
    val config: RecipeConfig,
    val ingredients: List<WireIngredient>,
    val allergens: List<String>,
    val today: String
) {
    val cost: Int get() = if (kind == WireKind.MENU) MENU_COST else RECIPE_COST

    /** Whether [other] asks for the same thing, whatever its id. */
    fun sameAskAs(other: GenerateRequest): Boolean = copy(requestId = other.requestId) == other
}

@Serializable
enum class GenerateStatus {
    DONE,
    IN_PROGRESS,
    FAILED
}

@Serializable
data class GenerateReply(
    val status: GenerateStatus,
    val recipe: Recipe? = null,
    val menu: Menu? = null,
    val balance: Int,
    val charged: Boolean = false
)

@Serializable
enum class VerifyStatus {
    GRANTED,
    ALREADY_GRANTED,
    REJECTED_PERMANENT,
    RETRY
}

@Serializable
data class VerifyReply(val status: VerifyStatus, val balance: Int? = null)

/** A failed call, by the contract's `details.reason`, plus the two the client sees itself. */
enum class ServerError {
    /** No connection, or the call timed out. Safe to retry with the same request id. */
    OFFLINE,
    INVALID_REQUEST,
    INSUFFICIENT_CREDITS,
    BUSY,
    GENERATION_DISABLED,
    ACCOUNT_CLOSED,
    RATE_LIMITED,
    DAILY_CAP,
    RECENT_SIGN_IN_REQUIRED,
    MERGE_NOT_ALLOWED,

    /** `internal`: a server bug or outage. Safe to retry with the same request id. */
    INTERNAL,

    /** No Auth user or no valid App Check token: this build can't use the server. */
    UNAUTHENTICATED;

    companion object {
        /** The error for a callable failure's `details.reason`, or [fallback] when it has none. */
        fun fromReason(reason: String?, fallback: ServerError): ServerError =
            entries.firstOrNull { it.name == reason } ?: fallback
    }
}

sealed interface ServerResult<out T> {
    data class Ok<T>(val value: T) : ServerResult<T>
    data class Err(val error: ServerError) : ServerResult<Nothing>
}

// --- Platform interfaces ----------------------------------------------------------------------

/** The Cloud Functions callables (functions/CONTRACT.md). Every call needs a user, which
 *  [RecipeAccount.ensureUser] or a sign-in creates first. */
interface RecipeBackend {
    /** Whether this build can pass App Check at all: false for forks and sideloads, so the paid
     *  route is hidden there and the free routes stay. Known without any network call. */
    val isAvailable: Boolean
    suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply>
    suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply>
    suspend fun getBalance(): ServerResult<Int>

    /** Deletes the signed-in account on the server. The caller signs out afterwards. */
    suspend fun deleteAccount(): ServerResult<Unit>
}

/** A pack as the store prices it, in the person's currency. */
data class StorePack(
    val productId: String,
    val recipes: Int,
    /** The store's own formatted price, such as "£7.99". */
    val price: String,
    val priceMicros: Long,
    val currencyCode: String
)

/** A purchase the store holds and the server hasn't consumed yet. */
data class StorePurchase(
    val token: String,
    val productIds: List<String>,
    val quantity: Int,
    /** Waiting for payment: cash top-up, bank approval and the like. Never sent to the server. */
    val pending: Boolean
) {
    val recipes: Int get() = productIds.sumOf { RECIPE_PACKS[it] ?: 0 } * quantity.coerceAtLeast(1)
}

/** What the store reports after a purchase flow, or on its own when a pending payment clears. */
sealed interface StoreUpdate {
    data class Purchases(val purchases: List<StorePurchase>) : StoreUpdate
    data object Cancelled : StoreUpdate

    /** An unconsumed purchase of the same pack is still in the store: one to resubmit. */
    data object AlreadyOwned : StoreUpdate

    /** The store can't be reached or purchases are off on this device. */
    data object Unavailable : StoreUpdate
}

/** The platform store: Play Billing on Android. Consumables only; the client never consumes or
 *  acknowledges (the server consumes). */
interface RecipeStore {
    /** Whether this build and device have a store to buy from at all. */
    val isSupported: Boolean
    val updates: Flow<StoreUpdate>

    /** The packs with their prices, or null when the store can't be reached. */
    suspend fun packs(): List<StorePack>?

    /** Opens the store's own payment sheet. False when it couldn't be opened; the outcome comes
     *  through [updates]. */
    suspend fun buy(productId: String): Boolean

    /** Purchases still held by the store, or null when it can't be asked right now. */
    suspend fun purchases(): List<StorePurchase>?
}

enum class AccountKind {
    /** No Auth user: nothing has been bought on this install, or the person signed out. */
    NONE,

    /** An anonymous user: recipes stay with this install. */
    ANONYMOUS,
    SIGNED_IN
}

enum class SignInProvider { GOOGLE, APPLE }

data class AccountState(
    val kind: AccountKind = AccountKind.NONE,
    val providers: Set<SignInProvider> = emptySet(),
    /** As the provider gave it: Apple's relay address is shown as it is. */
    val email: String? = null
)

/** A sign-in whose credential already has its own account while this install holds an
 *  anonymous one: the two can only be joined by moving this install's recipes over. */
interface PendingMerge {
    /** The account's own balance, or null when it couldn't be read. */
    val accountBalance: Int?
}

sealed interface SignInResult {
    data object SignedIn : SignInResult

    /** See [PendingMerge]. Nothing has changed yet; [RecipeAccount.completeMerge] or
     *  [RecipeAccount.cancelMerge] decides. */
    data class NeedsMerge(val merge: PendingMerge) : SignInResult

    /** Adding a sign-in to a signed-in account whose credential belongs to another account. */
    data object InUse : SignInResult
    data object Cancelled : SignInResult
    data object Failed : SignInResult
}

/** Firebase Auth, as the paid route needs it. */
interface RecipeAccount {
    val state: StateFlow<AccountState>

    /** The sign-ins this build offers; Google needs a configured web client id. */
    val providers: Set<SignInProvider>

    /** Makes sure there is a user, creating an anonymous one if there is none. Only called once
     *  a purchase starts or a held purchase has to be sent. */
    suspend fun ensureUser(): Boolean

    /**
     * With no user, signs in. With an anonymous user, links the sign-in to it, so the uid and
     * the balance stay. With a signed-in user, adds the sign-in to that account.
     */
    suspend fun signIn(provider: SignInProvider): SignInResult

    /** Moves this install's recipes into the account and switches to it. Returns the joined
     *  balance. */
    suspend fun completeMerge(merge: PendingMerge): ServerResult<Int>
    suspend fun cancelMerge(merge: PendingMerge)

    /** Whether deletion has to sign in again first: the last sign-in is too old for
     *  `deleteAccount`, or the provider's token has to be revoked from a fresh one (Apple). */
    fun needsFreshSignIn(): Boolean

    /** Signs in again with the account's own provider, as deletion requires, and revokes that
     *  provider's token where the provider asks for it (Apple). False when cancelled or failed. */
    suspend fun reauthenticateForDeletion(): Boolean
    suspend fun signOut()
}

/** What the paid route keeps on this phone between launches. */
@Serializable
data class UnverifiedPurchase(val token: String, val recipes: Int)

@Serializable
data class RecipePaidSnapshot(
    /** The last balance the server reported, shown until it reports again. */
    val balance: Int? = null,
    /** Paid in the store, not yet confirmed by the server: shown as "being confirmed" until it is,
     *  across restarts, and Buy stays hidden meanwhile so nobody pays twice. */
    val unverified: List<UnverifiedPurchase> = emptyList(),
    val paymentPending: Boolean = false,
    /** A generation whose answer never arrived. Sent again, with the same id, before anything
     *  new, since the server may have written it and taken a recipe for it. */
    val pendingGeneration: GenerateRequest? = null,
    /** When [pendingGeneration] was first sent. The server keeps an answer for 7 days; past a day
     *  the request is dropped rather than risk it being written, and charged, anew. */
    val pendingSinceMillis: Long? = null
)

interface RecipePaidRepository {
    fun get(): RecipePaidSnapshot
    fun save(snapshot: RecipePaidSnapshot)
}

// --- Stand-ins for builds without the paid route ----------------------------------------------

object NotAvailableRecipeBackend : RecipeBackend {
    override val isAvailable: Boolean = false
    override suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply> = ServerResult.Err(ServerError.UNAUTHENTICATED)
    override suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply> = ServerResult.Err(ServerError.UNAUTHENTICATED)
    override suspend fun getBalance(): ServerResult<Int> = ServerResult.Err(ServerError.UNAUTHENTICATED)
    override suspend fun deleteAccount(): ServerResult<Unit> = ServerResult.Err(ServerError.UNAUTHENTICATED)
}

object NotAvailableRecipeStore : RecipeStore {
    override val isSupported: Boolean = false
    override val updates: Flow<StoreUpdate> = emptyFlow()
    override suspend fun packs(): List<StorePack>? = null
    override suspend fun buy(productId: String): Boolean = false
    override suspend fun purchases(): List<StorePurchase>? = null
}

object NoRecipeAccount : RecipeAccount {
    override val state: StateFlow<AccountState> = MutableStateFlow(AccountState()).asStateFlow()
    override val providers: Set<SignInProvider> = emptySet()
    override suspend fun ensureUser(): Boolean = false
    override suspend fun signIn(provider: SignInProvider): SignInResult = SignInResult.Failed
    override suspend fun completeMerge(merge: PendingMerge): ServerResult<Int> = ServerResult.Err(ServerError.UNAUTHENTICATED)
    override suspend fun cancelMerge(merge: PendingMerge) = Unit
    override fun needsFreshSignIn(): Boolean = false
    override suspend fun reauthenticateForDeletion(): Boolean = false
    override suspend fun signOut() = Unit
}

/** Keeps nothing: for tests and builds without the paid route. */
class InMemoryRecipePaidRepository(private var snapshot: RecipePaidSnapshot = RecipePaidSnapshot()) : RecipePaidRepository {
    override fun get(): RecipePaidSnapshot = snapshot
    override fun save(snapshot: RecipePaidSnapshot) {
        this.snapshot = snapshot
    }
}

// --- Request building -------------------------------------------------------------------------

/** The contract's limits on what is sent. */
const val MAX_REQUEST_INGREDIENTS = 30
const val MAX_REQUEST_ALLERGENS = 20
const val MAX_WIRE_NAME_LENGTH = 40

/**
 * A pantry name as the server accepts it: letters of any script, digits, spaces and hyphens,
 * 1 to 40 characters. Anything else becomes a space ("Ben & Jerry's" → "Ben Jerry s"); null when
 * nothing is left.
 */
fun cleanWireName(name: String): String? {
    val cleaned = buildString {
        for (char in name) append(if (char.isLetterOrDigit() || char == '-' || char == ' ') char else ' ')
    }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    return cleaned.take(MAX_WIRE_NAME_LENGTH).trim().takeIf { it.isNotEmpty() }
}
