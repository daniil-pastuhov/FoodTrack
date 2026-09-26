package com.foodtracker.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.foodtracker.android.location.StoreGeofenceManager
import com.foodtracker.android.ui.AppScreen
import com.foodtracker.android.ui.FoodTrackerRoot
import com.foodtracker.android.util.shareRecipePrompt
import com.foodtracker.viewmodel.PantryStateHolder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val stateHolder: PantryStateHolder by inject()

    // Hoisted out of the composable (rather than `remember`ed) so the Shopping List widget's
    // tap intent can navigate there via onNewIntent even when MainActivity is already running,
    // not just on a cold start.
    private val currentScreenState = mutableStateOf(AppScreen.DASHBOARD)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenFromIntent(intent)
        // Re-asserted on every start rather than only when Settings changes: geofences are
        // OS-level registrations that can be dropped underneath the app (a reboot clears them
        // all), and permission can be revoked from system settings between runs.
        StoreGeofenceManager.sync(this, stateHolder.uiState.value.settings)

        setContent {
            FoodTrackerRoot(
                stateHolder = stateHolder,
                currentScreenState = currentScreenState,
                onAddCurrentLocationAsStore = ::resolveCurrentLocation
            )
        }
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
        if (shareRecipeFromIntent(intent)) return
        if (intent.getBooleanExtra(EXTRA_OPEN_SHOPPING_LIST, false)) {
            currentScreenState.value = AppScreen.SHOPPING_LIST
            return
        }
        when (intent.getStringExtra(EXTRA_SHORTCUT)) {
            SHORTCUT_SCAN -> currentScreenState.value = AppScreen.SCANNER
            SHORTCUT_ADD_ITEM -> currentScreenState.value = AppScreen.ADD_EDIT
            SHORTCUT_SHOPPING_LIST -> currentScreenState.value = AppScreen.SHOPPING_LIST
        }
    }

    /**
     * Handles the digest notification's "Recipe ideas" action, which carries the ids of the
     * items it reported. Returns true when it consumed the intent, so the caller leaves the
     * current screen alone — this action isn't a navigation request, it raises a share sheet
     * over whatever the user was already looking at.
     *
     * The ids are resolved against live pantry state rather than trusting names baked into the
     * intent, so anything eaten between the notification firing and the button being tapped
     * simply drops out of the prompt. If nothing resolves (the whole set is gone), the share is
     * skipped by [shareRecipePrompt] itself.
     *
     * The extra is cleared afterwards because [setIntent] makes this intent the activity's
     * current one: without that, a later configuration change or a return from the chooser
     * re-runs [applyScreenFromIntent] and raises the share sheet a second time.
     */
    private fun shareRecipeFromIntent(intent: Intent): Boolean {
        val ids = intent.getStringArrayListExtra(EXTRA_RECIPE_FOR_IDS) ?: return false
        intent.removeExtra(EXTRA_RECIPE_FOR_IDS)
        val requested = ids.toSet()
        val items = stateHolder.uiState.value.items.filter { it.id in requested }
        shareRecipePrompt(this, items)
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
     * just demonstrated wanting.
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
        backgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    companion object {
        const val EXTRA_OPEN_SHOPPING_LIST = "com.foodtracker.android.EXTRA_OPEN_SHOPPING_LIST"
        const val EXTRA_SHORTCUT = "com.foodtracker.android.EXTRA_SHORTCUT"

        /** Pantry item ids the digest notification's "Recipe ideas" action asks to build a
         *  prompt for. See [shareRecipeFromIntent]. */
        const val EXTRA_RECIPE_FOR_IDS = "com.foodtracker.android.EXTRA_RECIPE_FOR_IDS"
        const val SHORTCUT_SCAN = "scan"
        const val SHORTCUT_ADD_ITEM = "add_item"
        const val SHORTCUT_SHOPPING_LIST = "shopping_list"
    }
}
