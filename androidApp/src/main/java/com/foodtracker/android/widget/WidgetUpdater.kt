package com.foodtracker.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.ShoppingListRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Refreshes all home-screen widgets promptly on pantry/shopping-list changes, instead of
 *  waiting for the OS's own (30-minute-floor) `updatePeriodMillis` cadence. Collects the same
 *  repository flows `PantryStateHolder` observes, so every save/delete/decrement that reaches
 *  the database — from any screen — updates the widgets without each call site needing to
 *  know about them. */
class WidgetUpdater(
    private val context: Context,
    private val pantryRepository: PantryRepository,
    private val shoppingListRepository: ShoppingListRepository,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    fun start() {
        coroutineScope.launch {
            pantryRepository.getItemsFlow().collectLatest {
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
            shoppingListRepository.getEntriesFlow().collectLatest {
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
