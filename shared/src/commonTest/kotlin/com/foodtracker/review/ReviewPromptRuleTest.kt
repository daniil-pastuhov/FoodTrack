package com.foodtracker.review

import com.foodtracker.recipe.BuySheet
import com.foodtracker.recipe.PaidEvent
import com.foodtracker.recipe.PaidUiState
import com.foodtracker.recipe.PurchaseRejection
import com.foodtracker.recipe.RecipeEvent
import com.foodtracker.recipe.ServerIssue
import com.foodtracker.review.ReviewPromptRule.DAY_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewPromptRuleTest {

    private val start = 1_800_000_000_000L
    private val eligible = ReviewPromptRecord(firstLaunchMillis = start, mostItemsSeen = 2, usesBeforeDate = 3)
    private val later = start + 7 * DAY_MILLIS

    private fun mayAsk(
        record: ReviewPromptRecord = eligible,
        moment: ReviewMoment = ReviewMoment.USED_BEFORE_DATE,
        now: Long = later,
        version: String = "2.1.0",
        blocked: Boolean = false
    ) = ReviewPromptRule.mayAsk(record, moment, now, version, blocked)

    @Test
    fun asksOnceEveryConditionHolds() {
        assertTrue(mayAsk())
        assertTrue(mayAsk(moment = ReviewMoment.RECIPE_LEFT))
    }

    @Test
    fun waitsSevenFullDaysFromFirstLaunch() {
        assertFalse(mayAsk(now = later - 1))
        assertFalse(mayAsk(record = eligible.copy(firstLaunchMillis = null)))
    }

    @Test
    fun aClockSetBackIsTooSoonNotLongAgo() {
        assertFalse(mayAsk(now = start - 400 * DAY_MILLIS))
        val asked = eligible.copy(lastRequestMillis = later, lastRequestVersion = "2.0.0")
        assertFalse(mayAsk(record = asked, now = later - 1000 * DAY_MILLIS))
    }

    @Test
    fun needsTwoItemsEverInThePantry() {
        assertFalse(mayAsk(record = eligible.copy(mostItemsSeen = 1)))
        assertFalse(mayAsk(record = eligible.copy(mostItemsSeen = 1), moment = ReviewMoment.RECIPE_LEFT))
    }

    @Test
    fun aUseCountsOnlyFromTheThird() {
        assertFalse(mayAsk(record = eligible.copy(usesBeforeDate = 2)))
        // A recipe doesn't need any uses.
        assertTrue(mayAsk(record = eligible.copy(usesBeforeDate = 0), moment = ReviewMoment.RECIPE_LEFT))
    }

    @Test
    fun aBlockedSessionNeverAsks() {
        assertFalse(mayAsk(blocked = true))
    }

    @Test
    fun oncePerVersionAndNotWithin120Days() {
        val asked = eligible.copy(lastRequestMillis = later, lastRequestVersion = "2.1.0")
        assertFalse(mayAsk(record = asked, now = later + 400 * DAY_MILLIS))
        assertFalse(mayAsk(record = asked, version = "2.2.0", now = later + 120 * DAY_MILLIS - 1))
        assertTrue(mayAsk(record = asked, version = "2.2.0", now = later + 120 * DAY_MILLIS))
    }

    @Test
    fun theDateItselfIsStillBeforeTheDate() {
        assertTrue(ReviewPromptRule.isBeforeDate(0))
        assertFalse(ReviewPromptRule.isBeforeDate(-1))
    }
}

class ReviewPrompterTest {

    private val start = 1_800_000_000_000L
    private var now = start
    private var requests = 0
    private var requesterAnswers = true
    private val store = InMemoryReviewPromptStore()

    private fun prompter(version: String = "2.1.0", installedAt: Long? = null) = ReviewPrompter(
        store = store,
        requester = { requests++; requesterAnswers },
        appVersion = version,
        nowMillis = { now },
        installedAtMillis = installedAt
    )

    private fun eligiblePrompter(): ReviewPrompter = prompter().also {
        it.onPantrySize(2)
        now += 7 * DAY_MILLIS
    }

    @Test
    fun firstLaunchIsRecordedOnceAndPrefersAnEarlierInstall() {
        prompter(installedAt = start - 3 * DAY_MILLIS)
        assertEquals(start - 3 * DAY_MILLIS, store.record.firstLaunchMillis)
        now += DAY_MILLIS
        prompter()
        assertEquals(start - 3 * DAY_MILLIS, store.record.firstLaunchMillis)
    }

    @Test
    fun anInstallTimeInTheFutureIsIgnored() {
        prompter(installedAt = start + DAY_MILLIS)
        assertEquals(start, store.record.firstLaunchMillis)
    }

    @Test
    fun theThirdUseBeforeTheDateAsks() {
        val prompter = eligiblePrompter()
        assertFalse(prompter.onUsedOne(daysRemaining = 2))
        assertFalse(prompter.onUsedOne(daysRemaining = 0))
        assertFalse(prompter.onUsedOne(daysRemaining = -1)) // past its date: not counted
        assertEquals(2, store.record.usesBeforeDate)
        assertTrue(prompter.onUsedOne(daysRemaining = 5))
        assertEquals(1, requests)
        assertEquals("2.1.0", store.record.lastRequestVersion)
        assertEquals(now, store.record.lastRequestMillis)
        // Never twice in one version.
        assertFalse(prompter.onUsedOne(daysRemaining = 5))
        assertEquals(1, requests)
    }

    @Test
    fun usesCountAcrossLaunches() {
        prompter().apply { onPantrySize(3); onUsedOne(1); onUsedOne(1) }
        now += 8 * DAY_MILLIS
        assertTrue(prompter().onUsedOne(1))
    }

    @Test
    fun theMostItemsSeenOnlyGoesUp() {
        val prompter = prompter()
        prompter.onPantrySize(4)
        prompter.onPantrySize(0)
        assertEquals(4, store.record.mostItemsSeen)
    }

    @Test
    fun aRequestTheOsCouldNotTakeIsNotRecorded() {
        requesterAnswers = false
        val prompter = eligiblePrompter()
        repeat(3) { prompter.onUsedOne(1) }
        assertEquals(1, requests)
        assertNull(store.record.lastRequestMillis)
    }

    @Test
    fun aWrittenRecipeAsksWhenLeftNotWhenShown() {
        val prompter = eligiblePrompter()
        assertFalse(prompter.onRecipeLeft())
        prompter.onRecipeEvent(RecipeEvent.Written("r1"))
        assertEquals(0, requests)
        assertTrue(prompter.onRecipeLeft())
        assertEquals(1, requests)
    }

    @Test
    fun leavingASavedRecipeThatWasNotJustWrittenDoesNothing() {
        val prompter = eligiblePrompter()
        prompter.onRecipeEvent(RecipeEvent.Written("r1"))
        prompter.onRecipeLeft()
        // A new version, long after: only a fresh recipe arms it again.
        val next = ReviewPrompter(store, { requests++; true }, "2.2.0", { now + 200 * DAY_MILLIS })
        assertFalse(next.onRecipeLeft())
    }

    @Test
    fun aRecipeWrittenWithoutAnItemIsAProblem() {
        val prompter = eligiblePrompter()
        prompter.onRecipeEvent(RecipeEvent.Written("r1", leftOut = listOf("Milk")))
        assertFalse(prompter.onRecipeLeft())
        assertTrue(prompter.sessionBlocked)
    }

    @Test
    fun anyFailedOrRefusedGenerationBlocksTheSession() {
        val failures = listOf(
            RecipeEvent.Failed, RecipeEvent.ServerFailed, RecipeEvent.ServerFailedLater, RecipeEvent.ServerBusy,
            RecipeEvent.ServerRateLimited, RecipeEvent.ServerLimitToday, RecipeEvent.ServerTroubleToday,
            RecipeEvent.ServerRefused(1), RecipeEvent.IngredientNotSent("x"), RecipeEvent.AllergenNotSent("x"),
            RecipeEvent.TooManyAllergens(31, 30), RecipeEvent.AllergenConsentDeclined
        )
        failures.forEach { event ->
            val prompter = eligiblePrompter()
            prompter.onRecipeEvent(event)
            prompter.onRecipeEvent(RecipeEvent.Written("r1"))
            assertFalse(prompter.onRecipeLeft(), "after $event")
            repeat(3) { assertFalse(prompter.onUsedOne(1), "after $event") }
        }
        assertEquals(0, requests)
    }

    @Test
    fun purchaseAndAccountProblemsBlockTheSession() {
        assertTrue(ReviewPrompter.isProblem(PaidEvent.SignInFailed))
        assertTrue(ReviewPrompter.isProblem(PaidEvent.SignInInUse))
        assertTrue(ReviewPrompter.isProblem(PaidEvent.AccountActionFailed))
        assertTrue(ReviewPrompter.isProblem(PaidEvent.EarlierRefused(1)))
        assertFalse(ReviewPrompter.isProblem(PaidEvent.RecipesAdded(10)))
        assertFalse(ReviewPrompter.isProblem(PaidEvent.EarlierSaved(menu = false)))
        assertFalse(ReviewPrompter.isProblem(PaidUiState()))
        assertTrue(ReviewPrompter.isProblem(PaidUiState(rejection = PurchaseRejection.REFUSED)))
        assertTrue(ReviewPrompter.isProblem(PaidUiState(issue = ServerIssue.OFFLINE)))
        assertTrue(ReviewPrompter.isProblem(PaidUiState(storeRefused = true)))
        assertTrue(ReviewPrompter.isProblem(PaidUiState(sheet = BuySheet.StoreUnavailable)))
        assertFalse(ReviewPrompter.isProblem(PaidUiState(sheet = BuySheet.Loading)))

        val prompter = eligiblePrompter()
        prompter.onPaidState(PaidUiState())
        prompter.onPaidEvent(PaidEvent.RecipesAdded(10))
        assertFalse(prompter.sessionBlocked)
        prompter.onPaidState(PaidUiState(rejection = PurchaseRejection.WRONG_BUILD))
        assertTrue(prompter.sessionBlocked)
    }

    @Test
    fun onboardingAndDemoLaunchesNeverAsk() {
        eligiblePrompter().apply { onOnboardingShown(); repeat(3) { assertFalse(onUsedOne(1)) } }
        eligiblePrompter().apply { suppressForSession(); repeat(3) { assertFalse(onUsedOne(1)) } }
        eligiblePrompter().apply { onProblemShown(); repeat(3) { assertFalse(onUsedOne(1)) } }
        assertEquals(0, requests)
    }

    @Test
    fun aNewSessionStartsUnblocked() {
        val blocked = eligiblePrompter()
        blocked.onProblemShown()
        repeat(2) { blocked.onUsedOne(1) }
        assertTrue(prompter().onUsedOne(1))
    }

    @Test
    fun theNextVersionMayAskAfter120Days() {
        val first = eligiblePrompter()
        repeat(3) { first.onUsedOne(1) }
        assertEquals(1, requests)
        now += 119 * DAY_MILLIS
        assertFalse(prompter(version = "2.2.0").onUsedOne(1))
        now += DAY_MILLIS
        assertTrue(prompter(version = "2.2.0").onUsedOne(1))
        assertEquals(2, requests)
    }
}
