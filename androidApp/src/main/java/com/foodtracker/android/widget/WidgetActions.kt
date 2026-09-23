package com.foodtracker.android.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.Action
import com.foodtracker.android.notification.NotificationHelper
import com.foodtracker.viewmodel.PantryStateHolder
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** The pantry item / shopping entry a widget row's button acts on. */
internal val widgetTargetIdKey = ActionParameters.Key<String>("widget_target_id")

/**
 * "Used one" on an expiry widget row.
 *
 * Goes through [PantryStateHolder.decrementQuantity] rather than touching the repository, so a
 * multi-unit item still just loses one unit and an essential item still re-stocks the shopping
 * list once its last one is gone — the widget must not re-implement either rule. Cancelling the
 * removed item's reminder mirrors what `MainActivity`'s own decrement handler does; without it
 * a consumed item still warns on its expiry date.
 *
 * The widgets refresh themselves: `WidgetUpdater` observes the state holder and calls
 * `updateAll` on every pantry change, from any source, including this one.
 */
class ConsumeItemAction : ActionCallback, KoinComponent {

    private val stateHolder: PantryStateHolder by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val itemId = parameters[widgetTargetIdKey] ?: return
        stateHolder.decrementQuantity(itemId) { removedItem ->
            NotificationHelper.cancelNotification(context, removedItem.id)
        }
    }
}

/**
 * "Bought it" on a shopping-list widget row: removes that entry, exactly as ticking it off in
 * the app does (see [PantryStateHolder.deleteShoppingListEntry]).
 *
 * There is no undo here — a widget has nowhere to put a snackbar — so this is deliberately the
 * plain delete rather than anything that also touches the pantry.
 */
class TickOffShoppingEntryAction : ActionCallback, KoinComponent {

    private val stateHolder: PantryStateHolder by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryId = parameters[widgetTargetIdKey] ?: return
        stateHolder.deleteShoppingListEntry(entryId)
    }
}

internal inline fun <reified T : ActionCallback> widgetRowAction(targetId: String): Action =
    actionRunCallback<T>(actionParametersOf(widgetTargetIdKey to targetId))
