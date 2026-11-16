package com.foodtracker.android.screenshots

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import org.junit.rules.ExternalResource
import java.util.TimeZone

/** Pixel 5 in British English, fixed for every screenshot (the generated preview tests get it
 *  through robolectricConfig in androidApp/build.gradle.kts). A preview's own locale still wins. */
const val PIXEL_5_EN_GB = "en-rGB-w393dp-h851dp-normal-long-notround-any-440dpi-keyshidden-nonav"

/** The zone every screenshot is taken in, whatever the machine's: a time stored as epoch millis
 *  (a saved recipe's date) must land on the same day for everyone. */
const val SCREENSHOT_TIME_ZONE = "UTC"

/**
 * What every screenshot runs in, besides the qualifiers:
 * - the time zone is [SCREENSHOT_TIME_ZONE];
 * - nothing comes from the network: Coil gets a loader that fails every request at once, so a
 *   picture URL in sample data draws the placeholder icon, the same each run.
 */
class ScreenshotEnvironmentRule : ExternalResource() {
    private var savedZone: TimeZone? = null

    override fun before() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(SCREENSHOT_TIME_ZONE))
        val context = ApplicationProvider.getApplicationContext<Context>()
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(NoNetwork) }
                .build()
        )
    }

    override fun after() {
        Coil.reset()
        TimeZone.setDefault(savedZone)
    }

    private object NoNetwork : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
            ErrorResult(null, chain.request, IllegalStateException("No network in screenshot tests: ${chain.request.data}"))
    }
}
