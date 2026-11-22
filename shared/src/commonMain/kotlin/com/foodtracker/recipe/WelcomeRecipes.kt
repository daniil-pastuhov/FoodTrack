package com.foodtracker.recipe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/*
 * Free recipes for signing in (the backend's "welcome grant", functions/CONTRACT.md `getWelcomeOffer`,
 * `claimWelcome`, "Capability `acceptsWelcome`"). The pure rules, one set on both platforms; iOS:
 * `WelcomeRecipes` in `Recipe/RecipeWire.swift`. In the UI they are "free recipes", never a credit,
 * token or bonus, and the hint always names the server's number: no answer, 0, a failed call, an
 * older server or an answer older than a day is no hint at all.
 */

/** How long an answer stands: older than this it counts as none. */
const val WELCOME_ANSWER_MAX_AGE_MILLIS: Long = 24 * 60 * 60_000L

/** `getWelcomeOffer` is asked when the Recipe tab is shown, but not more often than this. */
const val WELCOME_REFRESH_MILLIS: Long = 15 * 60_000L

/** The most a grant can be (the server's `welcome.recipes` is 0 to 20); anything above is unread. */
const val WELCOME_MAX_RECIPES: Int = 20

/** A number of free recipes the server said, and when it said it (this phone's clock). */
@Serializable
data class WelcomeAnswer(val recipes: Int, val atMillis: Long)

/** [answer]'s number while it is less than a day old; 0 for none, an old answer, or one from the
 *  future (the clock was moved back), which is treated as old. */
fun welcomeRecipesOf(answer: WelcomeAnswer?, nowMillis: Long): Int {
    if (answer == null) return 0
    val age = nowMillis - answer.atMillis
    return if (age in 0 until WELCOME_ANSWER_MAX_AGE_MILLIS) answer.recipes.coerceIn(0, WELCOME_MAX_RECIPES) else 0
}

/** Whether [answer] is less than a day old (and not from the future). */
fun isWelcomeAnswerFresh(answer: WelcomeAnswer?, nowMillis: Long): Boolean =
    answer != null && (nowMillis - answer.atMillis) in 0 until WELCOME_ANSWER_MAX_AGE_MILLIS

/**
 * Whether to ask `getWelcomeOffer` now: only while [askable] ([welcomeOfferAskable]); [force] (right
 * before the sign-in sheet opens) whenever it is; otherwise when nothing was asked or answered in the
 * last [WELCOME_REFRESH_MILLIS]. Measured from the last ask ([lastAskedMillis], kept across launches,
 * failed calls too, so a failing server isn't asked on every showing or every cold start); without
 * one, from the answer's time unless that lies in the future. A clock moved back before the last ask
 * counts as time passed once: that ask is then made, and its own time takes over.
 */
fun shouldAskWelcomeOffer(askable: Boolean, answer: WelcomeAnswer?, lastAskedMillis: Long?, nowMillis: Long, force: Boolean = false): Boolean {
    if (!askable) return false
    if (force) return true
    val last = lastAskedMillis ?: answer?.atMillis?.takeIf { it <= nowMillis } ?: return true
    val since = nowMillis - last
    return since < 0 || since >= WELCOME_REFRESH_MILLIS
}

/** `getWelcomeOffer`'s `recipes`: a whole number 0 to [WELCOME_MAX_RECIPES]; null for anything else
 *  (the answer is then not read, like a failed call). */
fun welcomeOfferOf(json: JsonElement?): Int? = welcomeCountOf((json as? JsonObject)?.get("recipes"))

/** The `welcomeRecipes` beside a balance: as [welcomeOfferOf]; null when absent or unreadable. */
fun welcomeRecipesFieldOf(json: JsonElement?): Int? = welcomeCountOf(json)

private fun welcomeCountOf(json: JsonElement?): Int? {
    val value = json as? JsonPrimitive ?: return null
    if (value.isString) return null
    val whole = value.intOrNull ?: value.doubleOrNull?.takeIf { it % 1.0 == 0.0 && it in 0.0..WELCOME_MAX_RECIPES.toDouble() }?.toInt()
    return whole?.takeIf { it in 0..WELCOME_MAX_RECIPES }
}

/** A reply's `acceptsWelcome`: only a JSON `true` says the server has `claimWelcome`. */
fun acceptsWelcomeOf(json: JsonElement?): Boolean = purchasesBlockedOf(json)

/**
 * The welcome answer to keep from a reply that carries a balance: its `welcomeRecipes` when the
 * server says `acceptsWelcome` (0 when it leaves the number out), and 0 from an older server, which
 * grants nothing. Timed [nowMillis].
 */
fun welcomeAccountAnswerOf(accepts: Boolean, recipes: Int?, nowMillis: Long): WelcomeAnswer =
    WelcomeAnswer(if (accepts) recipes ?: 0 else 0, nowMillis)

/**
 * Right after a sign-in is linked to an existing account (Android `linkWithCredential`, iOS
 * `user.link(with:)`): the ID token is refreshed so it names the new provider, then `getBalance`
 * is called once, before anything else, whether or not an offer is showing (welcome-spec section
 * 7). Why: the server only learns which Google and Apple accounts belong to an account when the
 * app talks to it, and it records each one it sees so that free recipes are given once per Google
 * or Apple account (`welcomes/`). Firebase tells the server nothing about a link or an unlink, so a
 * sign-in linked and then unlinked again before the next server call would never be recorded and
 * could claim free recipes again from another account. The apps never unlink, so that needs a
 * tampered client; this call makes the window as short as one request.
 *
 * A failure (of either step) is silent and never fails or undoes the link: the next balance call
 * retries it. Returns whether the server answered. iOS: `RecipeAccount.recordLinkedSignIn`.
 */
suspend fun recordLinkedSignIn(
    refreshIdToken: suspend () -> Unit,
    getBalance: suspend () -> ServerResult<BalanceReply>
): Boolean {
    try {
        refreshIdToken()
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Without a token that names the new provider, the call would record nothing new.
        return false
    }
    return try {
        getBalance() is ServerResult.Ok
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}

/** `claimWelcome`'s status. */
enum class WelcomeStatus { GRANTED, ALREADY, OFF }

/** `claimWelcome`'s answer: [granted] recipes (0 unless [WelcomeStatus.GRANTED]) and everything a
 *  balance carries. */
data class WelcomeClaimReply(val status: WelcomeStatus, val granted: Int, val balance: BalanceReply)

/** The status of a `claimWelcome` reply, null for one this app doesn't know (read as not reached). */
fun welcomeStatusOf(json: JsonElement?): WelcomeStatus? {
    val value = (json as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return WelcomeStatus.entries.firstOrNull { it.name == value }
}

/** Which way the hint is worded: signed out it signs in first ("Sign in and get 3 free recipes"),
 *  signed in it only claims ("Get 3 free recipes"). */
enum class WelcomeKind { SIGN_IN, CLAIM }

/** The hint to show: [recipes] (above 0) and how to word it. */
data class WelcomeHint(val recipes: Int, val kind: WelcomeKind)

/**
 * Whether the person could be offered free recipes at all, whatever the number: the paid route is
 * offered on this install, the store sells it packs, the account isn't blocked from buying, buying
 * isn't refused for this build, and recipes from packs aren't paused (the hint goes wherever Buy goes).
 */
fun welcomeGate(paid: PaidUiState): Boolean =
    paid.available && paid.packsOffered == true && !paid.purchasesBlocked && !paid.storeRefused && paid.issue != ServerIssue.PAUSED

/** Whether `getWelcomeOffer` may be asked: through [welcomeGate], and only without a signed-in
 *  account (signed in, the number comes beside the balance with no extra call). Never creates one. */
fun welcomeOfferAskable(paid: PaidUiState): Boolean = welcomeGate(paid) && paid.account.kind != AccountKind.SIGNED_IN

/**
 * The hint, one rule wherever it shows (form, How recipes work, Buy sheet, Recipe settings), or
 * null for none:
 * - signed out (no account, or recipes kept on this device): the cached `getWelcomeOffer` answer
 *   ([PaidUiState.welcomeOffer]), and only where a sign-in is offered;
 * - signed in: nothing once this account has claimed on this device; otherwise the number beside the
 *   balance ([PaidUiState.welcomeAccount]) when one arrived in the last day, else the cached offer.
 * Never a hint without a number above 0.
 */
fun welcomeHint(paid: PaidUiState): WelcomeHint? {
    if (!welcomeGate(paid)) return null
    return when (paid.account.kind) {
        AccountKind.SIGNED_IN -> {
            if (paid.welcomeClaimed) return null
            val recipes = paid.welcomeAccount ?: paid.welcomeOffer
            if (recipes > 0) WelcomeHint(recipes, WelcomeKind.CLAIM) else null
        }
        AccountKind.NONE, AccountKind.ANONYMOUS -> {
            if (paid.providers.isEmpty() || paid.welcomeOffer <= 0) null else WelcomeHint(paid.welcomeOffer, WelcomeKind.SIGN_IN)
        }
    }
}

/** What the free recipes do for the menu set asked for, said under the offer on the menu form. */
enum class WelcomeMenuCover {
    /** With them there are enough for this menu. */
    ENOUGH,

    /** Not this one, but a single recipe or a smaller menu set the server prices. */
    SMALLER_MENU,

    /** Not any menu: single recipes only. */
    SINGLE_ONLY
}

/**
 * Whether [free] recipes, with the [balance] already held (never below 0), cover a menu that takes
 * [menuCost]; if not, whether some menu set the server offers ([costs]'s `menuSets` when [menuSets],
 * else the 3-day menus of 2 and 3 courses) fits, for the "or a smaller menu" clause. iOS:
 * `WelcomeRecipes.menuCover`.
 */
fun welcomeMenuCover(free: Int, balance: Int, menuCost: Int, costs: RecipeCosts, menuSets: Boolean): WelcomeMenuCover {
    val total = free + balance.coerceAtLeast(0)
    if (total >= menuCost) return WelcomeMenuCover.ENOUGH
    val offered = if (menuSets && costs.menuSets.isNotEmpty()) costs.menuSets.values.flatMap { it.values } else listOf(costs.menu2, costs.menu3)
    return if (offered.any { it <= total }) WelcomeMenuCover.SMALLER_MENU else WelcomeMenuCover.SINGLE_ONLY
}

/** The sign-in sheet that comes before a claim: [recipes] as the refresh before it said (0: a plain
 *  sign-in, no promise); [signingIn] while the system sign-in runs; [adding] while the claim runs;
 *  [signInFailed] after a sign-in that failed (not cancelled), said in the sheet, which stays. */
data class WelcomeSheet(val recipes: Int, val signingIn: Boolean = false, val adding: Boolean = false, val signInFailed: Boolean = false)

/** How a claim ended, for the person. */
sealed interface WelcomeOutcome {
    data class Granted(val count: Int) : WelcomeOutcome

    /** This Google or Apple account has had its free recipes: the dialog with See spark packs. */
    data object Already : WelcomeOutcome

    /** Off, or today's grants used up. [signedInNow]: the sign-in for it happened just now, which
     *  the message says. */
    data class Off(val signedInNow: Boolean) : WelcomeOutcome

    /** Signed in, but the claim didn't reach our server: Try again claims, which is safe. */
    data object NotReached : WelcomeOutcome
}

/** What a `claimWelcome` result means for the person. [signedInNow] as in [WelcomeOutcome.Off]. */
fun welcomeOutcomeOf(result: ServerResult<WelcomeClaimReply>, signedInNow: Boolean): WelcomeOutcome = when (result) {
    is ServerResult.Ok -> when (result.value.status) {
        WelcomeStatus.GRANTED -> WelcomeOutcome.Granted(result.value.granted)
        WelcomeStatus.ALREADY -> WelcomeOutcome.Already
        WelcomeStatus.OFF -> WelcomeOutcome.Off(signedInNow)
    }
    // A server without the callable grants nothing: said as off rather than as a failure to retry.
    is ServerResult.Err -> if (result.error == ServerError.CALLABLE_MISSING) WelcomeOutcome.Off(signedInNow) else WelcomeOutcome.NotReached
}

/** Whether a claim result settles it for this account on this device (the hint never shows for it
 *  again): GRANTED and ALREADY do; OFF and a claim not reached don't. */
fun welcomeClaimSettled(outcome: WelcomeOutcome): Boolean = outcome is WelcomeOutcome.Granted || outcome == WelcomeOutcome.Already
