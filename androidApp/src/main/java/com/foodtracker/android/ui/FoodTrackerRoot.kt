package com.foodtracker.android.ui

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.location.StoreGeofenceManager
import com.foodtracker.android.camera.CameraScanMode
import com.foodtracker.android.ui.components.ProductDetailActions
import com.foodtracker.android.ui.components.ProductDetailPane
import com.foodtracker.android.ui.components.ProductDetailRoute
import com.foodtracker.android.ui.design.FloatingNav
import com.foodtracker.android.ui.design.LarderSnackbar
import com.foodtracker.android.ui.design.LarderTab
import com.foodtracker.android.ui.design.LocalShellChrome
import com.foodtracker.android.ui.design.ScanFab
import com.foodtracker.android.ui.design.ShellChrome
import com.foodtracker.android.ui.design.isExpanded
import com.foodtracker.android.ui.design.shellClearance
import com.foodtracker.android.ui.navigation.AddItemMode
import com.foodtracker.android.ui.navigation.ItemPrefill
import com.foodtracker.android.ui.navigation.LarderNavigator
import com.foodtracker.android.ui.navigation.LarderRoute
import com.foodtracker.android.ui.navigation.LarderSnackbars
import com.foodtracker.android.ui.navigation.LocalLarderNavigator
import com.foodtracker.android.ui.navigation.LocalLarderSnackbars
import com.foodtracker.android.ui.navigation.route
import com.foodtracker.android.ui.navigation.tab
import com.foodtracker.android.ui.screens.AddEditItemScreen
import com.foodtracker.android.ui.screens.CalendarScreen
import com.foodtracker.android.ui.screens.CategorySettingsScreen
import com.foodtracker.android.ui.screens.HomeListPaneWidth
import com.foodtracker.android.ui.screens.OnboardingExit
import com.foodtracker.android.ui.screens.OnboardingScreen
import com.foodtracker.android.ui.screens.holdsFirstScreen
import com.foodtracker.android.ui.screens.PantryDashboardScreen
import com.foodtracker.android.ui.screens.ScannerScreen
import com.foodtracker.android.ui.screens.SettingsScreen
import com.foodtracker.android.ui.screens.ShoppingListMatchDialog
import com.foodtracker.android.ui.screens.ShoppingListScreen
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.backup.BackupReadResult
import com.foodtracker.backup.BackupSerializer
import com.foodtracker.backup.RestoreMode
import com.foodtracker.match.ShoppingListMatchResult
import com.foodtracker.match.ShoppingListMatcher
import com.foodtracker.model.AppTheme
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShelfLifeSuggestion
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.util.DateUtils
import com.foodtracker.viewmodel.HomeConfirmation
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import com.foodtracker.android.ui.components.BackgroundLocationDisclosureDialog

/** Sends a fresh install to [LarderRoute.Onboarding]. The same switch as iOS's
 *  `showOnboardingOnFirstRun`. */
private const val SHOW_ONBOARDING_ON_FIRST_RUN = true

/** Holds a generic shopping-list scan's resolved result plus its ranked candidate entries
 *  while the "Did you mean?" dialog is up, so a tapped suggestion (or "None of these") can
 *  proceed to Add/Edit with the same data the scanner already resolved. */
private data class PendingShoppingListMatch(
    val barcode: String?,
    val name: String?,
    val brand: String?,
    val imageUrl: String?,
    val expiryDate: LocalDate?,
    val category: String?,
    val suggestedExpiry: ShelfLifeSuggestion?,
    val candidates: List<ShoppingListEntry>
)

private fun confirmationMessage(context: Context, confirmation: HomeConfirmation): String =
    when (confirmation) {
        is HomeConfirmation.Saved -> context.getString(
            R.string.item_saved_snackbar,
            confirmation.name,
            storageLocationLabel(context.resources, confirmation.location)
        )
        is HomeConfirmation.BatchAdded -> context.resources.getQuantityString(
            R.plurals.batch_added_snackbar,
            confirmation.count,
            confirmation.count
        )
        is HomeConfirmation.OneMoreAdded -> context.getString(
            R.string.duplicate_added_snackbar,
            confirmation.name,
            confirmation.quantity
        )
    }

// Material predictive-back visual treatment for the screen being swiped away: it shrinks
// slightly, shifts toward the swipe edge, and gains rounded corners as the gesture progresses.
private const val PREDICTIVE_BACK_SCALE_FACTOR = 0.1f
private const val PREDICTIVE_BACK_MAX_TRANSLATION_FRACTION = 0.05f
private val PREDICTIVE_BACK_MAX_CORNER_RADIUS_DP = 32.dp

/**
 * The app's whole Compose UI: theme, screen switching, predictive back, the shared bottom-nav
 * chrome, and the snackbar host every screen reports through.
 *
 * [currentScreenState] is owned by MainActivity rather than `remember`ed here so an incoming
 * intent (widget tap, launcher shortcut) can navigate even while the activity is running.
 * [onAddCurrentLocationAsStore] stays on the activity because it drives activity-registered
 * permission launchers.
 */
@Composable
fun FoodTrackerRoot(
    stateHolder: PantryStateHolder,
    currentScreenState: MutableState<LarderRoute>,
    onAddCurrentLocationAsStore: (label: String, onResolved: (latitude: Double?, longitude: Double?) -> Unit) -> Unit,
    /** Set by the activity when it is about to ask for background location; see
     *  [BackgroundLocationDisclosureDialog]. */
    backgroundLocationDisclosureState: MutableState<Boolean> = remember { mutableStateOf(false) },
    onBackgroundLocationDisclosureAccepted: () -> Unit = {}
) {
    val context = LocalContext.current
    // Collect only the slices this root scope actually reads, rather than the whole
    // PantryUiState. Reading the whole object here meant any change to any field —
    // a scan message, isLookingUpBarcode, the item list — invalidated the entire
    // content tree, including every screen below. Each screen already collects the
    // state it needs for itself.
    val settings by remember(stateHolder) {
        stateHolder.uiState.map { it.settings }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(stateHolder.uiState.value.settings)

    val shoppingListCount by remember(stateHolder) {
        stateHolder.uiState.map { it.shoppingList.size }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(stateHolder.uiState.value.shoppingList.size)

    val homeConfirmation by remember(stateHolder) {
        stateHolder.uiState.map { it.homeConfirmation }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(stateHolder.uiState.value.homeConfirmation)

    val needsOnboarding by remember(stateHolder) {
        stateHolder.uiState.map { it.needsOnboarding }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(stateHolder.uiState.value.needsOnboarding)

    // Settled once the pantry has loaded, since items in it mean onboarding is skipped.
    val onboardingUndecided by remember(stateHolder) {
        stateHolder.uiState.map { it.isLoading && !it.settings.onboardingCompleted }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(stateHolder.uiState.value.run { isLoading && !settings.onboardingCompleted })

    val darkTheme = when (settings.theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }

    LarderTheme(darkTheme = darkTheme) {
        if (backgroundLocationDisclosureState.value) {
            BackgroundLocationDisclosureDialog(
                onContinue = {
                    backgroundLocationDisclosureState.value = false
                    onBackgroundLocationDisclosureAccepted()
                },
                onDismiss = { backgroundLocationDisclosureState.value = false }
            )
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            var currentScreen by currentScreenState
            val currentTab = currentScreen.tab
            val shellChrome = remember { ShellChrome() }
            // Hoisted above the Dashboard so it survives navigating away to Add/Edit
            // and back — the Dashboard composable itself is torn down and rebuilt by
            // the `when` below, which would otherwise reset scroll position on return.
            val dashboardListState = rememberLazyListState()
            // Set when the scanner was launched from a specific shopping list row,
            // so a product-not-found result can still prefill the name/category the
            // user already typed there, instead of leaving Add/Edit blank.
            var scanningShoppingListEntry by remember { mutableStateOf<ShoppingListEntry?>(null) }
            // Set when the scanner was launched from the shopping list's own "scan to
            // add" action (rather than an existing row, or the dashboard's scan button):
            // a successful lookup is added straight to the shopping list instead of
            // proceeding to Add/Edit, since there's no pantry item to fill in yet.
            var isScanningForNewShoppingListEntry by remember { mutableStateOf(false) }
            // Screen to return to when the scanner/Add-Edit flow is backed out of or
            // completed — SCANNER and ADD_EDIT are reachable from both the Dashboard
            // and the Shopping List screen, so a hardcoded Dashboard destination would
            // strand a shopping-list-originated scan on the wrong screen.
            var scanReturnScreen by remember { mutableStateOf<LarderRoute>(LarderRoute.Home) }
            // Holds a generic shopping-list scan's ranked candidates while the "Did you
            // mean?" dialog is up; non-null triggers that dialog as an overlay.
            var pendingShoppingListMatch by remember { mutableStateOf<PendingShoppingListMatch?>(null) }
            // The item whose product detail is showing, over whatever screen is underneath.
            var detailItemId by remember { mutableStateOf<String?>(null) }
            // The row picked for the right-hand pane on expanded Home. Separate from the sheet's
            // item, so leaving Home with a pane open doesn't pop a sheet up on the next tab.
            var homePaneItemId by remember { mutableStateOf<String?>(null) }
            var remindersCalloutDismissed by rememberSaveable { mutableStateOf(false) }
            val expanded = isExpanded()
            val openPrefilledItem: (ItemPrefill) -> Unit = { prefill ->
                currentScreen = LarderRoute.AddItem(AddItemMode.Prefilled(prefill))
            }

            LaunchedEffect(Unit) {
                com.foodtracker.android.notification.NotificationHelper.scheduleDigest(
                    context,
                    settings.notifications
                )
            }

            val navigateToDashboard: () -> Unit = {
                currentScreen = LarderRoute.Home
            }
            val navigateBackFromScanner: () -> Unit = {
                stateHolder.clearScanState()
                scanningShoppingListEntry = null
                isScanningForNewShoppingListEntry = false
                currentScreen = scanReturnScreen
            }

            val onNavigateToScanner: (CameraScanMode) -> Unit = { mode ->
                currentScreen = LarderRoute.Scanner(mode)
            }
            // Invoked from the global floating "scan" button, reachable from any of the
            // four bottom-nav tabs, so it returns to whichever tab it was opened from
            // rather than always landing back on the Dashboard.
            val onNavigateToScannerGlobal: (CameraScanMode) -> Unit = { mode ->
                scanReturnScreen = currentScreen
                scanningShoppingListEntry = null
                isScanningForNewShoppingListEntry = false
                onNavigateToScanner(mode)
            }
            val onScanNewShoppingListEntry: () -> Unit = {
                scanReturnScreen = LarderRoute.Shopping
                scanningShoppingListEntry = null
                isScanningForNewShoppingListEntry = true
                onNavigateToScanner(CameraScanMode.BARCODE)
            }
            // Tapping a shopping list row (rather than its scan icon) skips the camera
            // entirely and goes straight to Add/Edit prefilled from the entry, for items
            // bought without a barcode to scan (produce, bakery, etc). Marking the entry
            // via beginScanForShoppingListEntry means saveItem still removes it from the
            // shopping list, same as the scan-to-complete path does.
            val onAddShoppingListEntryManually: (ShoppingListEntry) -> Unit = { entry ->
                scanReturnScreen = LarderRoute.Shopping
                scanningShoppingListEntry = entry
                isScanningForNewShoppingListEntry = false
                stateHolder.beginScanForShoppingListEntry(entry.id)
                openPrefilledItem(
                    ItemPrefill(
                        barcode = entry.barcode,
                        name = entry.productName,
                        brand = null,
                        imageUrl = null,
                        expiryDate = null,
                        category = entry.category,
                        suggestion = null
                    )
                )
            }
            val onNavigateToAddEdit: (PantryItem?) -> Unit = { item ->
                scanReturnScreen = currentScreen
                currentScreen = LarderRoute.AddItem(
                    if (item == null) AddItemMode.New else AddItemMode.Edit(item.id)
                )
            }
            val navigate by rememberUpdatedState<(LarderRoute) -> Unit> { route ->
                when (route) {
                    is LarderRoute.ProductDetail -> {
                        if (expanded && currentScreen == LarderRoute.Home) {
                            homePaneItemId = route.itemId
                        } else {
                            detailItemId = route.itemId
                        }
                    }
                    is LarderRoute.Scanner -> onNavigateToScannerGlobal(route.mode)
                    is LarderRoute.AddItem -> {
                        scanReturnScreen = currentScreen
                        currentScreen = route
                    }
                    else -> currentScreen = route
                }
            }
            val navigator = remember { LarderNavigator { navigate(it) } }
            // Snackbar offering to reverse a removal — a full delete, a "-" tap that
            // used up the last unit, or checking a shopping list entry off — since all
            // of these are otherwise a single tap that's easy to trigger by mistake.
            // Takes the removed thing's display name plus an undo action rather than
            // the item itself, so it works for both pantry items and shopping list
            // entries.
            val snackbarHostState = remember { SnackbarHostState() }
            val snackbarScope = rememberCoroutineScope()
            val larderSnackbars = remember(snackbarHostState, snackbarScope) {
                LarderSnackbars { message, actionLabel, onAction ->
                    snackbarScope.launch {
                        // A second removal shouldn't queue behind the first one's long Undo.
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = actionLabel,
                            duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
                    }
                }
            }
            // Shown wherever the person lands, once they're back on a tab. Launched on the
            // snackbar scope because consuming it ends this effect.
            LaunchedEffect(homeConfirmation, currentTab != null) {
                val confirmation = homeConfirmation
                if (confirmation != null && currentTab != null) {
                    stateHolder.consumeHomeConfirmation()
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(confirmationMessage(context, confirmation))
                    }
                }
            }
            var routedToOnboarding by remember { mutableStateOf(false) }
            LaunchedEffect(needsOnboarding) {
                if (SHOW_ONBOARDING_ON_FIRST_RUN && needsOnboarding) {
                    currentScreen = LarderRoute.Onboarding
                    routedToOnboarding = true
                }
            }
            val holdFirstScreen = SHOW_ONBOARDING_ON_FIRST_RUN &&
                holdsFirstScreen(onboardingUndecided, needsOnboarding, routedToOnboarding)
            // [addedToShoppingList] swaps the message to "added to shopping list" rather
            // than "removed" — the item still left the pantry, but calling that a
            // "removal" reads as if it just vanished, when it's actually sitting on the
            // shopping list now.
            val showUndoRemovalSnackbar: (String, Boolean, () -> Unit) -> Unit = { removedName, addedToShoppingList, onUndo ->
                // A second removal replaces the first message rather than queueing behind it
                // for ten seconds. The first one's Undo is lost, as on Home.
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarScope.launch {
                    val messageRes = if (addedToShoppingList) {
                        R.string.item_added_to_shopping_list_snackbar
                    } else {
                        R.string.item_removed_snackbar
                    }
                    val result = snackbarHostState.showSnackbar(
                        message = context.getString(messageRes, removedName),
                        actionLabel = context.getString(R.string.undo_action),
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        onUndo()
                    }
                }
            }
            // An import has three distinct outcomes worth telling apart: some entries
            // were added, the list was understood but everything on it was already
            // here, or nothing parseable was found at all. Collapsing the last two into
            // one message would leave the user unsure whether the import even worked.
            val showImportResultSnackbar: (Int, Int) -> Unit = { added, found ->
                snackbarScope.launch {
                    val message = when {
                        added > 0 -> context.resources.getQuantityString(
                            R.plurals.shopping_list_import_added, added, added
                        )
                        found > 0 -> context.getString(R.string.shopping_list_import_all_present)
                        else -> context.getString(R.string.shopping_list_import_nothing)
                    }
                    snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
                }
            }
            // Backup file I/O. Kept here rather than in SettingsScreen because it needs a
            // ContentResolver and a coroutine scope, and because the result has to reach
            // the same snackbar host everything else reports through.
            val onExportBackup: (android.net.Uri, (Boolean) -> Unit) -> Unit = { uri, onResult ->
                stateHolder.exportBackup { json, _ ->
                    snackbarScope.launch(Dispatchers.IO) {
                        val ok = try {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(json.toByteArray())
                            } != null
                        } catch (_: Exception) {
                            false
                        }
                        withContext(Dispatchers.Main) { onResult(ok) }
                    }
                }
            }
            val onImportBackup: (android.net.Uri, RestoreMode) -> Unit = { uri, mode ->
                snackbarScope.launch {
                    val raw = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (raw == null) {
                        snackbarHostState.showSnackbar(context.getString(R.string.backup_import_malformed))
                        return@launch
                    }
                    stateHolder.restoreBackup(raw, mode) { result, summary ->
                        snackbarScope.launch {
                            val message = when {
                                result is BackupReadResult.Malformed ->
                                    context.getString(R.string.backup_import_malformed)
                                result is BackupReadResult.UnsupportedVersion ->
                                    context.getString(R.string.backup_import_unsupported)
                                summary.isEmpty -> context.getString(R.string.backup_import_empty)
                                else -> context.resources.getQuantityString(
                                    R.plurals.backup_import_success,
                                    summary.itemsRestored,
                                    summary.itemsRestored
                                )
                            }
                            snackbarHostState.showSnackbar(message)
                        }
                    }
                }
            }

            val onBatchCommitted: (Int) -> Unit = { currentScreen = scanReturnScreen }
            val onDuplicateMerged: (PantryItem) -> Unit = { updated ->
                // The item's date hasn't changed, but its reminder was scheduled from a
                // state that no longer exists; rescheduling keeps it consistent with
                // every other write path.
                com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, updated)
                scanningShoppingListEntry = null
                currentScreen = scanReturnScreen
            }

            val onShoppingListPayloadScanned: (String) -> Unit = { payload ->
                stateHolder.importShoppingList(payload, showImportResultSnackbar)
                currentScreen = scanReturnScreen
            }

            val onDeleteItemHandler: (String) -> Unit = { id ->
                stateHolder.deleteItem(id) { removedItem ->
                    com.foodtracker.android.notification.NotificationHelper.cancelNotification(context, removedItem.id)
                    showUndoRemovalSnackbar(removedItem.name, false) {
                        stateHolder.restoreItem(removedItem)
                        com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, removedItem)
                    }
                }
            }
            // Several items go with one Undo, however many were selected.
            val onDeleteItemsHandler: (Set<String>) -> Unit = { ids ->
                stateHolder.deleteItems(ids) { removed ->
                    removed.forEach {
                        com.foodtracker.android.notification.NotificationHelper.cancelNotification(context, it.id)
                    }
                    snackbarScope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = context.resources.getQuantityString(
                                R.plurals.items_deleted_snackbar, removed.size, removed.size
                            ),
                            actionLabel = context.getString(R.string.undo_action),
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            stateHolder.restoreItems(removed)
                            removed.forEach {
                                com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, it)
                            }
                        }
                    }
                }
            }
            val onDecrementQuantityHandler: (String) -> Unit = { id ->
                stateHolder.decrementQuantity(id) { removedItem ->
                    com.foodtracker.android.notification.NotificationHelper.cancelNotification(context, removedItem.id)
                    showUndoRemovalSnackbar(removedItem.name, removedItem.essential) {
                        stateHolder.restoreItem(removedItem, removedFromShoppingList = removedItem.essential)
                        com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, removedItem)
                    }
                }
            }
            // "Use one", from a row, the product detail or the scanner: the last unit goes through
            // the same removal (essential items land on the shopping list, with an undo), and any
            // other unit gets its own undo, since a count changing is easy to miss.
            val onUseOneHandler: (PantryItem) -> Unit = { item ->
                if (item.quantity <= 1) {
                    // Same removal as the stepper's minus, but this was a use, so it says "used".
                    stateHolder.decrementQuantity(item.id) { removedItem ->
                        com.foodtracker.android.notification.NotificationHelper.cancelNotification(context, removedItem.id)
                        val messageRes = if (removedItem.essential) {
                            R.string.item_added_to_shopping_list_snackbar
                        } else {
                            R.string.calendar_used_snackbar
                        }
                        larderSnackbars.show(
                            context.getString(messageRes, removedItem.name),
                            context.getString(R.string.undo_action)
                        ) {
                            stateHolder.restoreItem(removedItem, removedFromShoppingList = removedItem.essential)
                            com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, removedItem)
                        }
                    }
                } else {
                    stateHolder.decrementQuantity(item.id)
                    larderSnackbars.show(
                        context.getString(R.string.home_used_one_snackbar, item.name, item.quantity - 1),
                        context.getString(R.string.undo_action)
                    ) { stateHolder.incrementQuantity(item.id) }
                }
            }
            val onMoveToShoppingListHandler: (String) -> Unit = { id ->
                stateHolder.moveToShoppingList(id) { removedItem ->
                    com.foodtracker.android.notification.NotificationHelper.cancelNotification(context, removedItem.id)
                    showUndoRemovalSnackbar(removedItem.name, true) {
                        stateHolder.restoreItem(removedItem, removedFromShoppingList = true)
                        com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, removedItem)
                    }
                }
            }
            val productDetailActions = ProductDetailActions(
                onUseOne = onUseOneHandler,
                onEdit = { onNavigateToAddEdit(it) },
                onMoveToShoppingList = onMoveToShoppingListHandler,
                onDelete = onDeleteItemHandler
            )
            val onCheckOffShoppingListEntryHandler: (String) -> Unit = { id ->
                stateHolder.deleteShoppingListEntry(id) { removedEntry ->
                    showUndoRemovalSnackbar(removedEntry.productName, false) {
                        stateHolder.restoreShoppingListEntry(removedEntry)
                    }
                }
            }
            val onDecrementShoppingListQuantityHandler: (String) -> Unit = { id ->
                stateHolder.decrementShoppingListQuantity(id) { removedEntry ->
                    showUndoRemovalSnackbar(removedEntry.productName, false) {
                        stateHolder.restoreShoppingListEntry(removedEntry)
                    }
                }
            }

            // Predictive back state: tracks an in-flight system back gesture so the
            // current screen can be scaled/translated away, previewing the Dashboard
            // underneath, instead of cutting straight to it on release.
            var inPredictiveBack by remember { mutableStateOf(false) }
            var backProgress by remember { mutableFloatStateOf(0f) }
            var backSwipeEdge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }

            // Onboarding steps back through its own screens, and Back on its first leaves the app
            // rather than reaching Home with the flow unfinished.
            PredictiveBackHandler(
                enabled = currentScreen != LarderRoute.Home && currentScreen != LarderRoute.Onboarding
            ) { progress ->
                try {
                    progress.collect { backEvent ->
                        inPredictiveBack = true
                        backProgress = backEvent.progress
                        backSwipeEdge = backEvent.swipeEdge
                    }
                    inPredictiveBack = false
                    backProgress = 0f
                    when (currentScreen) {
                        is LarderRoute.Scanner -> navigateBackFromScanner()
                        is LarderRoute.AddItem -> {
                            stateHolder.clearScanState()
                            scanningShoppingListEntry = null
                            currentScreen = scanReturnScreen
                        }
                        LarderRoute.Categories -> currentScreen = LarderRoute.Settings
                        LarderRoute.Settings, LarderRoute.Calendar, LarderRoute.Shopping -> navigateToDashboard()
                        is LarderRoute.ProductDetail, LarderRoute.Home, LarderRoute.Onboarding -> Unit
                    }
                } catch (e: CancellationException) {
                    inPredictiveBack = false
                    backProgress = 0f
                }
            }

            // Measured rather than assumed, because both grow with font scale. Read from the
            // composables themselves, so no surrounding padding is folded into the numbers.
            val density = LocalDensity.current
            var pillHeightPx by remember { mutableIntStateOf(0) }
            var scanHeightPx by remember { mutableIntStateOf(0) }
            val scanVisible = (currentTab == LarderTab.Home || currentTab == LarderTab.Calendar) &&
                !shellChrome.scanHidden
            val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val clearance = shellClearance(
                bottomInset = navBarInset,
                pillHeight = if (shellChrome.navHidden) 0.dp else with(density) { pillHeightPx.toDp() },
                scanHeight = if (scanVisible) with(density) { scanHeightPx.toDp() } else 0.dp,
                expanded = expanded
            )

            CompositionLocalProvider(
                LocalLarderNavigator provides navigator,
                LocalLarderSnackbars provides larderSnackbars
            ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Background preview of the Dashboard, revealed as the current screen
                // scales away during an in-progress predictive back gesture.
                if (inPredictiveBack && currentScreen != LarderRoute.Home) {
                    PantryDashboardScreen(
                        stateHolder = stateHolder,
                        onUseOne = onUseOneHandler,
                        onDeleteItem = onDeleteItemHandler,
                        onDeleteItems = onDeleteItemsHandler,
                        onMoveToShoppingList = onMoveToShoppingListHandler,
                        listState = dashboardListState
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (inPredictiveBack) {
                                Modifier.graphicsLayer {
                                    val scale = 1f - PREDICTIVE_BACK_SCALE_FACTOR * backProgress
                                    scaleX = scale
                                    scaleY = scale
                                    val maxTranslationPx = size.width * PREDICTIVE_BACK_MAX_TRANSLATION_FRACTION
                                    translationX = if (backSwipeEdge == BackEventCompat.EDGE_LEFT) {
                                        backProgress * maxTranslationPx
                                    } else {
                                        -backProgress * maxTranslationPx
                                    }
                                    transformOrigin = TransformOrigin(
                                        pivotFractionX = if (backSwipeEdge == BackEventCompat.EDGE_LEFT) 1f else 0f,
                                        pivotFractionY = 0.5f
                                    )
                                    shape = RoundedCornerShape(PREDICTIVE_BACK_MAX_CORNER_RADIUS_DP * backProgress)
                                    clip = true
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    if (holdFirstScreen) {
                        // Blank; see holdsFirstScreen.
                    } else if (currentTab != null) {
                        // The floating pill and Scan overlay full-size, edge-to-edge tab content
                        // rather than reserving layout space like a Scaffold's bars, so lists
                        // scroll underneath them. Each tab keeps its last row reachable with the
                        // bottom padding it gets from shellClearance.
                        CompositionLocalProvider(LocalShellChrome provides shellChrome) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AnimatedContent(
                                targetState = currentScreen,
                                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                                modifier = Modifier.fillMaxSize(),
                                label = "tabContent"
                            ) { screen ->
                            when (screen) {
                                LarderRoute.Home -> {
                                    PantryDashboardScreen(
                                        stateHolder = stateHolder,
                                        onUseOne = onUseOneHandler,
                                        onDeleteItem = onDeleteItemHandler,
                                        onDeleteItems = onDeleteItemsHandler,
                                        onMoveToShoppingList = onMoveToShoppingListHandler,
                                        listState = dashboardListState,
                                        bottomOverlayClearance = clearance.content,
                                        pickedItemId = homePaneItemId,
                                        remindersCalloutDismissed = remindersCalloutDismissed,
                                        onDismissRemindersCallout = { remindersCalloutDismissed = true },
                                        detailPane = { itemId ->
                                            ProductDetailPane(
                                                stateHolder = stateHolder,
                                                itemId = itemId,
                                                actions = productDetailActions,
                                                bottomClearance = clearance.content
                                            )
                                        }
                                    )
                                }
                                LarderRoute.Calendar -> {
                                    CalendarScreen(
                                        stateHolder = stateHolder,
                                        onUseOne = onUseOneHandler,
                                        bottomOverlayClearance = clearance.content
                                    )
                                }
                                LarderRoute.Shopping -> {
                                    ShoppingListScreen(
                                        stateHolder = stateHolder,
                                        onScanNewEntry = onScanNewShoppingListEntry,
                                        onAddEntryManually = onAddShoppingListEntryManually,
                                        onCheckOffEntry = onCheckOffShoppingListEntryHandler,
                                        onDecrementEntry = onDecrementShoppingListQuantityHandler,
                                        onImportComplete = showImportResultSnackbar,
                                        bottomOverlayClearance = clearance.content
                                    )
                                }
                                LarderRoute.Settings -> {
                                    SettingsScreen(
                                        settings = settings,
                                        onAddCurrentLocationAsStore = onAddCurrentLocationAsStore,
                                        onSave = { newSettings ->
                                            val notificationsChanged = newSettings.notifications != settings.notifications
                                            val storesChanged = newSettings.stores != settings.stores ||
                                                    newSettings.storeRemindersEnabled != settings.storeRemindersEnabled
                                            stateHolder.updateSettings(newSettings)
                                            if (storesChanged) {
                                                StoreGeofenceManager.sync(context, newSettings)
                                            }
                                            if (notificationsChanged) {
                                                com.foodtracker.android.notification.NotificationHelper.rescheduleAll(
                                                    context,
                                                    stateHolder.uiState.value.items
                                                )
                                                com.foodtracker.android.notification.NotificationHelper.scheduleDigest(
                                                    context,
                                                    newSettings.notifications
                                                )
                                            }
                                        },
                                        onClearAllData = {
                                            stateHolder.uiState.value.items.forEach { item ->
                                                com.foodtracker.android.notification.NotificationHelper.cancelNotification(context, item.id)
                                            }
                                            stateHolder.clearAllData()
                                            currentScreen = LarderRoute.Home
                                        },
                                        onManageCategories = { currentScreen = LarderRoute.Categories },
                                        onExportBackup = onExportBackup,
                                        pantryItemCount = { stateHolder.uiState.value.items.size },
                                        onImportBackup = onImportBackup,
                                        backupFileName = BackupSerializer.suggestedFileName(
                                            DateUtils.today().toString()
                                        ),
                                        bottomOverlayClearance = clearance.content
                                    )
                                }
                                else -> Unit
                            }
                            }

                            AnimatedVisibility(
                                visible = !shellChrome.navHidden,
                                enter = fadeIn() + slideInVertically { it / 2 },
                                exit = fadeOut() + slideOutVertically { it / 2 },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(start = LarderSpace.s3, end = LarderSpace.s3, bottom = LarderSpace.s3)
                            ) {
                                FloatingNav(
                                    selectedTab = currentTab,
                                    onTabSelected = { currentScreen = it.route },
                                    shoppingCount = shoppingListCount,
                                    modifier = Modifier.onGloballyPositioned { pillHeightPx = it.size.height }
                                )
                            }
                            AnimatedVisibility(
                                visible = scanVisible,
                                enter = fadeIn() + slideInVertically { it / 2 },
                                exit = fadeOut() + slideOutVertically { it / 2 },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(
                                        end = if (expanded) LarderSpace.s5 else LarderSpace.s4,
                                        bottom = clearance.scan
                                    )
                            ) {
                                ScanFab(
                                    onClick = { onNavigateToScannerGlobal(CameraScanMode.BARCODE) },
                                    modifier = Modifier.onGloballyPositioned { scanHeightPx = it.size.height }
                                )
                            }
                        }
                        }
                    } else {
                    when (val route = currentScreen) {
                        is LarderRoute.Scanner -> {
                            ScannerScreen(
                                stateHolder = stateHolder,
                                initialMode = route.mode,
                                onNavigateBack = navigateBackFromScanner,
                                onShoppingListScanned = onShoppingListPayloadScanned,
                                onBatchCommitted = onBatchCommitted,
                                // Not while the scan is filling in a shopping list entry:
                                // that scan is about buying the product, not using it.
                                onUseOne = if (isScanningForNewShoppingListEntry || scanningShoppingListEntry != null) {
                                    null
                                } else {
                                    { item ->
                                        onUseOneHandler(item)
                                        navigateBackFromScanner()
                                    }
                                },
                                onProceedToAddItem = { barcode, name, brand, imageUrl, expiryDate, category, suggestedExpiry ->
                                    if (isScanningForNewShoppingListEntry) {
                                        isScanningForNewShoppingListEntry = false
                                        // Read from the flow rather than composition state: this runs
                                        // in a callback, so there is no reason to make the root scope
                                        // a reader of the whole shopping list.
                                        when (val result = ShoppingListMatcher.match(name, barcode, stateHolder.uiState.value.shoppingList)) {
                                            is ShoppingListMatchResult.ExactBarcodeMatch -> {
                                                stateHolder.beginScanForShoppingListEntry(result.entry.id)
                                                openPrefilledItem(
                                                    ItemPrefill(
                                                        barcode = barcode,
                                                        name = name ?: result.entry.productName,
                                                        brand = brand,
                                                        imageUrl = imageUrl,
                                                        expiryDate = expiryDate,
                                                        category = category ?: result.entry.category,
                                                        suggestion = suggestedExpiry
                                                    )
                                                )
                                            }
                                            is ShoppingListMatchResult.Suggestions -> {
                                                pendingShoppingListMatch = PendingShoppingListMatch(
                                                    barcode = barcode,
                                                    name = name,
                                                    brand = brand,
                                                    imageUrl = imageUrl,
                                                    expiryDate = expiryDate,
                                                    category = category,
                                                    suggestedExpiry = suggestedExpiry,
                                                    candidates = result.candidates
                                                )
                                                currentScreen = scanReturnScreen
                                            }
                                            ShoppingListMatchResult.NoMatch -> {
                                                openPrefilledItem(
                                                    ItemPrefill(
                                                        barcode = barcode,
                                                        name = name,
                                                        brand = brand,
                                                        imageUrl = imageUrl,
                                                        expiryDate = expiryDate,
                                                        category = category,
                                                        suggestion = suggestedExpiry
                                                    )
                                                )
                                            }
                                        }
                                    } else {
                                        val fallbackEntry = scanningShoppingListEntry
                                        openPrefilledItem(
                                            ItemPrefill(
                                                barcode = barcode,
                                                name = name ?: fallbackEntry?.productName,
                                                brand = brand,
                                                imageUrl = imageUrl,
                                                expiryDate = expiryDate,
                                                category = category ?: fallbackEntry?.category,
                                                suggestion = suggestedExpiry
                                            )
                                        )
                                    }
                                }
                            )
                        }

                        is LarderRoute.AddItem -> {
                            val prefill = (route.mode as? AddItemMode.Prefilled)?.prefill
                            val editingId = (route.mode as? AddItemMode.Edit)?.itemId
                            val editingItem = remember(editingId) {
                                stateHolder.uiState.value.items.find { it.id == editingId }
                            }
                            AddEditItemScreen(
                                stateHolder = stateHolder,
                                initialItem = editingItem,
                                prefillBarcode = prefill?.barcode,
                                prefillName = prefill?.name,
                                prefillBrand = prefill?.brand,
                                prefillImageUrl = prefill?.imageUrl,
                                prefillExpiryDate = prefill?.expiryDate,
                                prefillCategory = prefill?.category,
                                prefillSuggestion = prefill?.suggestion,
                                settings = settings,
                                onNavigateBack = {
                                    // Cancelling out of Add/Edit must clear any pending
                                    // shopping-list-entry match (set by beginScanForShoppingListEntry
                                    // when this screen was reached directly from a shopping
                                    // list row, or via the scanner completing one) — otherwise
                                    // it lingers and the next unrelated saveItem call deletes
                                    // that entry as a side effect.
                                    stateHolder.clearScanState()
                                    scanningShoppingListEntry = null
                                    currentScreen = scanReturnScreen
                                },
                                onSaveItem = { item ->
                                    com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, item)
                                    stateHolder.saveItem(item)
                                    scanningShoppingListEntry = null
                                    currentScreen = scanReturnScreen
                                },
                                onDuplicateMerged = onDuplicateMerged
                            )
                        }

                        LarderRoute.Categories -> {
                            CategorySettingsScreen(
                                stateHolder = stateHolder,
                                onNavigateBack = { currentScreen = LarderRoute.Settings }
                            )
                        }

                        LarderRoute.Onboarding -> {
                            OnboardingScreen(
                                onFinished = { exit ->
                                    stateHolder.completeOnboarding()
                                    scanReturnScreen = LarderRoute.Home
                                    currentScreen = when (exit) {
                                        OnboardingExit.Scanner -> LarderRoute.Scanner()
                                        OnboardingExit.AddByHand -> LarderRoute.AddItem()
                                        OnboardingExit.Home -> LarderRoute.Home
                                    }
                                }
                            )
                        }

                        else -> Unit
                    }
                    }
                }

                pendingShoppingListMatch?.let { pending ->
                    ShoppingListMatchDialog(
                        candidates = pending.candidates,
                        onCandidateSelected = { chosen ->
                            stateHolder.beginScanForShoppingListEntry(chosen.id)
                            pendingShoppingListMatch = null
                            openPrefilledItem(
                                ItemPrefill(
                                    barcode = pending.barcode,
                                    name = pending.name ?: chosen.productName,
                                    brand = pending.brand,
                                    imageUrl = pending.imageUrl,
                                    expiryDate = pending.expiryDate,
                                    category = pending.category ?: chosen.category,
                                    suggestion = pending.suggestedExpiry
                                )
                            )
                        },
                        onNoneOfThese = {
                            pendingShoppingListMatch = null
                            openPrefilledItem(
                                ItemPrefill(
                                    barcode = pending.barcode,
                                    name = pending.name,
                                    brand = pending.brand,
                                    imageUrl = pending.imageUrl,
                                    expiryDate = pending.expiryDate,
                                    category = pending.category,
                                    suggestion = pending.suggestedExpiry
                                )
                            )
                        },
                        onDismiss = { pendingShoppingListMatch = null }
                    )
                }

                detailItemId?.let { itemId ->
                    ProductDetailRoute(
                        stateHolder = stateHolder,
                        itemId = itemId,
                        actions = productDetailActions,
                        onDismiss = { detailItemId = null }
                    )
                }

                // Over the list on expanded Home, so it never covers the product pane's buttons.
                val snackbarOverList = expanded && currentScreen == LarderRoute.Home
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(if (snackbarOverList) Alignment.BottomStart else Alignment.BottomCenter)
                        .then(if (snackbarOverList) Modifier.width(HomeListPaneWidth) else Modifier)
                        .padding(
                            start = LarderSpace.s4,
                            end = LarderSpace.s4,
                            // Above the keyboard when it's up, so Undo stays reachable.
                            bottom = maxOf(
                                if (currentTab != null) clearance.snackbar else navBarInset + 88.dp,
                                with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() } + LarderSpace.s2
                            )
                        )
                ) { data ->
                    LarderSnackbar(data)
                }
            }
            }
        }
    }
}
