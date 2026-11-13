package com.foodtracker.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import com.foodtracker.android.MainActivity
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderSpace
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

    /** Two layouts: a tick on each row when narrow, the "Used one" button when wide. */
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 110.dp), DpSize(USED_BUTTON_MIN_WIDTH, 110.dp)))

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
            ExpiryWidgetContent(items = items, window = window)
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

/** What a row of the expiring list offers. */
internal enum class ExpiryRowAction { NONE, TICK, BUTTON }

/** Below this width the "Used one" button leaves no room for a name, so the row has a tick. */
private val USED_BUTTON_MIN_WIDTH = 250.dp

internal fun expiryRowAction(widgetWidth: Dp): ExpiryRowAction =
    if (widgetWidth >= USED_BUTTON_MIN_WIDTH) ExpiryRowAction.BUTTON else ExpiryRowAction.TICK

@Composable
private fun ExpiryWidgetContent(items: List<PantryItem>, window: ExpiryWindow) {
    val context = LocalContext.current
    val action = expiryRowAction(LocalSize.current.width)
    val tickInset = if (action == ExpiryRowAction.TICK) LarderSpace.s3 else 0.dp
    WidgetSurface(endPadding = LarderSpace.s3 - tickInset) {
        Box(modifier = GlanceModifier.padding(end = tickInset)) {
            WidgetTitle(
                text = context.getString(R.string.widget_expiry_title),
                onClick = actionStartActivity(openDashboardIntent(context))
            )
        }
        if (items.isEmpty()) {
            WidgetEmptyText(context.getString(R.string.widget_expiry_empty), GlanceModifier.padding(end = tickInset))
        } else {
            val visibleItems = items.take(12)
            // defaultWeight() gives the list a bounded height (rather than wrap-content), which
            // is what makes it actually scroll instead of just clipping past the widget's edge.
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                itemsIndexed(visibleItems, itemId = { _, item -> item.id.hashCode().toLong() }) { index, item ->
                    Column {
                        ExpiryRow(item, window, action)
                        if (index != visibleItems.lastIndex) {
                            WidgetRowDivider(endPadding = tickInset)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One expiry row: name, status chip and [action].
 *
 * [OverviewWidget] renders this same row with no action, inside a dense two-section summary where
 * a control per row crowds out the content the summary exists to show.
 */
@Composable
internal fun ExpiryRow(item: PantryItem, window: ExpiryWindow, action: ExpiryRowAction = ExpiryRowAction.NONE) {
    val context = LocalContext.current
    val status = item.expiryStatus(window.today, window.expiringSoonDays)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = LarderSpace.s1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            WidgetItemName(item.name)
            Spacer(modifier = GlanceModifier.height(2.dp))
            WidgetStatusChip(status, widgetStatusLabel(context, item, status, window))
        }
        val description = context.getString(R.string.widget_action_used_item, item.name)
        when (action) {
            ExpiryRowAction.NONE -> Unit
            ExpiryRowAction.TICK -> WidgetTick(description, widgetRowAction<ConsumeItemAction>(item.id))
            ExpiryRowAction.BUTTON -> {
                Spacer(modifier = GlanceModifier.width(LarderSpace.s2))
                WidgetUsedButton(
                    label = context.getString(R.string.widget_action_used),
                    description = description,
                    onClick = widgetRowAction<ConsumeItemAction>(item.id)
                )
            }
        }
    }
}
