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
 * Pages the calendar forward and back a month at a time.
 *
 * Every page rebuilds the 42-cell month grid and re-derives each day's indicator colour from the
 * items expiring that day, so this is the benchmark for work left inside composition rather than
 * hoisted into the state holder.
 */
@RunWith(AndroidJUnit4::class)
class CalendarPagingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun pageThroughMonths() = benchmarkRule.measureRepeated(
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
            device.wait(Until.findObject(By.text("Calendar")), 3_000)?.click()
                ?: error("Calendar tab not found in the bottom nav")
            check(device.wait(Until.hasObject(By.desc("Next month")), 5_000)) {
                "Calendar screen never appeared"
            }
        }
    ) {
        repeat(6) {
            device.findObject(By.desc("Next month")).click()
            device.waitForIdle()
        }
        repeat(6) {
            device.findObject(By.desc("Previous month")).click()
            device.waitForIdle()
        }
    }
}
