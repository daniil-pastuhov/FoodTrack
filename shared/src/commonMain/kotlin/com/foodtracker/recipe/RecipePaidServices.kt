package com.foodtracker.recipe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

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

/**
 * What one generation takes from a pack. The server decides and says so in a `costs` object on
 * every reply that carries a balance; this is the last one it sent, or these defaults before it
 * has sent any. The app only shows it.
 *
 * [menu2] and [menu3] are a 3-day menu's prices (2 and 3 courses a day). [menuSets] is the menu
 * set's table, days to courses to price (functions/CONTRACT.md "Costs"), from a server that says
 * `acceptsMenuDays`; empty from an older one and before any table arrived. iOS: `RecipeCosts`.
 */
@Serializable
data class RecipeCosts(
    val recipe: Int = 1,
    val menu2: Int = 4,
    val menu3: Int = 5,
    val menuSets: Map<Int, Map<Int, Int>> = emptyMap()
) {
    /** A 3-day menu of [courses] courses a day (2 or 3), from [menu2] / [menu3]. */
    fun menu(courses: Int): Int = if (courses >= 3) menu3 else menu2

    /**
     * What a menu of [days] days and [courses] courses a day takes: the table's price, or for 3
     * days with 2 or 3 courses [menu2] / [menu3]. Null when nobody said, so that combination is
     * not offered. Never a price made up on the phone.
     */
    fun menuSet(days: Int, courses: Int): Int? =
        menuSets[days]?.get(courses) ?: if (days == DEFAULT_MENU_DAYS && courses in LEGACY_MENU_COURSES) menu(courses) else null

    /** The cheapest and dearest menu set in [menuSets] (with [menu2] / [menu3]), for the packs
     *  text; null without a table, when the text says what a 3-day menu takes instead. */
    val menuSetRange: IntRange?
        get() {
            if (menuSets.isEmpty()) return null
            val prices = menuSets.values.flatMap { it.values } + menu2 + menu3
            return prices.min()..prices.max()
        }

    fun of(kind: WireKind, courses: Int?, days: Int? = null): Int =
        if (kind == WireKind.MENU) menuSet(days ?: DEFAULT_MENU_DAYS, courses ?: 2) ?: menu(courses ?: 2) else recipe
}

/** Days of a menu when a request doesn't say (an older server, and `days` left out). */
const val DEFAULT_MENU_DAYS = 3

/** The day counts a menu set can have, with a server that says `acceptsMenuDays`. */
val MENU_DAY_OPTIONS: List<Int> = listOf(1, 2, 3, 4)

/** The courses a day a menu can have with any server; [MENU_COURSE_OPTIONS] adds 1 (the main
 *  course only) with a server that says `acceptsMenuDays`. */
val LEGACY_MENU_COURSES: List<Int> = listOf(2, 3)

/** The costs in a reply's `costs`, read leniently: a missing, mistyped or unlikely value keeps the
 *  default for that one field, and anything that isn't an object is no costs at all. `menuSets`
 *  keeps only the day counts and courses the app knows ([MENU_DAY_OPTIONS], [MENU_COURSE_OPTIONS])
 *  with a whole price; anything else in it, and any other key, is ignored. */
fun recipeCostsOf(json: JsonElement?): RecipeCosts? {
    val costs = json as? JsonObject ?: return null
    val defaults = RecipeCosts()
    fun price(element: JsonElement?): Int? {
        val value = element as? JsonPrimitive ?: return null
        if (value.isString) return null
        val whole = value.intOrNull ?: value.doubleOrNull?.takeIf { it % 1.0 == 0.0 && it in 1.0..MAX_COST.toDouble() }?.toInt()
        return whole?.takeIf { it in 1..MAX_COST }
    }
    fun read(key: String, fallback: Int): Int = price(costs[key]) ?: fallback
    val sets = (costs["menuSets"] as? JsonObject).orEmpty().mapNotNull { (dayKey, row) ->
        val days = dayKey.toIntOrNull()?.takeIf { it in MENU_DAY_OPTIONS } ?: return@mapNotNull null
        val prices = (row as? JsonObject).orEmpty().mapNotNull { (courseKey, value) ->
            val courses = courseKey.toIntOrNull()?.takeIf { it in MENU_COURSE_OPTIONS } ?: return@mapNotNull null
            price(value)?.let { courses to it }
        }.toMap()
        prices.takeIf { it.isNotEmpty() }?.let { days to it }
    }.toMap()
    return RecipeCosts(read("recipe", defaults.recipe), read("menu2", defaults.menu2), read("menu3", defaults.menu3), sets)
}

private const val MAX_COST = 100

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
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GenerateRequest(
    val requestId: String,
    val kind: WireKind,
    /** 1, 2 or 3 for a menu (1 only to a server that said `acceptsMenuDays`); null for a recipe. */
    val courses: Int? = null,
    /** See [wireConfigFor]: the legacy shape unless the server takes the multi-choice one. */
    val config: WireConfig,
    val ingredients: List<WireIngredient>,
    val allergens: List<String>,
    val today: String,
    /** `true` with a non-empty [allergens], sent only after the person agreed to send them
     *  ([allergenConsentStep]); null otherwise. The server refuses allergens without it
     *  (ALLERGEN_CONSENT_REQUIRED). Not one of the inputs a request id is bound to. */
    val allergenConsent: Boolean? = null,
    /** The rest of the pantry, which the recipe may use as well ("Send all products"): only to
     *  a server that said [acceptsOptionalIngredientsOf], already capped by
     *  [optionalWireIngredients]; null otherwise (and in a request kept by an older version). Part
     *  of the inputs a request id is bound to. */
    val optionalIngredients: List<WireIngredient>? = null,
    /** A menu's days, 1 to 4: only when not [DEFAULT_MENU_DAYS] and only to a server that said
     *  `acceptsMenuDays`. Null (and then left out of the JSON altogether, so the request is the
     *  legacy one) otherwise, and in a request kept by an older version. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val days: Int? = null,
    /** The taste profile's answers, step id to option ids ([wireTasteFor]): only when not empty
     *  and only to a server that said `acceptsTaste`. Null (and then left out of the JSON
     *  altogether, so the request is the one from before the profile) otherwise, and in a request
     *  kept by an older version. Part of the inputs a request id is bound to, as sent. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val taste: Map<String, List<String>>? = null
) {
    /** Whether [other] asks for the same thing, whatever its id. */
    fun sameAskAs(other: GenerateRequest): Boolean =
        copy(requestId = other.requestId, allergenConsent = other.allergenConsent) == other
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
    val charged: Boolean = false,
    /** Read with [recipeCostsOf]. */
    val costs: JsonElement? = null,
    /** Read with [purchasesBlockedOf]. */
    val purchasesBlocked: JsonElement? = null,
    /** Read with [acceptsOptionalIngredientsOf]. */
    val acceptsOptionalIngredients: JsonElement? = null,
    /** Read with [acceptsMultiChoiceConfigOf]. */
    val acceptsMultiChoiceConfig: JsonElement? = null,
    /** Read with [acceptsDanishOf]. */
    val acceptsDanish: JsonElement? = null,
    /** Read with [acceptsMenuDaysOf]. */
    val acceptsMenuDays: JsonElement? = null,
    /** Read with [acceptsTasteOf]. */
    val acceptsTaste: JsonElement? = null,
    /** Read with [tasteQuizVersionOf]. */
    val tasteQuizVersion: JsonElement? = null,
    /** Read with [acceptsWelcomeOf]. */
    val acceptsWelcome: JsonElement? = null,
    /** Read with [welcomeRecipesFieldOf]. */
    val welcomeRecipes: JsonElement? = null
)

@Serializable
enum class VerifyStatus {
    GRANTED,
    ALREADY_GRANTED,
    REJECTED_PERMANENT,
    RETRY
}

@Serializable
data class VerifyReply(
    val status: VerifyStatus,
    val balance: Int? = null,
    val costs: JsonElement? = null,
    val purchasesBlocked: JsonElement? = null,
    val acceptsOptionalIngredients: JsonElement? = null,
    val acceptsMultiChoiceConfig: JsonElement? = null,
    val acceptsDanish: JsonElement? = null,
    val acceptsMenuDays: JsonElement? = null,
    val acceptsTaste: JsonElement? = null,
    val tasteQuizVersion: JsonElement? = null,
    val acceptsWelcome: JsonElement? = null,
    val welcomeRecipes: JsonElement? = null
)

/** `getBalance`'s answer. */
data class BalanceReply(
    val balance: Int,
    val costs: RecipeCosts? = null,
    val purchasesBlocked: Boolean = false,
    val acceptsOptional: Boolean = false,
    val acceptsMultiChoice: Boolean = false,
    val acceptsDanish: Boolean = false,
    val acceptsMenuDays: Boolean = false,
    val acceptsTaste: Boolean = false,
    val tasteQuizVersion: String? = null,
    /** The server has `claimWelcome` ([acceptsWelcomeOf]). */
    val acceptsWelcome: Boolean = false,
    /** What a claim would grant now ([welcomeRecipesFieldOf]); null when absent. */
    val welcomeRecipes: Int? = null
)

/** `mergeBalance`'s answer: the joined [balance], what [moved] from this install (negative when a
 *  store refund had left it below zero: the debt moves too), the costs, and the block. */
data class MergeReply(
    val balance: Int,
    val moved: Int = 0,
    val costs: RecipeCosts? = null,
    val purchasesBlocked: Boolean = false,
    val acceptsOptional: Boolean = false,
    val acceptsMultiChoice: Boolean = false,
    val acceptsDanish: Boolean = false,
    val acceptsMenuDays: Boolean = false,
    val acceptsTaste: Boolean = false,
    val tasteQuizVersion: String? = null,
    /** The server has `claimWelcome` ([acceptsWelcomeOf]). */
    val acceptsWelcome: Boolean = false,
    /** What a claim would grant now ([welcomeRecipesFieldOf]); null when absent. */
    val welcomeRecipes: Int? = null
)

/** A reply's `purchasesBlocked`, read leniently: only a JSON `true` blocks; anything else
 *  (absent, false, a string, a number) doesn't. */
fun purchasesBlockedOf(json: JsonElement?): Boolean =
    (json as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

/** A reply's `acceptsOptionalIngredients` (functions/CONTRACT.md): only a JSON `true` says the
 *  server reads `optionalIngredients`; absent (a server from before the field) says it doesn't. */
fun acceptsOptionalIngredientsOf(json: JsonElement?): Boolean = purchasesBlockedOf(json)

/** A reply's `acceptsMultiChoiceConfig` (functions/CONTRACT.md): only a JSON `true` says the server
 *  reads the time range and the meal and diet lists; absent says it takes only the legacy config. */
fun acceptsMultiChoiceConfigOf(json: JsonElement?): Boolean = purchasesBlockedOf(json)

/** A reply's `acceptsDanish` (functions/CONTRACT.md): only a JSON `true` says the server writes
 *  recipes in Danish (`language: "da"`); absent says it refuses `da`. */
fun acceptsDanishOf(json: JsonElement?): Boolean = purchasesBlockedOf(json)

/** A reply's `acceptsMenuDays` (functions/CONTRACT.md): only a JSON `true` says the server reads a
 *  menu's `days` (1 to 4) and `courses: 1`, priced by `costs.menuSets`; absent says it writes only
 *  3-day menus of 2 or 3 courses. */
fun acceptsMenuDaysOf(json: JsonElement?): Boolean = purchasesBlockedOf(json)

/** A reply's `acceptsTaste` (functions/CONTRACT.md): only a JSON `true` says the server reads a
 *  request's `taste`; absent says it doesn't, and the request goes without it. */
fun acceptsTasteOf(json: JsonElement?): Boolean = purchasesBlockedOf(json)

/** A reply's `tasteQuizVersion` (functions/CONTRACT.md): the version `getTasteQuiz` would answer
 *  with now, so the app can tell new questions without opening the quiz; null when absent or not
 *  a non-empty string. */
fun tasteQuizVersionOf(json: JsonElement?): String? =
    (json as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.take(64)

/** A failed call, by the contract's `details.reason`, plus the two the client sees itself. */
enum class ServerError {
    /** No connection, or the call timed out. Safe to retry with the same request id. */
    OFFLINE,
    INVALID_REQUEST,
    INSUFFICIENT_CREDITS,
    BUSY,
    GENERATION_DISABLED,
    ACCOUNT_CLOSED,
    /** Too many calls this minute. */
    RATE_LIMITED,

    /** This account's daily count or usage ceiling: try tomorrow (UTC), nothing taken. */
    USER_DAILY_LIMIT,

    /** Too many technical failures for this account today, each given back: try tomorrow. */
    INFRA_FAILURE_LIMIT,
    DAILY_CAP,

    /** The request id was first used with other inputs: a client bug. Drop it, use a new id. */
    REQUEST_ID_REUSED,

    /** `getGeneration` doesn't know this id: the server never got the request. Send it again
     *  with `generateRecipe`, same id and inputs. */
    UNKNOWN_REQUEST,

    /** The callable itself doesn't exist on this server (deployed before it was added). */
    CALLABLE_MISSING,

    /** The App Check token was already used. Asking again with a fresh one works; it never
     *  means the build can't attest. */
    APP_CHECK_REPLAYED,
    RECENT_SIGN_IN_REQUIRED,
    MERGE_NOT_ALLOWED,

    /** `verifyPurchase`: this build may not redeem purchases from this store (the App Check app
     *  id isn't the Android app's). A build bug; nothing was recorded. */
    STORE_MISMATCH,

    /** `verifyPurchase`: the purchase was made by another account. Nothing was recorded; it stays
     *  in the store for the account that bought it. */
    PURCHASE_ACCOUNT_MISMATCH,

    /** `claimWelcome`: the account has no Google or Apple sign-in (an anonymous install). Nothing
     *  was granted; sign in first. */
    SIGN_IN_REQUIRED,

    /** `generateRecipe`: allergens were sent without `allergenConsent: true` (a request kept from
     *  before the consent step existed). Nothing was charged; a new request carries the flag. */
    ALLERGEN_CONSENT_REQUIRED,

    /** `generateRecipe`: the ticked items can't make what was asked once the allergens and the
     *  diets are applied (for a menu, some day has no item it may use). Nothing was charged and no
     *  model was called; a changed request needs a new request id. */
    NOT_ENOUGH_ITEMS,

    /** `resource-exhausted` without a reason (HTTP 429): every server instance is busy. Nothing
     *  was charged; ask again, same request id, after a few seconds. */
    OVERLOADED,

    /** `internal`: a server bug or outage. Safe to retry with the same request id. */
    INTERNAL,

    /** A `details.reason` this app doesn't know yet (the server added it later). Treated as
     *  refused before anything was held: try later, nothing taken. */
    UNKNOWN,

    /** No Auth user or no valid App Check token: this build can't use the server. */
    UNAUTHENTICATED;

    companion object {
        /** What the client works out itself; never a server's `details.reason`. */
        private val CLIENT_ONLY = setOf(UNKNOWN, OFFLINE, UNAUTHENTICATED, CALLABLE_MISSING, OVERLOADED)

        /** The error for a callable failure's `details.reason`, [fallback] when it has none, and
         *  [UNKNOWN] for a reason this app doesn't know. */
        fun fromReason(reason: String?, fallback: ServerError): ServerError = when {
            reason.isNullOrEmpty() -> fallback
            else -> entries.firstOrNull { it.name == reason && it !in CLIENT_ONLY } ?: UNKNOWN
        }
    }
}

sealed interface ServerResult<out T> {
    data class Ok<T>(val value: T) : ServerResult<T>
    /** [refusedName]: for INVALID_REQUEST, the ingredient or allergen the server refused, when
     *  its `details.field` names one. */
    data class Err(val error: ServerError, val refusedName: RefusedName? = null) : ServerResult<Nothing>
}

// --- Platform interfaces ----------------------------------------------------------------------

/** The Cloud Functions callables (functions/CONTRACT.md). Every call needs a user, which
 *  [RecipeAccount.ensureUser] or a sign-in creates first. */
interface RecipeBackend {
    /** Whether this build can pass App Check at all: false for forks and sideloads, so the paid
     *  route is hidden there and the free routes stay. Known without any network call. */
    val isAvailable: Boolean

    /** Whether this build can get an App Check token, asked as a purchase starts and before
     *  any money moves. Makes no call to our server. */
    suspend fun attest(): Attestation = Attestation.OK
    /** Starts [request], or answers for it if that id was already started with these inputs.
     *  Each call spends a fresh limited-use App Check token. */
    suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply>

    /** Asks how an already started generation is going, without starting one and without
     *  spending a limited-use App Check token. [ServerError.UNKNOWN_REQUEST] when the server
     *  never got it; [ServerError.CALLABLE_MISSING] from a server deployed before it existed. */
    suspend fun getGeneration(requestId: String): ServerResult<GenerateReply> = ServerResult.Err(ServerError.CALLABLE_MISSING)
    suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply>
    suspend fun getBalance(): ServerResult<BalanceReply>

    /** `getTasteQuiz`: the taste profile's questions in [language] (an app language code), as the
     *  server's JSON, read with [tasteQuizOf]. Only once a reply said `acceptsTaste`. An ordinary
     *  App Check token, as for the balance; charges nothing. */
    suspend fun getTasteQuiz(language: String): ServerResult<JsonElement> = ServerResult.Err(ServerError.CALLABLE_MISSING)

    /** `getWelcomeOffer`: how many free recipes a sign-in gets now, 0 when none. Needs no user and
     *  creates none: an ordinary App Check token only, and nothing about the person is sent. An older
     *  server answers [ServerError.CALLABLE_MISSING]. */
    suspend fun getWelcomeOffer(): ServerResult<Int> = ServerResult.Err(ServerError.CALLABLE_MISSING)

    /** `claimWelcome`: the free recipes for the signed-in Google or Apple account, once ever. A
     *  limited-use App Check token; safe to ask again (it answers ALREADY). */
    suspend fun claimWelcome(): ServerResult<WelcomeClaimReply> = ServerResult.Err(ServerError.CALLABLE_MISSING)

    /** Deletes the signed-in account on the server. The caller signs out afterwards. */
    suspend fun deleteAccount(): ServerResult<DeleteReply>
}

/** `deleteAccount`'s answer. [appleLinkRemoved] is null for an account without Apple, false when
 *  the server could not remove Apple's link (the person is told how to do it themselves). */
data class DeleteReply(val appleLinkRemoved: Boolean? = null)

/** Whether a deletion left Apple's link to the app in place: only when the server says so. */
fun appleLinkLeftBehind(reply: DeleteReply): Boolean = reply.appleLinkRemoved == false

/** What [RecipeBackend.attest] found. */
enum class Attestation {
    OK,

    /** The attestation provider refused this build (a fork, a sideload, a debug build whose
     *  token isn't registered): the paid route hides and the free routes stay. */
    FAILED,

    /** It couldn't be asked, such as offline: the purchase goes ahead and fails as usual. */
    UNKNOWN
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

/** The sign-ins an account can carry. Android offers only [GOOGLE] ([RecipeAccount.providers]);
 *  [APPLE] is there because an account made or extended on an iPhone can carry it too. */
enum class SignInProvider { GOOGLE, APPLE }

data class AccountState(
    val kind: AccountKind = AccountKind.NONE,
    /** Every sign-in the account carries, including ones this platform doesn't offer (Apple,
     *  added on an iPhone, on an account signed in with Google on Android). */
    val providers: Set<SignInProvider> = emptySet(),
    /** As the provider gave it: Apple's relay address is shown as it is. */
    val email: String? = null,
    /** The purchase account token of this user (functions/CONTRACT.md, "Purchase account
     *  token"), not the uid itself: tells apart whom a purchase was refused for. Null without a
     *  user. */
    val purchaseKey: String? = null
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

    /** The sign-ins this build offers. On Android only Google (it needs a configured web
     *  client id), never Apple, whatever [AccountState.providers] carries. */
    val providers: Set<SignInProvider>

    /** Makes sure there is a user, creating an anonymous one if there is none. Only called once
     *  a purchase starts or a held purchase has to be sent. */
    suspend fun ensureUser(): Boolean

    /**
     * With no user, signs in. With an anonymous user, links the sign-in to it, so the uid and
     * the balance stay. With a signed-in user, adds the sign-in to that account.
     */
    suspend fun signIn(provider: SignInProvider): SignInResult

    /** Moves this install's recipes (or its debt) into the account with `mergeBalance` and, on
     *  success, switches to it. A failure changes nothing and leaves [merge] open, so it can be
     *  asked again; [cancelMerge] closes it. */
    suspend fun completeMerge(merge: PendingMerge): ServerResult<MergeReply>
    suspend fun cancelMerge(merge: PendingMerge)

    /** Whether deletion has to sign in again first: the last sign-in is too old for
     *  `deleteAccount`. */
    fun needsFreshSignIn(): Boolean

    /** Signs in again, as deletion requires, with a sign-in this platform offers (Google on
     *  Android, also for an account that has Apple attached). False when cancelled or failed, or
     *  when the account has no such sign-in. */
    suspend fun reauthenticateForDeletion(): Boolean
    suspend fun signOut()
}

/** What the paid route keeps on this phone between launches. */
@Serializable
data class UnverifiedPurchase(
    val token: String,
    val recipes: Int,
    /** The [AccountState.purchaseKey] the server said this purchase doesn't belong to
     *  (PURCHASE_ACCOUNT_MISMATCH): not sent again until another account is signed in. */
    val refusedFor: String? = null
)

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
    /** The costs the server last sent; null before it has sent any. */
    val costs: RecipeCosts? = null,
    /** The server said it won't sell to this account; kept with the balance. */
    val purchasesBlocked: Boolean = false,
    /** When [pendingGeneration] was first sent. The server keeps an answer for 7 days; past a day
     *  the request is dropped rather than risk it being written, and charged, anew. */
    val pendingSinceMillis: Long? = null,
    /** The person's answer to sending their allergen list to our server ([AllergenConsent]). */
    val allergenConsent: AllergenConsentRecord? = null,
    /** The server's last reply that carried a balance said `acceptsOptionalIngredients`: only
     *  then are the other pantry items sent with "Send all products". */
    val serverTakesOptional: Boolean = false,
    /** The same for `acceptsMultiChoiceConfig`: only then is a config sent that the legacy
     *  values can't say exactly ([wireConfigFor]). */
    val serverTakesMultiChoice: Boolean = false,
    /** The same for `acceptsDanish`: only then is a request in Danish sent. */
    val serverTakesDanish: Boolean = false,
    /** The same for `acceptsMenuDays`: only then are the Days choice and one course a day
     *  offered, and a menu's `days` (other than 3) or `courses: 1` sent. */
    val serverTakesMenuDays: Boolean = false,
    /** The same for `acceptsTaste`: only then is a non-empty taste profile sent ([wireTasteFor]).
     *  Cleared when a request that carried it is refused as INVALID_REQUEST (a rolled-back server). */
    val serverTakesTaste: Boolean = false,
    /** The last `tasteQuizVersion` a reply carried; null before one did. */
    val tasteQuizVersion: String? = null,
    /** The last `getWelcomeOffer` answer, with its time: shown while under a day old. */
    val welcomeOffer: WelcomeAnswer? = null,
    /** The `welcomeRecipes` beside the last balance (0 from a server without `acceptsWelcome`),
     *  with its time: the number for a signed-in account that hasn't claimed. */
    val welcomeAccount: WelcomeAnswer? = null,
    /** The accounts ([AccountState.purchaseKey]) whose claim settled on this device (GRANTED or
     *  ALREADY): the hint never shows for them again here. */
    val welcomeClaimed: List<String> = emptyList()
)

interface RecipePaidRepository {
    fun get(): RecipePaidSnapshot
    fun save(snapshot: RecipePaidSnapshot)
}

// --- Stand-ins for builds without the paid route ----------------------------------------------

object NotAvailableRecipeBackend : RecipeBackend {
    override val isAvailable: Boolean = false
    override suspend fun attest(): Attestation = Attestation.FAILED
    override suspend fun generate(request: GenerateRequest): ServerResult<GenerateReply> = ServerResult.Err(ServerError.UNAUTHENTICATED)
    override suspend fun verifyPurchase(purchaseToken: String): ServerResult<VerifyReply> = ServerResult.Err(ServerError.UNAUTHENTICATED)
    override suspend fun getBalance(): ServerResult<BalanceReply> = ServerResult.Err(ServerError.UNAUTHENTICATED)
    override suspend fun deleteAccount(): ServerResult<DeleteReply> = ServerResult.Err(ServerError.UNAUTHENTICATED)
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
    override suspend fun completeMerge(merge: PendingMerge): ServerResult<MergeReply> = ServerResult.Err(ServerError.UNAUTHENTICATED)
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

// Names are cleaned by cleanWireName (RecipeWireName.kt).

/** Names in one request with optional items: ticked and optional together (CONTRACT.md
 *  "Optional ingredients"). */
const val MAX_REQUEST_TOTAL_NAMES = 30

/** Code points of names, ticked and optional together, in a request with optional items. */
const val MAX_REQUEST_OPTIONAL_NAME_CHARS = 1050

/**
 * The optional items to send beside [ticked], from [others] (the rest of the pantry, soonest date
 * first, names already cleaned): as the server keeps them, so what is sent is what is used. A name
 * already in the request (ignoring case) is left out; then items are taken in order while the
 * ticked and taken ones together stay within [MAX_REQUEST_TOTAL_NAMES] names and
 * [MAX_REQUEST_OPTIONAL_NAME_CHARS] code points, an item that doesn't fit being skipped. iOS:
 * `RecipeWire.optionalIngredients`.
 */
fun optionalWireIngredients(ticked: List<WireIngredient>, others: List<WireIngredient>): List<WireIngredient> {
    val seen = ticked.mapTo(HashSet()) { it.name.lowercase() }
    var names = ticked.size
    var chars = ticked.sumOf { codePointsOf(it.name).size }
    val kept = mutableListOf<WireIngredient>()
    for (item in others) {
        val key = item.name.lowercase()
        val length = codePointsOf(item.name).size
        if (key in seen || names + 1 > MAX_REQUEST_TOTAL_NAMES || chars + length > MAX_REQUEST_OPTIONAL_NAME_CHARS) continue
        seen += key
        names++
        chars += length
        kept += item
    }
    return kept
}
