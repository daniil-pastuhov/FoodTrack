package com.foodtracker.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraAccessTest {

    private val notAsked = CameraAccess(granted = false)

    @Test
    fun `asks when the camera is not granted`() {
        assertTrue(notAsked.shouldRequest)
    }

    @Test
    fun `does not ask when the camera is granted`() {
        assertFalse(CameraAccess(granted = true).shouldRequest)
    }

    @Test
    fun `does not ask twice while a request is up`() {
        assertFalse(notAsked.requested().shouldRequest)
    }

    @Test
    fun `a cancelled request is not a refusal and is asked again`() {
        val cancelled = notAsked.requested().answered(null)

        assertFalse(cancelled.refused)
        assertFalse(cancelled.granted)
        assertTrue(cancelled.shouldRequest)
    }

    @Test
    fun `a refusal shows the camera-off state and stops asking`() {
        val refused = notAsked.requested().answered(false)

        assertTrue(refused.refused)
        assertFalse(refused.granted)
        assertFalse(refused.shouldRequest)
    }

    @Test
    fun `allowing opens the camera`() {
        assertEquals(CameraAccess(granted = true), notAsked.requested().answered(true))
    }

    @Test
    fun `coming back with the camera allowed clears the refusal`() {
        val refused = notAsked.requested().answered(false)

        assertEquals(CameraAccess(granted = true), refused.resumed(true))
    }

    @Test
    fun `coming back still refused stays refused without asking`() {
        val refused = notAsked.requested().answered(false)

        assertEquals(refused, refused.resumed(false))
    }

    @Test
    fun `coming back while a request is up does not ask again`() {
        assertFalse(notAsked.requested().resumed(false).shouldRequest)
    }

    @Test
    fun `losing the permission with no answer on record asks again`() {
        assertTrue(CameraAccess(granted = true).resumed(false).shouldRequest)
    }
}
