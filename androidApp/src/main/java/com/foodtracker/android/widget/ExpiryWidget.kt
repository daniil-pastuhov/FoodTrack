package com.foodtracker.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.foodtracker.viewmodel.PantryStateHolder
import com.foodtracker.viewmodel.PantryUiState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Home-screen widget showing pantry items that are expired or expiring soon (per the user's
 *  `AppSettings.expiringSoonDays` threshold), soonest first. Runs in the app's own process, so
 *  it reads straight from the same repositories the dashboard uses via Koin — no IPC needed. */
class ExpiryWidget : GlanceAppWidget(), KoinComponent {

    private val pantryRepository: PantryRepository by inject()
    private val stateHolder: PantryStateHolder by inject()

    /**
     * Everything is collected *inside* [provideContent], not read once before it. Glance keeps
     * a widget's session (and this coroutine) alive for a while after it renders or handles a
     * tap, and `updateAll` on a live session only recomposes it — it does not re-run
     * [provideGlance]. A value captured up front therefore stayed stale: an item consumed in
     * the app shortly after touching the widget never disappeared from it. The one-shot reads
     * are kept as initial values so the first frame isn't empty.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val itemsFlow = pantryRepository.getItemsFlow()
        val initialItems = itemsFlow.first()
        // The holder's midnight timer stops while the device sleeps, and only MainActivity
        // otherwise corrects it; a widget update (including the 30-minute periodic one) may be
        // the first thing to run after midnight with the app in the background. Cheap when the
        // date hasn't moved, and when it has, WidgetUpdater's window key refreshes the widgets.
        stateHolder.refreshToday()
        val windowFlow = stateHolder.expiryWindowFlow()

        provideContent {
            val allItems by itemsFlow.collectAsState(initial = initialItems)
            val window by windowFlow.collectAsState(initial = stateHolder.uiState.value.expiryWindow())
            val items = remember(allItems, window) { expiringItems(allItems, window) }
            ExpiryWidgetContent(items = items, today = window.today)
        }
    }
}

class ExpiryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ExpiryWidget()
}

/** What decides which items count as expiring: today's date and the user's threshold. */
internal data class ExpiryWindow(val today: LocalDate, val expiringSoonDays: Int)

internal fun PantryUiState.expiryWindow(): ExpiryWindow = ExpiryWindow(today, settings.expiringSoonDays)

/**
 * The [ExpiryWindow] as it changes. Taken from the state holder rather than from
 * `DateUtils.today()` and `SettingsRepository`, because the holder already advances `today` at
 * midnight (and on time/zone changes) and publishes a settings change the moment it is made.
 */
internal fun PantryStateHolder.expiryWindowFlow(): Flow<ExpiryWindow> =
    uiState.map { it.expiryWindow() }.distinctUntilChanged()

/** Expired or expiring-within-the-threshold items, soonest first. */
internal fun expiringItems(items: List<PantryItem>, window: ExpiryWindow): List<PantryItem> =
    items
        .filter { it.daysRemaining(window.today) <= window.expiringSoonDays }
        .sortedBy { it.daysRemaining(window.today) }

/** Opens the dashboard, including when [MainActivity] is already running on another screen. */
internal fun openDashboardIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_OPEN_DASHBOARD, true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
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
            modifier = GlanceModifier.clickable(actionStartActivity(openDashboardIntent(context)))
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
