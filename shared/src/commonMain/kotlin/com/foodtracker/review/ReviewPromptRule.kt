package com.foodtracker.review

import kotlinx.serialization.Serializable

/**
 * What the store-rating prompt remembers between launches. Kept under its own settings key,
 * outside `AppSettings`, so it is never in a backup and a restore can't reset it.
 *
 * iOS keeps the same fields in `ReviewPromptRecord` (`iosApp/FoodTracker/Settings/ReviewPrompt.swift`).
 */
@Serializable
data class ReviewPromptRecord(
    /** When the app first ran with this rule in it (on Android, the install time if earlier). */
    val firstLaunchMillis: Long? = null,
    /** The most pantry items seen at once: a lower bound on how many were ever added, and one
     *  that every way of adding (scan, form, batch, receipt, restore) moves without its own hook. */
    val mostItemsSeen: Int = 0,
    /** "Use one" taps on an item that was not past its date. */
    val usesBeforeDate: Int = 0,
    val lastRequestMillis: Long? = null,
    /** The app version the prompt was last asked for in. */
    val lastRequestVersion: String? = null
)

/** The positive moments the prompt may follow. */
enum class ReviewMoment {
    /** An item was used before its date, for the [ReviewPromptRule.MIN_USES_BEFORE_DATE]th time or later. */
    USED_BEFORE_DATE,

    /** A recipe or menu that had just been written was shown, and the person left it. */
    RECIPE_LEFT
}

/**
 * When the app may ask the OS for its rating prompt. Pure, so both apps follow the same rule and
 * tests can pin it; iOS ports it as `ReviewPromptRule` in `ReviewPrompt.swift`.
 *
 * The OS draws the prompt and throttles it on its own (it may show nothing); this is our own
 * politeness on top: only after a positive moment, never while the person is new, in onboarding
 * or in a session where something went wrong, and at most once per app version and 120 days.
 */
object ReviewPromptRule {
    const val MIN_DAYS_SINCE_FIRST_LAUNCH = 7
    const val MIN_ITEMS_ADDED = 2
    const val MIN_USES_BEFORE_DATE = 3
    const val MIN_DAYS_BETWEEN_REQUESTS = 120

    const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Whether a "Use one" on an item with [daysRemaining] left counts: on its date still does. */
    fun isBeforeDate(daysRemaining: Int): Boolean = daysRemaining >= 0

    /**
     * Whether [moment] may lead to the prompt now. [sessionBlocked] is true when this session
     * showed onboarding, an error, a failed or refused generation, a purchase problem or a
     * refused permission, or is a demo/screenshot launch.
     *
     * A clock set back before [ReviewPromptRecord.firstLaunchMillis] or the last request reads
     * as "too soon", never as "long enough ago".
     */
    fun mayAsk(
        record: ReviewPromptRecord,
        moment: ReviewMoment,
        nowMillis: Long,
        appVersion: String,
        sessionBlocked: Boolean
    ): Boolean {
        if (sessionBlocked) return false
        val firstLaunch = record.firstLaunchMillis ?: return false
        if (nowMillis - firstLaunch < MIN_DAYS_SINCE_FIRST_LAUNCH * DAY_MILLIS) return false
        if (record.mostItemsSeen < MIN_ITEMS_ADDED) return false
        if (moment == ReviewMoment.USED_BEFORE_DATE && record.usesBeforeDate < MIN_USES_BEFORE_DATE) return false
        if (record.lastRequestVersion == appVersion) return false
        val lastRequest = record.lastRequestMillis
        if (lastRequest != null && nowMillis - lastRequest < MIN_DAYS_BETWEEN_REQUESTS * DAY_MILLIS) return false
        return true
    }
}
