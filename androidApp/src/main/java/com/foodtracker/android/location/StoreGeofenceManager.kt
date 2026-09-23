package com.foodtracker.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.foodtracker.model.AppSettings
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registers (and clears) the geofences behind the "you're near a shop" shopping-list reminder.
 *
 * Kept as an object with explicit `register`/`clear` calls rather than anything that observes
 * settings by itself: geofences are OS-level registrations that outlive the process, so the
 * only correct model is to re-assert them at the few points where the intent can have changed —
 * app start, a settings edit, and boot (the OS drops every geofence on reboot).
 */
object StoreGeofenceManager {

    private const val REQUEST_CODE = -2000

    /** Geofences never expire on their own; the app controls their lifetime. */
    private const val EXPIRATION_NEVER = Geofence.NEVER_EXPIRE

    /**
     * How long the user must stay inside the radius before the reminder fires.
     *
     * Without this, driving past a shop trips the fence. Two minutes is roughly "stopped here"
     * rather than "went by", and the OS applies it without waking the app in the meantime.
     */
    private const val LOITERING_DELAY_MILLIS = 2 * 60 * 1000

    /**
     * Re-registers every geofence implied by [settings], or clears them all when the feature is
     * off or has no stores.
     *
     * Silently does nothing when location permission hasn't been granted — this is called on
     * app start and after any settings write, both of which can happen before the user has been
     * asked. The Settings screen is what asks; this only ever acts on what it was given.
     */
    fun sync(context: Context, settings: AppSettings) {
        if (!settings.storeRemindersEnabled || settings.stores.isEmpty()) {
            clear(context)
            return
        }
        if (!hasLocationPermission(context)) return

        val geofences = settings.stores.map { store ->
            Geofence.Builder()
                .setRequestId(store.id)
                .setCircularRegion(store.latitude, store.longitude, store.radiusMeters.toFloat())
                .setExpirationDuration(EXPIRATION_NEVER)
                .setLoiteringDelay(LOITERING_DELAY_MILLIS)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                .build()
        }

        val request = GeofencingRequest.Builder()
            // Deliberately not INITIAL_TRIGGER_ENTER: registering happens on every app start,
            // and with ENTER that would fire the reminder every time the app is opened while at
            // home if home happened to be a saved store — and, more commonly, the moment the
            // user finishes adding the shop they are currently standing in.
            .setInitialTrigger(0)
            .addGeofences(geofences)
            .build()

        // Permission is checked above; the lint check can't see through that helper.
        @SuppressLint("MissingPermission")
        val client = LocationServices.getGeofencingClient(context)
        client.removeGeofences(pendingIntent(context)).addOnCompleteListener {
            @SuppressLint("MissingPermission")
            client.addGeofences(request, pendingIntent(context))
        }
    }

    fun clear(context: Context) {
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
    }

    /** True once the user has granted at least coarse location. Background location is needed
     *  for the fence to fire while the app is closed, but its absence is not a reason to skip
     *  registering — a while-in-use fence still works when the app is open. */
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, StoreGeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // MUTABLE is required: the OS writes the triggering geofence's details into this
            // intent's extras before delivering it, which an immutable PendingIntent forbids.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
}
