package com.foodtracker.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flings the dashboard grid over 1000 items.
 *
 * The frame timings here are driven by how much work each `ItemCard` does per frame and by
 * whether it can skip recomposition at all — the collapsing header writes `headerOffsetPx` on
 * every scroll frame, which invalidates the whole screen including the grid content lambda.
 */
@RunWith(AndroidJUnit4::class)
class PantryScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollPantryGrid() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Full(),
        // WARM, with the launch done in setupBlock, so startup frames are not counted in the
        // scroll measurement.
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
        val grid = device.findObject(By.scrollable(true))
        // Keep the gesture away from the screen edges, which belong to the system's
        // back/navigation gesture areas rather than to the grid.
        grid.setGestureMargin(device.displayWidth / 5)

        repeat(4) {
            grid.fling(Direction.DOWN)
        }
        repeat(2) {
            grid.fling(Direction.UP)
        }
        device.waitForIdle()
    }
}
