package com.foodtracker.review

import com.foodtracker.recipe.BuySheet
import com.foodtracker.recipe.PaidEvent
import com.foodtracker.recipe.PaidUiState
import com.foodtracker.recipe.RecipeEvent
import kotlin.time.Clock

/** Where [ReviewPromptRecord] is kept: its own settings key, never the backup. */
interface ReviewPromptStore {
    fun load(): ReviewPromptRecord
    fun save(record: ReviewPromptRecord)
}

class InMemoryReviewPromptStore(var record: ReviewPromptRecord = ReviewPromptRecord()) : ReviewPromptStore {
    override fun load(): ReviewPromptRecord = record
    override fun save(record: ReviewPromptRecord) {
        this.record = record
    }
}

/**
 * Asks the OS for its rating prompt. Android's is Play In-App Review in release builds installed
 * from Play, and nothing elsewhere. Never shows anything of ours, and never fails loudly.
 */
fun interface StoreReviewRequester {
    /** Starts the OS flow; false when it could not even be asked (no screen in front, no Play).
     *  True doesn't mean a prompt appeared: the OS decides that and doesn't say. */
    fun requestReview(): Boolean
}

/** Builds that never ask: sideloads, forks, tests. */
val NoStoreReview = StoreReviewRequester { false }

/**
 * Follows the session and the moments for [ReviewPromptRule], and asks [requester] when one
 * passes. Call it from the main thread only: it has no locking of its own.
 *
 * iOS ports this as `ReviewPrompter` in `iosApp/FoodTracker/Settings/ReviewPrompt.swift`.
 */
class ReviewPrompter(
    private val store: ReviewPromptStore,
    private val requester: StoreReviewRequester,
    private val appVersion: String,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** When the app was installed, if the platform knows: counts as the first launch when earlier. */
    installedAtMillis: Long? = null
) {
    private var record: ReviewPromptRecord = store.load()

    /** Something went wrong in front of the person, onboarding ran, or this is a demo launch. */
    var sessionBlocked: Boolean = false
        private set

    /** A freshly written recipe is on screen; leaving it is the moment. */
    private var recipeShown = false

    init {
        if (record.firstLaunchMillis == null) {
            val now = nowMillis()
            update(record.copy(firstLaunchMillis = installedAtMillis?.takeIf { it in 1..now } ?: now))
        }
    }

    /** Demo and screenshot launches: no prompt may appear in store screenshots. */
    fun suppressForSession() {
        sessionBlocked = true
    }

    fun onOnboardingShown() {
        sessionBlocked = true
    }

    /** An error, a refused permission, a failed or refused generation, a purchase problem. */
    fun onProblemShown() {
        sessionBlocked = true
    }

    fun onPantrySize(count: Int) {
        if (count > record.mostItemsSeen) update(record.copy(mostItemsSeen = count))
    }

    /** "Use one" on an item with [daysRemaining] left. Returns whether the OS was asked. */
    fun onUsedOne(daysRemaining: Int): Boolean {
        if (!ReviewPromptRule.isBeforeDate(daysRemaining)) return false
        update(record.copy(usesBeforeDate = record.usesBeforeDate + 1))
        return askIfDue(ReviewMoment.USED_BEFORE_DATE)
    }

    /** Classifies one recipe-tab event: a written recipe arms [onRecipeLeft], anything else that
     *  went wrong blocks the session. */
    fun onRecipeEvent(event: RecipeEvent?) {
        when (event) {
            null -> Unit
            is RecipeEvent.Written -> if (event.leftOut.isEmpty()) recipeShown = true else onProblemShown()
            else -> onProblemShown()
        }
    }

    fun onPaidEvent(event: PaidEvent?) {
        if (event != null && isProblem(event)) onProblemShown()
    }

    /** Lasting conditions of the paid route the tab shows as a problem. */
    fun onPaidState(state: PaidUiState) {
        if (isProblem(state)) onProblemShown()
    }

    /** The person left the recipe that was just written (to anything but its own dishes).
     *  Returns whether the OS was asked. */
    fun onRecipeLeft(): Boolean {
        if (!recipeShown) return false
        recipeShown = false
        return askIfDue(ReviewMoment.RECIPE_LEFT)
    }

    private fun askIfDue(moment: ReviewMoment): Boolean {
        val now = nowMillis()
        if (!ReviewPromptRule.mayAsk(record, moment, now, appVersion, sessionBlocked)) return false
        if (!requester.requestReview()) return false
        update(record.copy(lastRequestMillis = now, lastRequestVersion = appVersion))
        return true
    }

    private fun update(updated: ReviewPromptRecord) {
        record = updated
        store.save(updated)
    }

    companion object {
        /** A deletion, done or not, counts too: nobody is asked for a rating after leaving. */
        fun isProblem(event: PaidEvent): Boolean = when (event) {
            PaidEvent.SignInFailed, PaidEvent.SignInInUse, PaidEvent.AccountActionFailed, is PaidEvent.EarlierRefused,
            PaidEvent.AccountDeleted, PaidEvent.DeviceDataDeleted, PaidEvent.AccountBusy, PaidEvent.DeletionWaitsForPurchase -> true
            is PaidEvent.RecipesAdded, is PaidEvent.Merged, is PaidEvent.EarlierSaved -> false
        }

        fun isProblem(state: PaidUiState): Boolean =
            state.rejection != null || state.issue != null || state.storeRefused || state.sheet == BuySheet.StoreUnavailable ||
                state.deletionTried
    }
}
