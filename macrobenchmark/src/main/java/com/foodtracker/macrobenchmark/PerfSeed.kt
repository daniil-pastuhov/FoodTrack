package com.foodtracker.macrobenchmark

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/** Package under measurement — :androidApp's `benchmark` build type. */
const val TARGET_PACKAGE = "com.foodtracker.android"

/** How many pantry items every benchmark seeds. Large enough that per-item work in composition
 *  and O(items × preferences) filtering are measurable, small enough to seed in ~a second. */
const val SEED_ITEM_COUNT = 1000

/**
 * Replaces the app's database with [com.foodtracker.perf.PerfDataset]'s fixed-seed contents.
 *
 * Every benchmark calls this from its `setupBlock`, so a "before" and an "after" run measure the
 * same list of items — otherwise whatever happened to be on the device would move the numbers
 * more than the code change does.
 *
 * `-W` makes `am` wait for the receiver to finish, and the receiver seeds synchronously, so this
 * returns only once the data is actually committed. `-f 0x01000000` is FLAG_INCLUDE_STOPPED_
 * PACKAGES, needed because the app is force-stopped between iterations.
 */
fun UiDevice.seedPantry(itemCount: Int = SEED_ITEM_COUNT) {
    val output = executeShellCommand(
        "am broadcast -W --receiver-foreground " +
            "-a com.foodtracker.android.PERF_SEED " +
            "-f 0x01000000 " +
            "--ei count $itemCount " +
            "-n $TARGET_PACKAGE/com.foodtracker.android.perf.PerfSeedReceiver"
    )
    // Fail loudly rather than silently benchmarking an empty or stale database — a seeding
    // regression would otherwise look like a spectacular performance win.
    check(output.contains("PERF_SEED_OK")) {
        "Pantry seeding failed. `am broadcast` returned:\n$output"
    }
}

/**
 * Grants the runtime permissions the app asks for on launch.
 *
 * Without this, `MainActivity`'s `LaunchedEffect` puts up the POST_NOTIFICATIONS system dialog
 * over the dashboard on every iteration. UiAutomator then finds no scrollable grid, and any
 * frames measured would be the permission dialog's rather than the app's.
 */
fun UiDevice.grantAppPermissions() {
    listOf(
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.CAMERA"
    ).forEach { permission ->
        // Fails harmlessly on API levels where the permission doesn't exist.
        executeShellCommand("pm grant $TARGET_PACKAGE $permission")
    }
}

/** Clears any prior process state so each iteration starts from the same place. */
fun UiDevice.resetApp() {
    executeShellCommand("am force-stop $TARGET_PACKAGE")
}

internal fun uiDevice(): UiDevice =
    UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
