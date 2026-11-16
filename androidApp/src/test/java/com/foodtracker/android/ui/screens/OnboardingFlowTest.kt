package com.foodtracker.android.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.foodtracker.model.Staple
import com.foodtracker.staples.StaplePresets

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
    fun `nothing left to ask goes straight to staples, then ready`() {
        val started = OnboardingFlow().started(askNothing)
        assertEquals(OnboardingStep.Staples, started.step)
        assertEquals(OnboardingStep.Ready, started.staplesDone().step)
    }

    @Test
    fun `allowing both ends on ready`() {
        val staples = atCamera.cameraAnswered(true)
        assertEquals(OnboardingStep.Staples, staples.step)
        val done = staples.staplesDone()

        assertEquals(OnboardingStep.Ready, done.step)
        assertEquals(OnboardingExit.Scanner, done.primaryExit)
    }

    @Test
    fun `not now is not a refusal, so it ends on ready`() {
        val done = OnboardingFlow().started(askBoth).notificationsAnswered(null, askBoth).cameraAnswered(null).staplesDone()

        assertEquals(OnboardingStep.Ready, done.step)
        assertFalse(done.remindersDeclined)
        assertFalse(done.cameraDeclined)
    }

    @Test
    fun `a cancelled camera request is not a refusal and leaves the asking to the scanner`() {
        val done = atCamera.cameraAnswered(null).staplesDone()

        assertEquals(OnboardingStep.Ready, done.step)
        assertEquals(OnboardingExit.Scanner, done.primaryExit)
    }

    @Test
    fun `refusing the camera offers adding by hand`() {
        val done = atCamera.cameraAnswered(false).staplesDone()

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
        val done = OnboardingFlow().started(askBoth).notificationsAnswered(false, askBoth).cameraAnswered(true).staplesDone()

        assertEquals(OnboardingStep.Declined, done.step)
        assertTrue(done.remindersDeclined)
        assertFalse(done.cameraDeclined)
        assertEquals(OnboardingExit.Scanner, done.primaryExit)
    }

    @Test
    fun `refusing reminders with no camera question left ends on the summary`() {
        val staples = OnboardingFlow().started(askBoth).notificationsAnswered(false, askNothing)
        assertEquals(OnboardingStep.Staples, staples.step)

        assertEquals(OnboardingStep.Declined, staples.staplesDone().step)
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
        val refused = atCamera.cameraAnswered(false).staplesDone()

        val changed = refused.back(askCamera).back(askCamera).cameraAnswered(true).staplesDone()

        assertEquals(OnboardingStep.Ready, changed.step)
        assertFalse(changed.cameraDeclined)
    }

    @Test
    fun `back retraces the screens that were shown`() {
        assertEquals(OnboardingStep.Welcome, OnboardingFlow(OnboardingStep.Notifications).back(askBoth).step)
        assertEquals(OnboardingStep.Notifications, OnboardingFlow(OnboardingStep.Camera).back(askBoth).step)
        assertEquals(OnboardingStep.Camera, OnboardingFlow(OnboardingStep.Staples).back(askBoth).step)
        assertEquals(OnboardingStep.Staples, OnboardingFlow(OnboardingStep.Ready).back(askBoth).step)
        assertEquals(OnboardingStep.Staples, OnboardingFlow(OnboardingStep.Declined).back(askNothing).step)
    }

    @Test
    fun `back skips screens with nothing left to ask`() {
        assertEquals(OnboardingStep.Welcome, OnboardingFlow(OnboardingStep.Camera).back(askCamera).step)
        assertEquals(OnboardingStep.Welcome, OnboardingFlow(OnboardingStep.Staples).back(askNothing).step)
        assertEquals(OnboardingStep.Notifications, OnboardingFlow(OnboardingStep.Staples).back(OnboardingAsks(notifications = true, camera = false)).step)
    }

    @Test
    fun `the staples step goes on to whichever ending the answers chose`() {
        val declined = OnboardingFlow().started(askBoth).notificationsAnswered(false, askBoth).cameraAnswered(true)
        assertEquals(OnboardingStep.Staples, declined.step)
        assertEquals(OnboardingStep.Declined, declined.staplesDone().step)
        assertEquals(OnboardingExit.Scanner, declined.staplesDone().primaryExit)
    }

    private fun staple(id: String, name: String, presetId: String?) = Staple(id, name, presetId, 0)

    @Test
    fun `the staples step starts from the presets already there`() {
        val staples = listOf(staple("a", "Salt", "salt"), staple("b", "Honey", null), staple("c", "Truffle oil", "truffle_oil"))
        assertEquals(setOf("salt"), tickedPresets(staples))
        assertEquals(emptySet<String>(), tickedPresets(emptyList()))
    }

    @Test
    fun `continue adds the newly ticked presets and removes the unticked ones, in table order`() {
        val staples = listOf(staple("v", "Vinegar", "vinegar"), staple("s", "Salt", "salt"), staple("h", "Honey", null))
        val changes = onboardingStapleChanges(staples, setOf("eggs", "salt", "black_pepper"))
        assertEquals(listOf("black_pepper", "eggs"), changes.add)
        assertEquals("own entries are never touched", listOf("v"), changes.remove)
    }

    @Test
    fun `continue with nothing changed changes nothing`() {
        val staples = listOf(staple("s", "Salt", "salt"))
        assertTrue(onboardingStapleChanges(staples, setOf("salt")).isEmpty)
        assertTrue(onboardingStapleChanges(emptyList(), emptySet()).isEmpty)
    }

    @Test
    fun `the cap counts the own entries and the ticks`() {
        val own = (1..10).map { staple("o$it", "Own $it", null) }
        assertFalse(onboardingStaplesFull(own, (StaplePresets.all.take(9)).map { it.id }.toSet()))
        assertTrue(onboardingStaplesFull(own, (StaplePresets.all.take(10)).map { it.id }.toSet()))
        assertFalse("14 presets alone never reach 20", onboardingStaplesFull(emptyList(), StaplePresets.all.map { it.id }.toSet()))
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
