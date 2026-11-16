package com.foodtracker.android.ui.screens

import com.foodtracker.model.Staple
import com.foodtracker.staples.MAX_STAPLES
import com.foodtracker.staples.StaplePresets

internal enum class OnboardingStep { Welcome, Notifications, Camera, Staples, Ready, Declined }

/** Where the first-run flow hands over to the app. */
enum class OnboardingExit { Scanner, AddByHand, Home }

/** Which rationale screens still have something to ask. Read fresh each time, because an answer
 *  can change it. Nothing to ask means the screen is skipped: a phone on Android 12 or below has
 *  no notification permission, and a flow restarted after being killed may already have both. */
internal data class OnboardingAsks(val notifications: Boolean, val camera: Boolean)

/**
 * Whether the app should draw nothing yet. On a first launch that is until it knows whether
 * onboarding is due and, if so, until it has taken over: not empty Home for a frame, and not a
 * scanner from a launcher shortcut, whose camera prompt would land over onboarding.
 */
internal fun holdsFirstScreen(undecided: Boolean, needsOnboarding: Boolean, routedToOnboarding: Boolean): Boolean =
    undecided || (needsOnboarding && !routedToOnboarding)

/**
 * Where the first-run flow stands. Every answer moves it forward; nothing depends on the answer
 * except what the last screen says.
 *
 * An answer is true for allowed, false for refused on the system prompt, and null when the system
 * never put the question: Not now, or a request it cancelled. Only a refusal counts as declined.
 */
internal data class OnboardingFlow(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val remindersDeclined: Boolean = false,
    val cameraDeclined: Boolean = false
) {
    private val ending: OnboardingStep
        get() = if (remindersDeclined || cameraDeclined) OnboardingStep.Declined else OnboardingStep.Ready

    private fun afterNotifications(asks: OnboardingAsks): OnboardingStep =
        if (asks.camera) OnboardingStep.Camera else OnboardingStep.Staples

    fun started(asks: OnboardingAsks): OnboardingFlow = copy(
        step = if (asks.notifications) OnboardingStep.Notifications else afterNotifications(asks)
    )

    fun notificationsAnswered(answer: Boolean?, asks: OnboardingAsks): OnboardingFlow =
        copy(remindersDeclined = answer == false).let { it.copy(step = it.afterNotifications(asks)) }

    fun cameraAnswered(answer: Boolean?): OnboardingFlow =
        copy(cameraDeclined = answer == false, step = OnboardingStep.Staples)

    /** The staples step was left forwards, with Continue or Skip: Ready, or Declined after a refusal. */
    fun staplesDone(): OnboardingFlow = copy(step = ending)

    /** The screen before this one, skipping those with nothing left to ask. */
    fun back(asks: OnboardingAsks): OnboardingFlow = copy(
        step = when (step) {
            OnboardingStep.Welcome, OnboardingStep.Notifications -> OnboardingStep.Welcome
            OnboardingStep.Camera -> if (asks.notifications) OnboardingStep.Notifications else OnboardingStep.Welcome
            OnboardingStep.Staples -> when {
                asks.camera -> OnboardingStep.Camera
                asks.notifications -> OnboardingStep.Notifications
                else -> OnboardingStep.Welcome
            }
            OnboardingStep.Ready, OnboardingStep.Declined -> OnboardingStep.Staples
        }
    )

    /** Scanning leads the way unless the camera was refused, when the scanner would have nothing
     *  to show. Reminders being off doesn't get in the way of a scan. */
    val primaryExit: OnboardingExit
        get() = if (cameraDeclined) OnboardingExit.AddByHand else OnboardingExit.Scanner
}

/** The presets ticked among [staples]: the staples step's first ticks. */
internal fun tickedPresets(staples: List<Staple>): Set<String> =
    staples.mapNotNullTo(LinkedHashSet()) { staple -> staple.presetId?.takeIf { StaplePresets.byId(it) != null } }

/** What Continue on the staples step does: the presets to add and the staples (by id) to remove,
 *  each in the presets' table order. Nothing in either when the ticks are what was there. */
data class OnboardingStapleChanges(val add: List<String>, val remove: List<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()
}

internal fun onboardingStapleChanges(staples: List<Staple>, ticked: Set<String>): OnboardingStapleChanges {
    val had = tickedPresets(staples)
    val order = StaplePresets.all.map { it.id }
    return OnboardingStapleChanges(
        add = order.filter { it in ticked && it !in had },
        remove = order.filter { it in had && it !in ticked }.flatMap { id -> staples.filter { it.presetId == id }.map { it.id } }
    )
}

/** Whether the staples step is at the cap with these ticks: the person's own entries (and presets
 *  this version doesn't know) plus the ticked presets. Unticked rows are then off. */
internal fun onboardingStaplesFull(staples: List<Staple>, ticked: Set<String>): Boolean =
    staples.count { staple -> staple.presetId?.let { StaplePresets.byId(it) } == null } + ticked.size >= MAX_STAPLES
