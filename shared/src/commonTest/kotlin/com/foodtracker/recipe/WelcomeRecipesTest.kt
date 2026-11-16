package com.foodtracker.recipe

import com.foodtracker.review.ReviewPrompter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of free recipes for signing in (WelcomeRecipes.kt). iOS: `WelcomeRecipes`. */
class WelcomeRecipesTest {

    private val day = WELCOME_ANSWER_MAX_AGE_MILLIS
    private val now = 10 * day

    // --- The cached answer and its age ------------------------------------------------------

    @Test
    fun anAnswerUnderADayOldIsShownAndOneADayOldIsNone() {
        assertEquals(3, welcomeRecipesOf(WelcomeAnswer(3, now), now))
        assertEquals(3, welcomeRecipesOf(WelcomeAnswer(3, now - day + 1), now))
        assertEquals(0, welcomeRecipesOf(WelcomeAnswer(3, now - day), now))
        assertEquals(0, welcomeRecipesOf(null, now))
    }

    @Test
    fun anAnswerFromTheFutureCountsAsOld() {
        // The clock was moved back: never trust a number whose age can't be told.
        assertEquals(0, welcomeRecipesOf(WelcomeAnswer(3, now + 1), now))
        assertFalse(isWelcomeAnswerFresh(WelcomeAnswer(3, now + 1), now))
    }

    @Test
    fun aStoredNumberAboveTheServersLimitIsCapped() {
        assertEquals(WELCOME_MAX_RECIPES, welcomeRecipesOf(WelcomeAnswer(99, now), now))
        assertEquals(0, welcomeRecipesOf(WelcomeAnswer(-2, now), now))
    }

    // --- When to ask ------------------------------------------------------------------------

    @Test
    fun theOfferIsAskedWhenNothingWasAskedAndNotAgainWithinFifteenMinutes() {
        assertTrue(shouldAskWelcomeOffer(askable = true, answer = null, lastAskedMillis = null, nowMillis = now))
        val answered = WelcomeAnswer(3, now)
        assertFalse(shouldAskWelcomeOffer(true, answered, null, now + WELCOME_REFRESH_MILLIS - 1))
        assertTrue(shouldAskWelcomeOffer(true, answered, null, now + WELCOME_REFRESH_MILLIS))
    }

    @Test
    fun aFailedAskCountsForTheFifteenMinutesToo() {
        // The answer is old, but the server was asked a minute ago and failed: not again yet.
        val old = WelcomeAnswer(3, now - day)
        assertFalse(shouldAskWelcomeOffer(true, old, now - 60_000, now))
        assertTrue(shouldAskWelcomeOffer(true, old, now - WELCOME_REFRESH_MILLIS, now))
    }

    @Test
    fun beforeTheSignInSheetItIsAskedWhateverTheTime() {
        assertTrue(shouldAskWelcomeOffer(true, WelcomeAnswer(3, now), now, now, force = true))
        // But never where it may not be asked at all.
        assertFalse(shouldAskWelcomeOffer(false, null, null, now, force = true))
    }

    @Test
    fun aClockMovedBackAllowsAskingAgainOnceFromTheLastAsk() {
        assertTrue(shouldAskWelcomeOffer(true, WelcomeAnswer(3, now), now, now - 1))
        // That ask's own time then takes over: not again for 15 minutes.
        assertFalse(shouldAskWelcomeOffer(true, WelcomeAnswer(3, now), now - 1, now))
    }

    @Test
    fun anAnswerDatedInTheFutureIsIgnoredForTheFifteenMinutes() {
        val future = WelcomeAnswer(3, now + day)
        assertTrue(shouldAskWelcomeOffer(true, future, null, now))
        // Measured from the last ask, not from the future answer.
        assertFalse(shouldAskWelcomeOffer(true, future, now, now + 60_000))
        assertTrue(shouldAskWelcomeOffer(true, future, now, now + WELCOME_REFRESH_MILLIS))
    }

    // --- Reading the server -------------------------------------------------------------------

    @Test
    fun theOfferIsAWholeNumberFromZeroToTwenty() {
        assertEquals(3, welcomeOfferOf(buildJsonObject { put("recipes", 3) }))
        assertEquals(0, welcomeOfferOf(buildJsonObject { put("recipes", 0) }))
        assertEquals(5, welcomeOfferOf(buildJsonObject { put("recipes", 5.0) }))
        assertNull(welcomeOfferOf(buildJsonObject { put("recipes", 2.5) }))
        assertNull(welcomeOfferOf(buildJsonObject { put("recipes", "3") }))
        assertNull(welcomeOfferOf(buildJsonObject { put("recipes", 21) }))
        assertNull(welcomeOfferOf(buildJsonObject { put("recipes", -1) }))
        assertNull(welcomeOfferOf(buildJsonObject { }))
        assertNull(welcomeOfferOf(JsonPrimitive(3)))
    }

    @Test
    fun besideABalanceOnlyAServerThatAcceptsWelcomeGrantsAnything() {
        assertEquals(WelcomeAnswer(3, now), welcomeAccountAnswerOf(accepts = true, recipes = 3, nowMillis = now))
        assertEquals(WelcomeAnswer(0, now), welcomeAccountAnswerOf(accepts = true, recipes = null, nowMillis = now))
        // An older server sends neither: no grant.
        assertEquals(WelcomeAnswer(0, now), welcomeAccountAnswerOf(accepts = false, recipes = 3, nowMillis = now))
        assertTrue(acceptsWelcomeOf(JsonPrimitive(true)))
        assertFalse(acceptsWelcomeOf(JsonPrimitive("true")))
        assertFalse(acceptsWelcomeOf(JsonNull))
        assertEquals(4, welcomeRecipesFieldOf(JsonPrimitive(4)))
        assertNull(welcomeRecipesFieldOf(JsonPrimitive("4")))
    }

    @Test
    fun claimStatusesAreReadByName() {
        assertEquals(WelcomeStatus.GRANTED, welcomeStatusOf(JsonPrimitive("GRANTED")))
        assertEquals(WelcomeStatus.ALREADY, welcomeStatusOf(JsonPrimitive("ALREADY")))
        assertEquals(WelcomeStatus.OFF, welcomeStatusOf(JsonPrimitive("OFF")))
        assertNull(welcomeStatusOf(JsonPrimitive("granted")))
        assertNull(welcomeStatusOf(JsonPrimitive(1)))
    }

    // --- What is shown ------------------------------------------------------------------------

    private val canBuy = PaidUiState(available = true, packsOffered = true, providers = setOf(SignInProvider.GOOGLE))
    private val signedIn = AccountState(AccountKind.SIGNED_IN, setOf(SignInProvider.GOOGLE), "a@b.c", "key")

    @Test
    fun signedOutTheHintNamesTheOfferAndSignsIn() {
        assertEquals(WelcomeHint(3, WelcomeKind.SIGN_IN), welcomeHint(canBuy.copy(welcomeOffer = 3)))
        // With recipes on this device (anonymous) too: signing in moves them, then claims.
        assertEquals(WelcomeHint(1, WelcomeKind.SIGN_IN), welcomeHint(canBuy.copy(welcomeOffer = 1, account = AccountState(AccountKind.ANONYMOUS))))
    }

    @Test
    fun neverAHintWithoutANumber() {
        assertNull(welcomeHint(canBuy))
        assertNull(welcomeHint(canBuy.copy(welcomeOffer = 0)))
        assertNull(welcomeHint(canBuy.copy(account = signedIn, welcomeAccount = 0, welcomeOffer = 3)))
    }

    @Test
    fun theHintGoesWhereBuyGoes() {
        val offer = canBuy.copy(welcomeOffer = 3)
        assertNull(welcomeHint(offer.copy(available = false)))
        assertNull(welcomeHint(offer.copy(packsOffered = null)))
        assertNull(welcomeHint(offer.copy(packsOffered = false)))
        assertNull(welcomeHint(offer.copy(purchasesBlocked = true)))
        assertNull(welcomeHint(offer.copy(storeRefused = true)))
        assertNull(welcomeHint(offer.copy(issue = ServerIssue.PAUSED)))
        // Offline or busy is not a reason to hide it.
        assertEquals(3, welcomeHint(offer.copy(issue = ServerIssue.OFFLINE))?.recipes)
    }

    @Test
    fun signedOutWithoutASignInToOfferThereIsNoHint() {
        assertNull(welcomeHint(canBuy.copy(welcomeOffer = 3, providers = emptySet())))
    }

    @Test
    fun signedInTheNumberBesideTheBalanceWinsAndOnlyUntilClaimed() {
        val paid = canBuy.copy(account = signedIn, welcomeAccount = 5, welcomeOffer = 3)
        assertEquals(WelcomeHint(5, WelcomeKind.CLAIM), welcomeHint(paid))
        // No number beside a balance in the last day (a claim that didn't reach our server, say):
        // the day's offer.
        assertEquals(WelcomeHint(3, WelcomeKind.CLAIM), welcomeHint(paid.copy(welcomeAccount = null)))
        assertNull(welcomeHint(paid.copy(welcomeClaimed = true)))
        // Signed in, the providers this build offers don't matter: no sign-in is needed.
        assertEquals(WelcomeHint(5, WelcomeKind.CLAIM), welcomeHint(paid.copy(providers = emptySet())))
    }

    @Test
    fun theOfferIsAskedOnlyWithoutASignedInAccount() {
        assertTrue(welcomeOfferAskable(canBuy))
        assertTrue(welcomeOfferAskable(canBuy.copy(account = AccountState(AccountKind.ANONYMOUS))))
        assertFalse(welcomeOfferAskable(canBuy.copy(account = signedIn)))
        assertFalse(welcomeOfferAskable(canBuy.copy(packsOffered = null)))
        assertFalse(welcomeOfferAskable(canBuy.copy(available = false)))
    }

    // --- The menu line -----------------------------------------------------------------------

    private val table = RecipeCosts(menuSets = (1..4).associateWith { days -> (1..3).associateWith { courses -> days + courses - 1 } })

    @Test
    fun theMenuLineSaysWhetherTheFreeRecipesCoverThisMenu() {
        // 1 day of 3 courses takes 3.
        assertEquals(WelcomeMenuCover.ENOUGH, welcomeMenuCover(3, 0, 3, table, menuSets = true))
        // 3 days of 3 courses takes 5; a smaller set fits.
        assertEquals(WelcomeMenuCover.SMALLER_MENU, welcomeMenuCover(3, 0, 5, table, menuSets = true))
        // What is already held counts with them.
        assertEquals(WelcomeMenuCover.ENOUGH, welcomeMenuCover(3, 2, 5, table, menuSets = true))
        // A balance below zero (a refund after use) doesn't take them away.
        assertEquals(WelcomeMenuCover.SMALLER_MENU, welcomeMenuCover(3, -4, 5, table, menuSets = true))
    }

    @Test
    fun theSmallerMenuClauseOnlyWhenSomeSetTheServerPricesFits() {
        // Without menu sets, only 3-day menus of 4 and 5: 3 free recipes fit none.
        assertEquals(WelcomeMenuCover.SINGLE_ONLY, welcomeMenuCover(3, 0, 5, RecipeCosts(), menuSets = false))
        assertEquals(WelcomeMenuCover.SMALLER_MENU, welcomeMenuCover(4, 0, 5, RecipeCosts(), menuSets = false))
        // A table that the server didn't send (menuSets off) isn't used.
        assertEquals(WelcomeMenuCover.SINGLE_ONLY, welcomeMenuCover(3, 0, 5, table, menuSets = false))
        assertEquals(WelcomeMenuCover.SINGLE_ONLY, welcomeMenuCover(1, 0, 4, RecipeCosts(menuSets = mapOf(1 to mapOf(1 to 2))), menuSets = true))
    }

    // --- Outcomes ----------------------------------------------------------------------------

    private fun reply(status: WelcomeStatus, granted: Int = 0) = ServerResult.Ok(WelcomeClaimReply(status, granted, BalanceReply(granted)))

    @Test
    fun outcomesFollowTheClaimsStatus() {
        assertEquals(WelcomeOutcome.Granted(3), welcomeOutcomeOf(reply(WelcomeStatus.GRANTED, 3), signedInNow = true))
        assertEquals(WelcomeOutcome.Already, welcomeOutcomeOf(reply(WelcomeStatus.ALREADY), signedInNow = true))
        assertEquals(WelcomeOutcome.Off(true), welcomeOutcomeOf(reply(WelcomeStatus.OFF), signedInNow = true))
        assertEquals(WelcomeOutcome.Off(false), welcomeOutcomeOf(reply(WelcomeStatus.OFF), signedInNow = false))
        assertEquals(WelcomeOutcome.NotReached, welcomeOutcomeOf(ServerResult.Err(ServerError.OFFLINE), signedInNow = true))
        assertEquals(WelcomeOutcome.NotReached, welcomeOutcomeOf(ServerResult.Err(ServerError.SIGN_IN_REQUIRED), signedInNow = true))
        // A server without the callable has no free recipes: off, not a retry.
        assertEquals(WelcomeOutcome.Off(true), welcomeOutcomeOf(ServerResult.Err(ServerError.CALLABLE_MISSING), signedInNow = true))
    }

    @Test
    fun onlyGrantedAndAlreadySettleTheClaim() {
        assertTrue(welcomeClaimSettled(WelcomeOutcome.Granted(3)))
        assertTrue(welcomeClaimSettled(WelcomeOutcome.Already))
        assertFalse(welcomeClaimSettled(WelcomeOutcome.Off(true)))
        assertFalse(welcomeClaimSettled(WelcomeOutcome.NotReached))
    }

    // --- After a link (recordLinkedSignIn) ---------------------------------------------------

    @Test
    fun aLinkIsFollowedByExactlyOneBalanceCallWithAFreshToken() = kotlinx.coroutines.test.runTest {
        val calls = mutableListOf<String>()
        val reached = recordLinkedSignIn(
            refreshIdToken = { calls += "token" },
            getBalance = { calls += "balance"; ServerResult.Ok(BalanceReply(0)) }
        )
        assertTrue(reached)
        assertEquals(listOf("token", "balance"), calls)
    }

    @Test
    fun aFailedRefreshNeverFailsTheLink() = kotlinx.coroutines.test.runTest {
        // The server unreachable: said by the result only, nothing thrown.
        assertFalse(recordLinkedSignIn(refreshIdToken = {}, getBalance = { ServerResult.Err(ServerError.OFFLINE) }))
        assertFalse(recordLinkedSignIn(refreshIdToken = {}, getBalance = { error("network") }))
        // No fresh token: no call that would carry a token without the new provider.
        var balanceCalls = 0
        assertFalse(recordLinkedSignIn(refreshIdToken = { error("token") }, getBalance = { balanceCalls++; ServerResult.Ok(BalanceReply(0)) }))
        assertEquals(0, balanceCalls)
    }

    @Test
    fun theReasonSignInRequiredIsRead() {
        assertEquals(ServerError.SIGN_IN_REQUIRED, ServerError.fromReason("SIGN_IN_REQUIRED", ServerError.INTERNAL))
    }

    @Test
    fun theRatingPromptTreatsAGrantAsNeutral() {
        assertFalse(ReviewPrompter.isProblem(PaidEvent.Welcome(WelcomeOutcome.Granted(3))))
        assertFalse(ReviewPrompter.isProblem(PaidEvent.Welcome(WelcomeOutcome.Off(true))))
        assertTrue(ReviewPrompter.isProblem(PaidEvent.Welcome(WelcomeOutcome.NotReached)))
    }

    @Test
    fun freeRecipesOpenTheTasteQuizLikeBoughtOnes() {
        // The gate is unchanged: recipes in the balance, whatever their origin.
        assertEquals(TasteEntry.QUIZ, tasteEntry(true, 3, false, false, true))
    }
}
