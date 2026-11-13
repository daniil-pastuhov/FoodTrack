package com.foodtracker.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {

    private val askBoth = OnboardingAsks(notifications = true, camera = true)
    private val askCamera = OnboardingAsks(notifications = false, camera = true)
    private val askNothing = OnboardingAsks(notifications = false, camera = false)

    private val atCamera = OnboardingFlow().started(askBoth).notificationsAnswered(true, askCamera)

    @Test
    fun `a fresh install goes through both rationales`() {
        val started = OnboardingFlow().started(askBoth)

        assertEquals(OnboardingStep.Notifications, started.step)
        assertEquals(OnboardingStep.Camera, started.notificationsAnswered(true, askCamera).step)
    }

    @Test
    fun `no notification permission to ask for skips its screen`() {
        assertEquals(OnboardingStep.Camera, OnboardingFlow().started(askCamera).step)
    }

    @Test
    fun `nothing left to ask goes straight to ready`() {
        assertEquals(OnboardingStep.Ready, OnboardingFlow().started(askNothing).step)
    }

    @Test
    fun `allowing both ends on ready`() {
        val done = atCamera.cameraAnswered(true)

        assertEquals(OnboardingStep.Ready, done.step)
        assertEquals(OnboardingExit.Scanner, done.primaryExit)
    }

    @Test
    fun `not now is not a refusal, so it ends on ready`() {
        val done = OnboardingFlow().started(askBoth).notificationsAnswered(null, askBoth).cameraAnswered(null)

        assertEquals(OnboardingStep.Ready, done.step)
        assertFalse(done.remindersDeclined)
        assertFalse(done.cameraDeclined)
    }

    @Test
    fun `a cancelled camera request is not a refusal and leaves the asking to the scanner`() {
        val done = atCamera.cameraAnswered(null)

        assertEquals(OnboardingStep.Ready, done.step)
        assertEquals(OnboardingExit.Scanner, done.primaryExit)
    }

    @Test
    fun `refusing the camera offers adding by hand`() {
        val done = atCamera.cameraAnswered(false)

        assertEquals(OnboardingStep.Declined, done.step)
        assertTrue(done.cameraDeclined)
        assertFalse(done.remindersDeclined)
        assertEquals(OnboardingExit.AddByHand, done.primaryExit)
    }

    @Test
    fun `refusing reminders still carries on to the camera`() {
        val refused = OnboardingFlow().started(askBoth).notificationsAnswered(false, askBoth)

        assertEquals(OnboardingStep.Camera, refused.step)
        assertTrue(refused.remindersDeclined)
    }

    @Test
    fun `refusing only reminders keeps scanning as the main action`() {
        val done = OnboardingFlow().started(askBoth).notificationsAnswered(false, askBoth).cameraAnswered(true)

        assertEquals(OnboardingStep.Declined, done.step)
        assertTrue(done.remindersDeclined)
        assertFalse(done.cameraDeclined)
        assertEquals(OnboardingExit.Scanner, done.primaryExit)
    }

    @Test
    fun `refusing reminders with no camera question left ends on the summary`() {
        val done = OnboardingFlow().started(askBoth).notificationsAnswered(false, askNothing)

        assertEquals(OnboardingStep.Declined, done.step)
    }

    @Test
    fun `refusing both shows both rows and offers adding by hand`() {
        val done = OnboardingFlow().started(askBoth).notificationsAnswered(false, askBoth).cameraAnswered(false)

        assertTrue(done.remindersDeclined)
        assertTrue(done.cameraDeclined)
        assertEquals(OnboardingExit.AddByHand, done.primaryExit)
    }

    @Test
    fun `changing an answer after going back replaces the refusal`() {
        val refused = atCamera.cameraAnswered(false)

        val changed = refused.back(askCamera).cameraAnswered(true)

        assertEquals(OnboardingStep.Ready, changed.step)
        assertFalse(changed.cameraDeclined)
    }

    @Test
    fun `back retraces the screens that were shown`() {
        assertEquals(OnboardingStep.Welcome, OnboardingFlow(OnboardingStep.Notifications).back(askBoth).step)
        assertEquals(OnboardingStep.Notifications, OnboardingFlow(OnboardingStep.Camera).back(askBoth).step)
        assertEquals(OnboardingStep.Camera, OnboardingFlow(OnboardingStep.Ready).back(askBoth).step)
    }

    @Test
    fun `back skips screens with nothing left to ask`() {
        assertEquals(OnboardingStep.Welcome, OnboardingFlow(OnboardingStep.Camera).back(askCamera).step)
        assertEquals(OnboardingStep.Welcome, OnboardingFlow(OnboardingStep.Ready).back(askNothing).step)
    }

    @Test
    fun `the first screen is held until the pantry has loaded`() {
        assertTrue(holdsFirstScreen(undecided = true, needsOnboarding = false, routedToOnboarding = false))
    }

    @Test
    fun `the first screen is held until onboarding has taken over`() {
        assertTrue(holdsFirstScreen(undecided = false, needsOnboarding = true, routedToOnboarding = false))
        assertFalse(holdsFirstScreen(undecided = false, needsOnboarding = true, routedToOnboarding = true))
    }

    @Test
    fun `someone past onboarding is never held`() {
        assertFalse(holdsFirstScreen(undecided = false, needsOnboarding = false, routedToOnboarding = false))
    }
}
