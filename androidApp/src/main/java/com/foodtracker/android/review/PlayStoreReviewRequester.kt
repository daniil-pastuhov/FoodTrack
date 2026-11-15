package com.foodtracker.android.review

import android.content.Context
import android.util.Log
import com.foodtracker.android.BuildConfig
import com.foodtracker.android.recipe.paid.CurrentActivity
import com.foodtracker.android.recipe.paid.installedFromPlay
import com.foodtracker.review.NoStoreReview
import com.foodtracker.review.StoreReviewRequester
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Play In-App Review over the activity in front. Play decides whether anything appears and says
 * nothing either way; every failure is swallowed, since a rating prompt that didn't show is not
 * something to tell anyone about.
 */
class PlayStoreReviewRequester(context: Context) : StoreReviewRequester {

    private val manager by lazy { ReviewManagerFactory.create(context.applicationContext) }

    override fun requestReview(): Boolean {
        val activity = CurrentActivity.activity ?: return false
        return runCatching {
            manager.requestReviewFlow().addOnCompleteListener { request ->
                val info = if (request.isSuccessful) request.result else null
                val front = CurrentActivity.activity
                // Only over the same screen it was asked from, and only if it is still there.
                if (info != null && front === activity) {
                    runCatching { manager.launchReviewFlow(activity, info) }
                }
            }
            true
        }.getOrDefault(false)
    }
}

/** Debug builds: says in logcat what release would have done, and shows nothing. */
private object LoggingStoreReviewRequester : StoreReviewRequester {
    override fun requestReview(): Boolean {
        Log.i("ReviewPrompt", "Release would ask Play for the rating prompt now")
        return true
    }
}

/**
 * The requester this build gets: Play's in release builds installed from Google Play, the
 * logging stand-in in debug builds (so the real flow never runs by default), nothing otherwise.
 */
fun storeReviewRequester(context: Context): StoreReviewRequester = when {
    BuildConfig.DEBUG -> LoggingStoreReviewRequester
    installedFromPlay(context) -> PlayStoreReviewRequester(context)
    else -> NoStoreReview
}

/** The install time from the package manager, or null if it can't be read. */
fun installedAtMillis(context: Context): Long? = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
}.getOrNull()
