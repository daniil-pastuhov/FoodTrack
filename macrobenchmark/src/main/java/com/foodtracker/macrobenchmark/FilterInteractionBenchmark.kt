package com.foodtracker.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Taps the three status-filter metric cards in sequence.
 *
 * Each tap runs the whole filter + sort over 1000 items. Those run synchronously inside the
 * click handler, i.e. on the main thread, so the cost lands directly in frame time — this is
 * the benchmark that should move most when that work is pushed onto a background dispatcher.
 */
@RunWith(AndroidJUnit4::class)
class FilterInteractionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun toggleStatusFilters() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            device.grantAppPermissions()
            device.seedPantry()
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.scrollable(true)), 10_000)) {
                "Pantry grid never appeared — seeding or launch failed"
            }
        }
    ) {
        // Each label is tapped twice: once to apply that status filter, once to clear it back
        // to ALL. Both directions re-run the full filter+sort.
        listOf("Expired", "Soon", "Fresh").forEach { label ->
            val card = device.wait(Until.findObject(By.text(label)), 3_000)
                ?: error("Metric card '$label' not found on the dashboard")
            card.click()
            device.waitForIdle()
            device.wait(Until.findObject(By.text(label)), 3_000)?.click()
            device.waitForIdle()
        }
    }
}
