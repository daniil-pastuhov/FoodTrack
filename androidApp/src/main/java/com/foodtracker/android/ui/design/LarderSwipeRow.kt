package com.foodtracker.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.model.ExpiryStatus
import kotlinx.coroutines.launch

/** How a swipe's reveal looks. */
enum class LarderSwipeStyle {
    Primary,

    /** The error colour, for an action that removes something. */
    Danger,

    /** A soft primary container, for actions that aren't the row's main story. */
    Quiet
}

/** One side of a [LarderSwipeRow]. [keepsRow] is for an action that can leave the row in the
 *  list, such as using one of several, so the row slides back afterwards. */
class LarderSwipeAction(
    val icon: LarderIcons,
    /** Null shows the icon alone, for a reveal the row's own control already explains. */
    val label: String?,
    val style: LarderSwipeStyle = LarderSwipeStyle.Primary,
    val keepsRow: Boolean = false,
    val onSwipe: () -> Unit
)

/**
 * Swipe to dismiss with a labelled, coloured reveal. [startAction] is revealed dragging right,
 * [endAction] dragging left, and either can be null. Every swipe needs a visible equivalent
 * elsewhere, because nothing may depend on a gesture.
 *
 * Inside a lazy list, key the item by its id.
 */
@Composable
fun LarderSwipeRow(
    startAction: LarderSwipeAction?,
    endAction: LarderSwipeAction?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    // A plain remember rather than rememberSwipeToDismissBoxState: that one is saveable, and its
    // saved value outlives the lazy item. An Undo that re-adds the same id would restore the row
    // already dismissed, showing only the reveal and unable to be swiped back.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionalThreshold
        )
    }
    val scope = rememberCoroutineScope()
    val currentStart by rememberUpdatedState(startAction)
    val currentEnd by rememberUpdatedState(endAction)
    // SwipeToDismissBox keys its dismissal effect on this lambda, so it has to keep one identity:
    // a new one per recomposition would fire the action again while the row sits dismissed.
    val onDismiss = remember {
        { value: SwipeToDismissBoxValue ->
            val action = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentStart
                SwipeToDismissBoxValue.EndToStart -> currentEnd
                SwipeToDismissBoxValue.Settled -> null
            }
            action?.onSwipe?.invoke()
            if (action?.keepsRow == true) scope.launch { state.reset() }
            Unit
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = enabled && startAction != null,
        enableDismissFromEndToStart = enabled && endAction != null,
        onDismiss = onDismiss,
        backgroundContent = {
            when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> startAction?.let { SwipeReveal(it, Alignment.CenterStart) }
                SwipeToDismissBoxValue.EndToStart -> endAction?.let { SwipeReveal(it, Alignment.CenterEnd) }
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        content = { content() }
    )
}

@Composable
internal fun SwipeReveal(action: LarderSwipeAction, alignment: Alignment) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when (action.style) {
        LarderSwipeStyle.Primary -> scheme.primary to scheme.onPrimary
        LarderSwipeStyle.Danger -> scheme.error to scheme.onError
        LarderSwipeStyle.Quiet -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = LarderSpace.s4),
        contentAlignment = alignment
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderIcon(action.icon, contentDescription = null, tint = foreground)
            action.label?.let {
                Text(text = it, style = MaterialTheme.typography.labelLarge, color = foreground)
            }
        }
    }
}

@PreviewLightDark
@Composable
internal fun LarderSwipeRowPreview() = LarderPreview {
    LarderGroup {
        Box {
            SwipeReveal(LarderSwipeAction(LarderIcons.ShoppingBasket, "To list") {}, Alignment.CenterStart)
        }
        LarderSwipeRow(
            startAction = LarderSwipeAction(LarderIcons.ShoppingBasket, "To list") {},
            endAction = LarderSwipeAction(LarderIcons.Trash2, "Delete", LarderSwipeStyle.Danger) {}
        ) {
            PantryRow(
                name = "Greek yoghurt",
                status = ExpiryStatus.EXPIRING_SOON,
                statusText = "Tomorrow",
                place = "Fridge · Dairy",
                quantity = 2,
                onClick = {},
                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
            )
        }
    }
}
