package com.foodtracker.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.foodtracker.android.location.StoreGeofenceManager
import com.foodtracker.android.ui.FoodTrackerRoot
import com.foodtracker.android.ui.navigation.AddItemMode
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.backup.RestoreMode
import com.foodtracker.android.notification.NotificationHelper
import com.foodtracker.repository.PantryRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.lifecycleScope
import com.foodtracker.recipe.RecipeStateHolder
import com.foodtracker.viewmodel.PantryStateHolder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val stateHolder: PantryStateHolder by inject()
    private val recipeStateHolder: RecipeStateHolder by inject()
    private val pantryRepository: PantryRepository by inject()

    // Hoisted out of the composable (rather than `remember`ed) so the Shopping List widget's
    // tap intent can navigate there via onNewIntent even when MainActivity is already running,
    // not just on a cold start.
    private val currentScreenState = mutableStateOf<LarderRoute>(LarderRoute.Home)

    /** Held while the location permission dialog is up, so the Settings screen's spinner is
     *  always answered — whichever way the user decides. */
    private var pendingLocationResult: ((Double?, Double?) -> Unit)? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            val callback = pendingLocationResult ?: return@registerForActivityResult
            pendingLocationResult = null
            if (granted.values.any { it }) {
                requestCurrentLocation(callback)
            } else {
                callback(null, null)
            }
        }

    /** Escalation to "allow all the time", asked for only after a shop has been saved. Its
     *  result needs no handling: the geofences are registered either way, and without it they
     *  simply stop firing once the app is closed. */
    private val backgroundLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Whether the in-app disclosure that has to come before the background location request
     *  is showing (see BackgroundLocationDisclosureDialog). */
    private val backgroundLocationDisclosureState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a genuine first launch. When the system recreates the activity (rotation, a
        // reclaimed background activity), `intent` is still the one that first launched it, so
        // re-applying it would jump back to a long-gone shortcut's screen — e.g. reopening the
        // app from the launcher landing on Add Item. A newer intent that caused the recreation
        // arrives separately through onNewIntent.
        if (savedInstanceState == null) {
            if (isDebuggable()) loadDemoFromIntent(intent)
            applyScreenFromIntent(intent)
        }
        // Re-asserted on every start rather than only when Settings changes: geofences are
        // OS-level registrations that can be dropped underneath the app (a reboot clears them
        // all), and permission can be revoked from system settings between runs.
        StoreGeofenceManager.sync(this, stateHolder.uiState.value.settings)
        // Likewise the reminder alarms, which a force-stop clears as well as a reboot.
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                NotificationHelper.restoreReminders(applicationContext, pantryRepository)
            }
        }

        setContent {
            FoodTrackerRoot(
                stateHolder = stateHolder,
                recipeStateHolder = recipeStateHolder,
                currentScreenState = currentScreenState,
                onAddCurrentLocationAsStore = ::resolveCurrentLocation,
                backgroundLocationDisclosureState = backgroundLocationDisclosureState,
                onBackgroundLocationDisclosureAccepted = {
                    backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            )
        }
    }

    /**
     * Debug builds only, for scripts/android_screenshots.sh: `--es demoBackup <path>` replaces
     * everything with that backup file, as Settings' Replace restore does, and `--es demoTab
     * <home|recipe|shopping|settings>` opens that tab, or `edit:<item id>` that item's Edit
     * screen. The counterpart of iOS's `-demoBackup` and `-demoTab` launch arguments. A restore
     * also marks onboarding done, so onboarding is skipped from the next launch on.
     */
    private fun loadDemoFromIntent(intent: Intent) {
        intent.getStringExtra(EXTRA_DEMO_BACKUP)?.let { path ->
            val raw = runCatching { java.io.File(path).readText() }.getOrNull()
            if (raw == null) {
                android.util.Log.w("DemoBackup", "Can't read $path")
            } else {
                stateHolder.restoreBackup(raw, RestoreMode.REPLACE) { result, summary ->
                    android.util.Log.i("DemoBackup", "$result, $summary")
                }
            }
        }
        val tab = intent.getStringExtra(EXTRA_DEMO_TAB)
        when {
            tab == null -> Unit
            tab.startsWith("edit:") ->
                currentScreenState.value = LarderRoute.AddItem(AddItemMode.Edit(tab.removePrefix("edit:")))
            tab == "home" -> currentScreenState.value = LarderRoute.Home
            tab == "recipe" -> currentScreenState.value = LarderRoute.Recipe
            tab == "shopping" -> currentScreenState.value = LarderRoute.Shopping
            tab == "settings" -> currentScreenState.value = LarderRoute.Settings
        }
    }

    private fun isDebuggable(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Re-derives the pantry when the date may have moved under it. See
     *  [PantryStateHolder.refreshToday] for why its own midnight timer isn't enough. */
    private val dateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = stateHolder.refreshToday()
    }

    override fun onStart() {
        super.onStart()
        // The app may have sat in the background (or the device asleep) across midnight.
        stateHolder.refreshToday()
        // Only while visible: in the background nothing shows the labels, and onStart catches up.
        // NOT_EXPORTED still receives these, since they are sent by the system.
        ContextCompat.registerReceiver(
            this,
            dateChangeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        unregisterReceiver(dateChangeReceiver)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScreenFromIntent(intent)
    }

    /**
     * Routes an incoming intent to the screen it asks for: the widgets' tap targets, and the
     * launcher shortcuts declared in `res/xml/shortcuts.xml`.
     *
     * Shortcuts arrive as an extra rather than a URI scheme because nothing outside the app
     * needs to link here - a scheme would mean an exported, externally-addressable entry point
     * for no benefit.
     */
    private fun applyScreenFromIntent(intent: Intent) {
        // First run finishes before anything else opens.
        if (currentScreenState.value == LarderRoute.Onboarding) return
        if (openRecipeFromIntent(intent)) return
        if (intent.getBooleanExtra(EXTRA_OPEN_SHOPPING_LIST, false)) {
            currentScreenState.value = LarderRoute.Shopping
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_DASHBOARD, false)) {
            currentScreenState.value = LarderRoute.Home
            return
        }
        when (intent.getStringExtra(EXTRA_SHORTCUT)) {
            SHORTCUT_SCAN -> currentScreenState.value = LarderRoute.Scanner()
            SHORTCUT_ADD_ITEM -> currentScreenState.value = LarderRoute.AddItem()
            SHORTCUT_SHOPPING_LIST -> currentScreenState.value = LarderRoute.Shopping
        }
    }

    /**
     * Handles the digest notification's "Recipe ideas" action, which carries the ids of the
     * items it reported: opens the Recipe tab with those ticked. Nothing is written or shared by
     * itself; the person still chooses a route.
     *
     * The ids are resolved against the live pantry by the Recipe tab, so anything eaten between
     * the notification firing and the tap simply drops out (and ids work before the pantry has
     * loaded, on a cold start).
     *
     * The extra is cleared afterwards because [setIntent] makes this intent the activity's
     * current one: without that, a later configuration change re-runs [applyScreenFromIntent]
     * and resets the person's ticks.
     */
    private fun openRecipeFromIntent(intent: Intent): Boolean {
        val ids = intent.getStringArrayListExtra(EXTRA_RECIPE_FOR_IDS) ?: return false
        intent.removeExtra(EXTRA_RECIPE_FOR_IDS)
        recipeStateHolder.selectItems(ids)
        currentScreenState.value = LarderRoute.Recipe
        return true
    }

    /**
     * Resolves the device's current position so Settings can save it as a store, asking for
     * location permission first if it hasn't been granted.
     *
     * Asks for **while-in-use** only. Background location is what makes the geofence fire with
     * the app closed, but requesting it up front is the single most common cause of both a
     * denial and a store-review rejection; it is escalated separately, once the user has the
     * feature switched on and a shop saved (see [promptForBackgroundLocationIfNeeded]).
     *
     * [onResolved] is always called exactly once — with nulls when permission was refused or no
     * fix arrived — so the Settings screen never leaves its spinner running.
     */
    private fun resolveCurrentLocation(
        @Suppress("UNUSED_PARAMETER") label: String,
        onResolved: (latitude: Double?, longitude: Double?) -> Unit
    ) {
        if (!StoreGeofenceManager.hasLocationPermission(this)) {
            pendingLocationResult = onResolved
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        requestCurrentLocation(onResolved)
    }

    @SuppressLint("MissingPermission") // Callers check via StoreGeofenceManager.hasLocationPermission.
    private fun requestCurrentLocation(onResolved: (Double?, Double?) -> Unit) {
        // getCurrentLocation rather than lastLocation: the cached fix can be hours old and
        // somewhere else entirely, and saving *that* as a shop would put the geofence in the
        // wrong place with no sign anything went wrong.
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location == null) {
                    onResolved(null, null)
                } else {
                    onResolved(location.latitude, location.longitude)
                    promptForBackgroundLocationIfNeeded()
                }
            }
            .addOnFailureListener { onResolved(null, null) }
    }

    /**
     * Asks for background location, but only after a shop has actually been saved — at which
     * point the permission dialog's "Allow all the time" has an obvious purpose the user has
     * just demonstrated wanting. The system request itself comes after the in-app disclosure
     * Google Play requires, and only if the user continues past it.
     *
     * Skipped below API 29, where background location isn't a separate grant.
     */
    private fun promptForBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        backgroundLocationDisclosureState.value = true
    }

    companion object {
        private const val EXTRA_DEMO_BACKUP = "demoBackup"
        private const val EXTRA_DEMO_TAB = "demoTab"

        const val EXTRA_OPEN_SHOPPING_LIST = "com.foodtracker.android.EXTRA_OPEN_SHOPPING_LIST"

        /** Sent by the expiry widgets' title taps and the expiry/digest notification taps.
         *  Needed now that the activity is singleTop:
         *  a plain intent would reach the running instance via onNewIntent and leave it on
         *  whatever screen it was showing, rather than on the dashboard the tap implies. */
        const val EXTRA_OPEN_DASHBOARD = "com.foodtracker.android.EXTRA_OPEN_DASHBOARD"
        const val EXTRA_SHORTCUT = "com.foodtracker.android.EXTRA_SHORTCUT"

        /** Pantry item ids the digest notification's "Recipe ideas" action opens the Recipe tab
         *  with. See [openRecipeFromIntent]. */
        const val EXTRA_RECIPE_FOR_IDS = "com.foodtracker.android.EXTRA_RECIPE_FOR_IDS"
        const val SHORTCUT_SCAN = "scan"
        const val SHORTCUT_ADD_ITEM = "add_item"
        const val SHORTCUT_SHOPPING_LIST = "shopping_list"
    }
}
