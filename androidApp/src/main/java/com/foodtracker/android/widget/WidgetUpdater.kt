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
                ExpiryWidget().updateAll(context)
                OverviewWidget().updateAll(context)
            }
        }
        coroutineScope.launch {
            shoppingListRepository.getEntriesFlow().collectLatest {
                ShoppingListWidget().updateAll(context)
                OverviewWidget().updateAll(context)
            }
        }
    }
}
