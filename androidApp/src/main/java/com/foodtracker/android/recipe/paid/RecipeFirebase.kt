package com.foodtracker.android.recipe.paid

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.foodtracker.android.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The region every callable is deployed in (functions/CONTRACT.md). */
const val RECIPE_FUNCTIONS_REGION = "europe-west1"

/**
 * Whether this build may offer the paid route, worked out without any network call: a release
 * build installed by Google Play (Play Integrity attests it), or any debug build (the debug App
 * Check provider, or the emulators' stand-in with -PrecipeEmulatorHost). Forks and sideloaded
 * release builds can't attest, so they keep the free routes only. Where it is allowed, the route
 * still hides itself at run time if Play sells the build no pack or App Check refuses it
 * (RecipePaid's noteOffered and continuePurchase).
 */
fun recipePaidRouteBuildAllowed(context: Context): Boolean {
    if (BuildConfig.DEBUG) return true
    return installedFromPlay(context)
}

private fun installedFromPlay(context: Context): Boolean {
    val installer = runCatching {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            manager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
    return installer == PLAY_STORE_PACKAGE
}

private const val PLAY_STORE_PACKAGE = "com.android.vending"

/**
 * Firebase as the paid route uses it, set up on first use and not before: App Check's provider is
 * installed, and the emulators pointed at, only when a purchase or a call first needs them. The
 * default app itself is started by the google-services plugin, as Crashlytics needs it.
 */
class RecipeFirebase(private val context: Context) {

    private val emulatorHost: String? = BuildConfig.RECIPE_EMULATOR_HOST.takeIf { BuildConfig.DEBUG && it.isNotEmpty() }

    private val configured = mutableSetOf<String>()

    /** The app the person's own user lives in. */
    val primary: FirebaseApp get() = prepared(FirebaseApp.getInstance())

    /**
     * A second app with the same project, for signing in to an existing account while this
     * install's anonymous user stays signed in to [primary]: used to read that account's count,
     * and to join the two, before the person has agreed to switch.
     */
    val merge: FirebaseApp
        get() = prepared(
            FirebaseApp.getApps(context).firstOrNull { it.name == MERGE_APP }
                ?: FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, MERGE_APP)
        )

    fun auth(app: FirebaseApp = primary): FirebaseAuth = FirebaseAuth.getInstance(app)

    fun functions(app: FirebaseApp = primary): FirebaseFunctions = FirebaseFunctions.getInstance(app, RECIPE_FUNCTIONS_REGION)

    @Synchronized
    private fun prepared(app: FirebaseApp): FirebaseApp {
        if (configured.add(app.name)) {
            FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(recipeAppCheckProviderFactory())
            emulatorHost?.let { host ->
                FirebaseAuth.getInstance(app).useEmulator(host, AUTH_EMULATOR_PORT)
                FirebaseFunctions.getInstance(app, RECIPE_FUNCTIONS_REGION).useEmulator(host, FUNCTIONS_EMULATOR_PORT)
            }
        }
        return app
    }

    private companion object {
        const val MERGE_APP = "recipe-merge"
        const val AUTH_EMULATOR_PORT = 9099
        const val FUNCTIONS_EMULATOR_PORT = 5001
    }
}

/** The activity in front, for the flows that need one: Play's payment sheet, Credential
 *  Manager, and Apple's sign-in page. Registered from the Application. */
object CurrentActivity : Application.ActivityLifecycleCallbacks {
    private var resumed: WeakReference<Activity>? = null

    val activity: Activity? get() = resumed?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

/** A Play services task as a suspending call. */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
