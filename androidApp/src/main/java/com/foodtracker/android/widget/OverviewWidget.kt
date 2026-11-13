package com.foodtracker.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.ShoppingListRepository
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val MAX_ROWS_PER_SECTION = 8

private enum class OverviewSection { EXPIRY, SHOPPING_LIST }

/** One row of the combined widget's single scrollable list: a section header, one item from
 *  either source list, or the "nothing here" message for an empty section. Modeling both
 *  sections as one flat list (rather than two separate `LazyColumn`s) is what lets the whole
 *  thing scroll as a single list. */
private sealed class OverviewRow {
    data class Header(val section: OverviewSection, val title: String) : OverviewRow()
    data class Empty(val message: String) : OverviewRow()
    data class ExpiryItem(val item: PantryItem) : OverviewRow()
    data class ShoppingItem(val entry: ShoppingListEntry) : OverviewRow()
}

/** Home-screen widget combining [ExpiryWidget]'s expired/expiring-soon list and
 *  [ShoppingListWidget]'s shopping list into one scrollable widget, for a single-glance
 *  overview without placing two separate widgets. Reuses each widget's own row/query logic
 *  rather than re-deriving it. */
class OverviewWidget : GlanceAppWidget(), KoinComponent {

    private val pantryRepository: PantryRepository by inject()
    private val shoppingListRepository: ShoppingListRepository by inject()
    private val stateHolder: PantryStateHolder by inject()

    /** Collects inside [provideContent] rather than reading once up front; see
     *  [ExpiryWidget.provideGlance] for why a captured value goes stale. */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val itemsFlow = pantryRepository.getItemsFlow()
        val entriesFlow = shoppingListRepository.getEntriesFlow()
        val initialItems = itemsFlow.first()
        val initialEntries = entriesFlow.first()
        stateHolder.refreshToday() // see ExpiryWidget.provideGlance
        val windowFlow = stateHolder.expiryWindowFlow()

        provideContent {
            val allItems by itemsFlow.collectAsState(initial = initialItems)
            val shoppingEntries by entriesFlow.collectAsState(initial = initialEntries)
            val window by windowFlow.collectAsState(initial = stateHolder.uiState.value.expiryWindow())
            val rows = remember(allItems, shoppingEntries, window) {
                buildOverviewRows(
                    context = context,
                    expiryItems = expiringItems(allItems, window),
                    shoppingEntries = shoppingEntries
                )
            }
            OverviewWidgetContent(rows = rows, window = window)
        }
    }
}

private fun buildOverviewRows(
    context: Context,
    expiryItems: List<PantryItem>,
    shoppingEntries: List<ShoppingListEntry>
): List<OverviewRow> = buildList {
    add(OverviewRow.Header(OverviewSection.EXPIRY, context.getString(R.string.widget_expiry_title)))
    if (expiryItems.isEmpty()) {
        add(OverviewRow.Empty(context.getString(R.string.widget_expiry_empty)))
    } else {
        expiryItems.take(MAX_ROWS_PER_SECTION).forEach { add(OverviewRow.ExpiryItem(it)) }
    }

    add(OverviewRow.Header(OverviewSection.SHOPPING_LIST, context.getString(R.string.widget_shopping_list_title)))
    if (shoppingEntries.isEmpty()) {
        add(OverviewRow.Empty(context.getString(R.string.widget_shopping_list_empty)))
    } else {
        shoppingEntries.take(MAX_ROWS_PER_SECTION).forEach { add(OverviewRow.ShoppingItem(it)) }
    }
}

class OverviewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OverviewWidget()
}

/** Stable-ish id per row for Glance's list diffing: content ids are tagged by section so an
 *  expiry item and a shopping entry that happen to hash the same never collide. */
private fun OverviewRow.stableId(): Long = when (this) {
    is OverviewRow.Header -> -(section.ordinal.toLong()) - 1
    is OverviewRow.Empty -> -(message.hashCode().toLong() and 0x3FFFFFFF) - 10
    is OverviewRow.ExpiryItem -> (item.id.hashCode().toLong() shl 1)
    is OverviewRow.ShoppingItem -> (entry.id.hashCode().toLong() shl 1) or 1
}

@Composable
private fun OverviewWidgetContent(rows: List<OverviewRow>, window: ExpiryWindow) {
    WidgetSurface {
        // defaultWeight() gives the list a bounded height (rather than wrap-content), which is
        // what makes it actually scroll instead of just clipping past the widget's edge.
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            itemsIndexed(rows, itemId = { _, row -> row.stableId() }) { index, row ->
                val nextIsSameSectionItem = rows.getOrNull(index + 1).let {
                    it is OverviewRow.ExpiryItem || it is OverviewRow.ShoppingItem
                }
                Column {
                    OverviewRowContent(row, window)
                    if (nextIsSameSectionItem && (row is OverviewRow.ExpiryItem || row is OverviewRow.ShoppingItem)) {
                        WidgetRowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewRowContent(row: OverviewRow, window: ExpiryWindow) {
    val context = LocalContext.current
    when (row) {
        is OverviewRow.Header -> {
            val openIntent = when (row.section) {
                OverviewSection.EXPIRY -> openDashboardIntent(context)
                OverviewSection.SHOPPING_LIST -> openShoppingListIntent(context)
            }
            WidgetSectionHeader(
                title = row.title,
                modifier = GlanceModifier.clickable(actionStartActivity(openIntent))
            )
        }
        is OverviewRow.Empty -> WidgetEmptyText(row.message, GlanceModifier.padding(bottom = LarderSpace.s1))
        is OverviewRow.ExpiryItem -> ExpiryRow(row.item, window)
        is OverviewRow.ShoppingItem -> ShoppingListRow(row.entry)
    }
}
