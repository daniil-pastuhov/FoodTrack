package com.foodtracker.android.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraOffStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `open settings goes to this app's own page`() {
        val intent = appSettingsIntent(context)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
    }
}
