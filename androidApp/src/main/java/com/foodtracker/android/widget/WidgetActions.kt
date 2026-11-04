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

/** The pantry item, shopping entry or shopping section a widget tap acts on. */
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
 * The tick on a shopping-list widget row: removes that entry, exactly as ticking it off in the
 * app does (see [PantryStateHolder.deleteShoppingListEntry]), and as the iOS widget's
 * `TickOffShoppingEntryIntent` does.
 *
 * There is no undo here, as a widget has nowhere to put a snackbar, so this is deliberately the
 * plain delete rather than anything that also touches the pantry.
 */
class TickOffShoppingEntryAction : ActionCallback, KoinComponent {

    private val stateHolder: PantryStateHolder by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryId = parameters[widgetTargetIdKey] ?: return
        stateHolder.deleteShoppingListEntry(entryId)
    }
}

/** The collapsed state a shopping-section header tap should switch its section to. Passed in
 *  rather than read back, since the widget may be running without the app's state loaded. */
internal val widgetCollapseKey = ActionParameters.Key<Boolean>("widget_collapse")

/**
 * Tapping a section header on the shopping-list widget folds or unfolds it. Persisted through
 * [PantryStateHolder.setShoppingSectionCollapsed] - the same flag the app's header toggles - so
 * the app and the widget always show the same sections open. `WidgetUpdater` refreshes the
 * widget when the sections change.
 */
class ToggleShoppingSectionAction : ActionCallback, KoinComponent {

    private val stateHolder: PantryStateHolder by inject()

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val sectionId = parameters[widgetTargetIdKey] ?: return
        val collapse = parameters[widgetCollapseKey] ?: return
        stateHolder.setShoppingSectionCollapsed(sectionId, collapse)
    }
}

internal fun toggleShoppingSectionAction(sectionId: String, collapse: Boolean): Action =
    actionRunCallback<ToggleShoppingSectionAction>(
        actionParametersOf(widgetTargetIdKey to sectionId, widgetCollapseKey to collapse)
    )

internal inline fun <reified T : ActionCallback> widgetRowAction(targetId: String): Action =
    actionRunCallback<T>(actionParametersOf(widgetTargetIdKey to targetId))
