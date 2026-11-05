package com.foodtracker.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.foodtracker.android.MainActivity
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.repository.ShoppingListRepository
import com.foodtracker.repository.ShoppingSectionRepository
import com.foodtracker.viewmodel.PantryDerivation
import com.foodtracker.viewmodel.ShoppingRow
import com.foodtracker.android.util.categoryLabel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Home-screen widget showing the shopping list the way the app does: in the user's sections,
 *  in their order, with folded sections showing only their header. The rows come from the same
 *  [PantryDerivation.buildShoppingRows] the app's screen uses, so the two can't drift. Tapping a
 *  header folds or unfolds that section for the app too (see [ToggleShoppingSectionAction]). */
class ShoppingListWidget : GlanceAppWidget(), KoinComponent {

    private val shoppingListRepository: ShoppingListRepository by inject()
    private val shoppingSectionRepository: ShoppingSectionRepository by inject()

    /** Collects inside [provideContent] rather than reading once up front; see
     *  [ExpiryWidget.provideGlance] for why a captured list goes stale. */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val rowsFlow = combine(
            shoppingListRepository.getEntriesFlow(),
            shoppingSectionRepository.getSectionsFlow()
        ) { entries, sections ->
            WidgetShoppingList(
                rows = PantryDerivation.buildShoppingRows(entries, sections),
                isEmpty = entries.isEmpty()
            )
        }
        val initial = rowsFlow.first()
        provideContent {
            val list by rowsFlow.collectAsState(initial = initial)
            ShoppingListWidgetContent(list)
        }
    }
}

private data class WidgetShoppingList(val rows: List<ShoppingRow>, val isEmpty: Boolean)

class ShoppingListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShoppingListWidget()
}

/** Opens straight to the Shopping List screen, including when [MainActivity] is already
 *  running — SINGLE_TOP + CLEAR_TOP route the intent through `onNewIntent` on the existing
 *  instance instead of leaving it wherever the user last left it. */
internal fun openShoppingListIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_OPEN_SHOPPING_LIST, true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

@Composable
private fun ShoppingListWidgetContent(list: WidgetShoppingList) {
    val context = LocalContext.current
    // Every row ends in a tick, whose target runs out to the card's edge; the rest stays inset.
    val inset = LarderSpace.s3
    WidgetSurface(endPadding = 0.dp) {
        Box(modifier = GlanceModifier.padding(end = inset)) {
            WidgetTitle(
                text = context.getString(R.string.widget_shopping_list_title),
                onClick = actionStartActivity(openShoppingListIntent(context))
            )
        }
        if (list.isEmpty) {
            WidgetEmptyText(context.getString(R.string.widget_shopping_list_empty), GlanceModifier.padding(end = inset))
        } else {
            val rows = list.rows.take(MAX_WIDGET_ROWS)
            // defaultWeight() gives the list a bounded height (rather than wrap-content), which
            // is what makes it actually scroll instead of just clipping past the widget's edge.
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                itemsIndexed(rows, itemId = { _, row -> row.key.hashCode().toLong() }) { index, row ->
                    when (row) {
                        is ShoppingRow.Header -> ShoppingSectionWidgetHeader(row, inset)
                        is ShoppingRow.Item -> Column {
                            ShoppingListRow(row.entry, showAction = true)
                            if (rows.getOrNull(index + 1) is ShoppingRow.Item) {
                                WidgetRowDivider(endPadding = inset)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A section's header on the widget: tapping it folds or unfolds the section (persisted, so the
 *  app follows). */
@Composable
private fun ShoppingSectionWidgetHeader(header: ShoppingRow.Header, endPadding: Dp) {
    val context = LocalContext.current
    val section = header.section
    WidgetFoldHeader(
        label = if (section.isDefault) {
            context.getString(R.string.shopping_section_default)
        } else {
            categoryLabel(context.resources, section.name)
        },
        count = header.itemCount,
        collapsed = section.collapsed,
        onClick = toggleShoppingSectionAction(section.id, collapse = !section.collapsed),
        endPadding = endPadding
    )
}

/** Glance's LazyColumn is backed by a RemoteViews collection; well past any realistic list, but
 *  keeps a pathological one from producing a huge widget update. */
private const val MAX_WIDGET_ROWS = 60

/**
 * One shopping-list row.
 *
 * [showAction] adds the tick that marks the entry bought. Off by default for the same reason as
 * [ExpiryRow]'s: [OverviewWidget] reuses this row in a dense summary, where a 48dp control per row
 * would crowd out the content. The iOS overview widget has no tick either.
 */
@Composable
internal fun ShoppingListRow(entry: ShoppingListEntry, showAction: Boolean = false) {
    val context = LocalContext.current
    Row(
        // The tick's touch target already gives the row its height.
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = if (showAction) 0.dp else LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WidgetItemName(entry.productName, GlanceModifier.defaultWeight())
        if (entry.quantity > 1) {
            Spacer(modifier = GlanceModifier.width(LarderSpace.s2))
            WidgetQuantity(context.getString(R.string.quantity_format, entry.quantity))
        }
        if (showAction) {
            WidgetTick(
                description = context.getString(R.string.widget_action_mark_bought, entry.productName),
                onClick = widgetRowAction<TickOffShoppingEntryAction>(entry.id)
            )
        }
    }
}
