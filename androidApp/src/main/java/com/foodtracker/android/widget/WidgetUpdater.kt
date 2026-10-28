package com.foodtracker.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Refreshes all home-screen widgets promptly on pantry/shopping-list changes, instead of
 *  waiting for the OS's own (30-minute-floor) `updatePeriodMillis` cadence, so every
 *  save/delete/decrement that reaches the database — from any screen — updates the widgets
 *  without each call site needing to know about them.
 *
 *  Observes [PantryStateHolder]'s already-derived state rather than the repository flows
 *  directly. Room flows are cold, so collecting them here as well meant every pantry write ran
 *  `SELECT *` over the whole table twice and mapped every row to a domain object twice, once
 *  for the UI and once for the widgets. The state holder is a singleton whose observers run on
 *  its own scope regardless of whether any UI is attached, so the widgets still update when the
 *  app isn't in the foreground. */
class WidgetUpdater(
    private val context: Context,
    private val stateHolder: PantryStateHolder,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    fun start() {
        coroutineScope.launch {
            // The expiry window is part of the key: with no live session, a day rollover or a
            // threshold change has to start one, or the widgets keep yesterday's filter until
            // the next pantry write.
            stateHolder.uiState
                .map { it.items to it.expiryWindow() }
                .distinctUntilChanged()
                .collectLatest {
                    // A single failed updateAll() must not throw out of collectLatest: an
                    // uncaught exception here would cancel this whole coroutine (SupervisorJob
                    // only isolates it from the other launch, it doesn't restart it), leaving the
                    // widget stuck on Glance's initial loading layout forever since no
                    // updatePeriodMillis exists to retry it.
                    refreshWidget { ExpiryWidget().updateAll(context) }
                    refreshWidget { OverviewWidget().updateAll(context) }
                }
        }
        coroutineScope.launch {
            stateHolder.uiState
                .map { it.shoppingList }
                .distinctUntilChanged()
                .collectLatest {
                    refreshWidget { ShoppingListWidget().updateAll(context) }
                    refreshWidget { OverviewWidget().updateAll(context) }
                }
        }
    }

    private suspend fun refreshWidget(update: suspend () -> Unit) {
        try {
            update()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
