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
import com.foodtracker.model.PantryItem
import com.foodtracker.repository.PantryRepository
import com.foodtracker.repository.SettingsRepository
import com.foodtracker.util.DateUtils
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Home-screen widget showing pantry items that are expired or expiring soon (per the user's
 *  `AppSettings.expiringSoonDays` threshold), soonest first. Runs in the app's own process, so
 *  it reads straight from the same repositories the dashboard uses via Koin — no IPC needed. */
class ExpiryWidget : GlanceAppWidget(), KoinComponent {

    private val pantryRepository: PantryRepository by inject()
    private val settingsRepository: SettingsRepository by inject()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = DateUtils.today()
        val settings = settingsRepository.getSettings()
        val items = pantryRepository.getItemsFlow().first()
            .filter { it.daysRemaining(today) <= settings.expiringSoonDays }
            .sortedBy { it.daysRemaining(today) }

        provideContent {
            ExpiryWidgetContent(items = items, today = today)
        }
    }
}

class ExpiryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpiryWidget()
}

@Composable
private fun ExpiryWidgetContent(items: List<PantryItem>, today: LocalDate) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(R.color.widget_background))
            .padding(12.dp)
    ) {
        Text(
            text = context.getString(R.string.widget_expiry_title),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ColorProvider(R.color.widget_on_background)),
            modifier = GlanceModifier.clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
        )
        Spacer(modifier = GlanceModifier.height(8.dp))
        if (items.isEmpty()) {
            Text(
                text = context.getString(R.string.widget_expiry_empty),
                style = TextStyle(fontSize = 13.sp, color = ColorProvider(R.color.widget_on_background_muted))
            )
        } else {
            val visibleItems = items.take(12)
            // defaultWeight() gives the list a bounded height (rather than wrap-content), which
            // is what makes it actually scroll instead of just clipping past the widget's edge.
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                itemsIndexed(visibleItems, itemId = { _, item -> item.id.hashCode().toLong() }) { index, item ->
                    Column {
                        ExpiryRow(item, today, showAction = true)
                        if (index != visibleItems.lastIndex) {
                            WidgetRowDivider()
                        }
                    }
                }
            }
        }
    }
}

/**
 * One expiry row.
 *
 * [showAction] adds the "Used one" button. Off by default because [OverviewWidget] renders this
 * same row inside a dense two-section summary, where a button per row crowds out the content
 * the summary exists to show; only the dedicated expiry widget opts in.
 */
@Composable
internal fun ExpiryRow(item: PantryItem, today: LocalDate, showAction: Boolean = false) {
    val context = LocalContext.current
    val statusColor = if (item.daysRemaining(today) < 0) {
        ColorProvider(R.color.widget_status_expired)
    } else {
        ColorProvider(R.color.widget_status_expiring)
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Spacer(modifier = GlanceModifier.height(5.dp))
            Box(modifier = GlanceModifier.width(8.dp).height(8.dp).background(statusColor)) {}
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = item.name,
                maxLines = 1,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ColorProvider(R.color.widget_on_background))
            )
            Text(
                text = expiryLabel(context, item, today),
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = statusColor)
            )
        }
        if (showAction) {
            Spacer(modifier = GlanceModifier.width(8.dp))
            Button(
                text = context.getString(R.string.widget_action_used),
                onClick = widgetRowAction<ConsumeItemAction>(item.id),
                modifier = GlanceModifier.height(32.dp)
            )
        }
    }
}
