package com.foodtracker.android.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.foodtracker.android.ui.screens.rememberReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Material's predictive back for the screen being swiped away: it shrinks a little, shifts
// toward the side the swipe started from and rounds its corners, revealing the real screen Back
// goes to underneath. On release it fades out; a cancelled swipe settles back exactly.
private const val BACK_SCALE_FACTOR = 0.1f
private const val BACK_MAX_SHIFT_FRACTION = 0.05f
private val BACK_MAX_CORNER = 32.dp
private const val BACK_COMMIT_MILLIS = 150
private const val BACK_CANCEL_MILLIS = 200

/** A back swipe in progress: the screen it started on and the screen it would go to. */
private class BackSwipe<T>(val from: T, val to: T, val edge: Int)

/**
 * Shows [current] through [content], switching with [transitionSpec] when it changes, and gives
 * system Back to the root while [backTarget] is non-null. During a back swipe the outgoing screen
 * follows the gesture over [backTarget]'s real content; releasing calls [onBack], which must make
 * [backTarget] the current screen. Where [backTarget] is null the handler is off, so the system
 * plays its own animation (back to home) instead.
 *
 * With the system's "Remove animations" the swipe shows nothing until it is released. A Back key
 * press, which has no progress, simply calls [onBack].
 */
@Composable
fun <T : Any> PredictiveBackContent(
    current: T,
    backTarget: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentKey: (T) -> Any? = { it },
    /** Painted behind the outgoing screen while it is swiped, since screens draw on the root's
     *  background and would otherwise show the screen underneath through them. */
    containerColor: Color = MaterialTheme.colorScheme.background,
    transitionSpec: AnimatedContentTransitionScope<T>.() -> ContentTransform,
    content: @Composable AnimatedContentScope.(T) -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val transitionState = remember { SeekableTransitionState(current) }
    var swipe by remember { mutableStateOf<BackSwipe<T>?>(null) }
    val progress = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val latestOnBack by rememberUpdatedState(onBack)
    // How many swipes have been committed. A screen a swipe revealed keeps the z-index it was
    // given then, so each swipe puts the screen it reveals one step lower than the last did,
    // always below the screen being swiped away.
    var committedSwipes by remember { mutableIntStateOf(0) }

    // Ordinary navigation (taps, the Back key, intents) runs the screens' own transition.
    LaunchedEffect(current) {
        if (swipe == null) transitionState.animateTo(current)
    }

    PredictiveBackHandler(enabled = backTarget != null) { events ->
        val from = current
        val to = backTarget ?: return@PredictiveBackHandler
        try {
            events.collect { event ->
                if (reducedMotion) return@collect
                if (swipe == null) {
                    swipe = BackSwipe(from, to, event.swipeEdge)
                    transitionState.seekTo(0f, targetState = to)
                }
                progress.snapTo(event.progress)
            }
            if (swipe != null) alpha.animateTo(0f, tween(BACK_COMMIT_MILLIS))
            latestOnBack()
            if (swipe != null) {
                transitionState.snapTo(to)
                committedSwipes++
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (swipe != null) {
                    progress.animateTo(0f, tween(BACK_CANCEL_MILLIS))
                    transitionState.snapTo(from)
                }
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                swipe = null
                progress.snapTo(0f)
                alpha.snapTo(1f)
            }
        }
    }

    val transition = rememberTransition(transitionState, label = "screens")
    transition.AnimatedContent(
        modifier = modifier,
        transitionSpec = {
            if (swipe != null) {
                // The screen Back goes to sits still underneath; the outgoing one moves itself.
                ContentTransform(
                    EnterTransition.None,
                    ExitTransition.None,
                    targetContentZIndex = -(committedSwipes + 1f),
                    sizeTransform = null
                )
            } else {
                transitionSpec()
            }
        },
        contentKey = contentKey
    ) { screen ->
        val active = swipe
        val outgoing = active != null && contentKey(screen) == contentKey(active.from)
        val fromLeft = active?.edge != BackEventCompat.EDGE_RIGHT
        Box(
            if (outgoing) {
                Modifier.graphicsLayer {
                    val p = progress.value
                    val scale = 1f - BACK_SCALE_FACTOR * p
                    scaleX = scale
                    scaleY = scale
                    translationX = (if (fromLeft) 1f else -1f) * p * size.width * BACK_MAX_SHIFT_FRACTION
                    transformOrigin = TransformOrigin(pivotFractionX = if (fromLeft) 1f else 0f, pivotFractionY = 0.5f)
                    shape = RoundedCornerShape(BACK_MAX_CORNER * p)
                    clip = true
                    this.alpha = alpha.value
                }.background(containerColor)
            } else {
                Modifier
            }
        ) {
            content(screen)
        }
    }
}

/**
 * Where system Back goes from [route] when no screen handles it itself, or null where the root
 * leaves Back to the system: Home (the app closes, with the system's back-to-home animation),
 * onboarding (its own steps handle Back; on its first step the app closes) and the product
 * sheet, which is never the current route.
 */
fun larderBackTarget(
    route: LarderRoute,
    scanReturn: LarderRoute,
    recipeResultReturn: LarderRoute,
    staplesReturn: LarderRoute = LarderRoute.Settings
): LarderRoute? =
    when (route) {
        is LarderRoute.Scanner, is LarderRoute.AddItem -> scanReturn
        LarderRoute.Categories -> LarderRoute.Settings
        // Settings, or the Recipe form when its staples line opened it.
        LarderRoute.Staples -> staplesReturn
        is LarderRoute.RecipeResult -> recipeResultReturn
        is LarderRoute.MenuDish -> LarderRoute.RecipeResult(route.savedId)
        LarderRoute.SavedRecipes, LarderRoute.RecipeSettings -> LarderRoute.Recipe
        LarderRoute.TasteProfile -> LarderRoute.RecipeSettings
        LarderRoute.Settings, LarderRoute.Recipe, LarderRoute.Shopping -> LarderRoute.Home
        is LarderRoute.ProductDetail, LarderRoute.Home, LarderRoute.Onboarding -> null
    }
