package com.foodtracker.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foodtracker.android.MainActivity
import com.foodtracker.android.R
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
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(R.color.widget_background))
            .padding(12.dp)
    ) {
        Text(
            text = context.getString(R.string.widget_shopping_list_title),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ColorProvider(R.color.widget_on_background)),
            modifier = GlanceModifier.clickable(actionStartActivity(openShoppingListIntent(context)))
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (list.isEmpty) {
            Text(
                text = context.getString(R.string.widget_shopping_list_empty),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(R.color.widget_on_background_muted))
            )
        } else {
            val rows = list.rows.take(MAX_WIDGET_ROWS)
            // defaultWeight() gives the list a bounded height (rather than wrap-content), which
            // is what makes it actually scroll instead of just clipping past the widget's edge.
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                itemsIndexed(rows, itemId = { _, row -> row.key.hashCode().toLong() }) { index, row ->
                    when (row) {
                        is ShoppingRow.Header -> ShoppingSectionWidgetHeader(row)
                        is ShoppingRow.Item -> Column {
                            ShoppingListRow(row.entry)
                            if (rows.getOrNull(index + 1) is ShoppingRow.Item) {
                                WidgetRowDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A section's header on the widget: chevron, name and count, tapping it folds or unfolds the
 *  section (persisted, so the app follows). */
@Composable
private fun ShoppingSectionWidgetHeader(header: ShoppingRow.Header) {
    val context = LocalContext.current
    val section = header.section
    val label = if (section.isDefault) {
        context.getString(R.string.shopping_section_default)
    } else {
        categoryLabel(context.resources, section.name)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp)
            .clickable(toggleShoppingSectionAction(section.id, collapse = !section.collapsed))
    ) {
        Text(
            text = if (section.collapsed) "▸" else "▾",
            style = TextStyle(fontSize = 13.sp, color = ColorProvider(R.color.widget_on_background_muted))
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = label,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorProvider(R.color.widget_on_background_muted))
        )
        Text(
            text = header.itemCount.toString(),
            style = TextStyle(fontSize = 13.sp, color = ColorProvider(R.color.widget_on_background_muted))
        )
    }
}

/** Glance's LazyColumn is backed by a RemoteViews collection; well past any realistic list, but
 *  keeps a pathological one from producing a huge widget update. */
private const val MAX_WIDGET_ROWS = 60

/** One shopping-list row, shared with [OverviewWidget]'s dense summary. */
@Composable
internal fun ShoppingListRow(entry: ShoppingListEntry) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Spacer(modifier = GlanceModifier.height(6.dp))
            Box(modifier = GlanceModifier.width(6.dp).height(6.dp).background(ColorProvider(R.color.notification_accent))) {}
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = entry.productName,
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ColorProvider(R.color.widget_on_background))
        )
        if (entry.quantity > 1) {
            Text(
                text = context.getString(R.string.quantity_format, entry.quantity),
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ColorProvider(R.color.widget_on_background_muted))
            )
        }
    }
}
