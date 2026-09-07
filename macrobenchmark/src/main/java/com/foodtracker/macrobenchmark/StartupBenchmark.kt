package com.foodtracker.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start with a 1000-item pantry already on disk.
 *
 * This is where the synchronous `settingsRepository.getSettings()` in `PantryStateHolder`'s
 * constructor, the first whole-table Room read, and the first filter+sort of the dashboard all
 * land, so it is sensitive to moving that work off the critical path.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        // Full AOT compilation removes JIT warm-up luck from the comparison; without it the
        // run-to-run spread on an emulator is wide enough to hide the effect being measured.
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = {
            device.grantAppPermissions()
            device.seedPantry()
            pressHome()
        }
    ) {
        startActivityAndWait()
        // Wait for the grid itself, not just the window: the dashboard renders a loading state
        // first, and measuring only to that point would hide the cost of the initial load.
        device.wait(Until.hasObject(By.scrollable(true)), 10_000)
    }
}
