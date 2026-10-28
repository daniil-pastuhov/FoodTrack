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
import androidx.glance.Button
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
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Home-screen widget showing the flat shopping list (name + quantity), newest first — the
 *  same order `ShoppingListDao.getAllFlow()` returns, without re-implementing the in-app
 *  screen's category grouping. */
class ShoppingListWidget : GlanceAppWidget(), KoinComponent {

    private val shoppingListRepository: ShoppingListRepository by inject()

    /** Collects inside [provideContent] rather than reading once up front; see
     *  [ExpiryWidget.provideGlance] for why a captured list goes stale. */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entriesFlow = shoppingListRepository.getEntriesFlow()
        val initialEntries = entriesFlow.first()
        provideContent {
            val entries by entriesFlow.collectAsState(initial = initialEntries)
            ShoppingListWidgetContent(entries = entries)
        }
    }
}

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
private fun ShoppingListWidgetContent(entries: List<ShoppingListEntry>) {
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
        if (entries.isEmpty()) {
            Text(
                text = context.getString(R.string.widget_shopping_list_empty),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(R.color.widget_on_background_muted))
            )
        } else {
            val visibleEntries = entries.take(12)
            // defaultWeight() gives the list a bounded height (rather than wrap-content), which
            // is what makes it actually scroll instead of just clipping past the widget's edge.
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                itemsIndexed(visibleEntries, itemId = { _, entry -> entry.id.hashCode().toLong() }) { index, entry ->
                    Column {
                        ShoppingListRow(entry, showAction = true)
                        if (index != visibleEntries.lastIndex) {
                            WidgetRowDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * One shopping-list row.
 *
 * [showAction] adds the "Bought" button. Off by default for the same reason as
 * [ExpiryRow]'s: [OverviewWidget] reuses this row in a dense summary where a button per row
 * would crowd out the content.
 */
@Composable
internal fun ShoppingListRow(entry: ShoppingListEntry, showAction: Boolean = false) {
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
        if (showAction) {
            Spacer(modifier = GlanceModifier.width(8.dp))
            Button(
                text = context.getString(R.string.widget_action_bought),
                onClick = widgetRowAction<TickOffShoppingEntryAction>(entry.id),
                modifier = GlanceModifier.height(32.dp)
            )
        }
    }
}
