package com.foodtracker.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foodtracker.android.MainActivity
import com.foodtracker.android.R
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.repository.ShoppingListRepository
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
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
    private val settingsRepository: SettingsRepository by inject()
    private val shoppingListRepository: ShoppingListRepository by inject()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = DateUtils.today()
        val settings = settingsRepository.getSettings()
        val expiryItems = pantryRepository.getItemsFlow().first()
            .filter { it.daysRemaining(today) <= settings.expiringSoonDays }
            .sortedBy { it.daysRemaining(today) }
        val shoppingEntries = shoppingListRepository.getEntriesFlow().first()

        val rows = buildList {
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

        provideContent {
            OverviewWidgetContent(rows = rows, today = today)
        }
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
private fun OverviewWidgetContent(rows: List<OverviewRow>, today: LocalDate) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(R.color.widget_background))
            .padding(12.dp)
    ) {
        // defaultWeight() gives the list a bounded height (rather than wrap-content), which is
        // what makes it actually scroll instead of just clipping past the widget's edge.
        LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            itemsIndexed(rows, itemId = { _, row -> row.stableId() }) { index, row ->
                val nextIsSameSectionItem = rows.getOrNull(index + 1).let {
                    it is OverviewRow.ExpiryItem || it is OverviewRow.ShoppingItem
                }
                Column {
                    OverviewRowContent(row, today)
                    if (nextIsSameSectionItem && (row is OverviewRow.ExpiryItem || row is OverviewRow.ShoppingItem)) {
                        WidgetRowDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewRowContent(row: OverviewRow, today: LocalDate) {
    val context = LocalContext.current
    when (row) {
        is OverviewRow.Header -> {
            val openIntent = when (row.section) {
                OverviewSection.EXPIRY -> Intent(context, MainActivity::class.java)
                OverviewSection.SHOPPING_LIST -> openShoppingListIntent(context)
            }
            WidgetSectionHeader(
                title = row.title,
                modifier = GlanceModifier.clickable(actionStartActivity(openIntent))
            )
        }
        is OverviewRow.Empty -> {
            Text(
                text = row.message,
                modifier = GlanceModifier.padding(bottom = 4.dp),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(R.color.widget_on_background_muted))
            )
        }
        is OverviewRow.ExpiryItem -> ExpiryRow(row.item, today)
        is OverviewRow.ShoppingItem -> ShoppingListRow(row.entry)
    }
}
