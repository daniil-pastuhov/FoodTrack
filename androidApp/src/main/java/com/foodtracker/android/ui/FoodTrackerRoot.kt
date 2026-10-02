package com.foodtracker.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.location.StoreGeofenceManager
import com.foodtracker.android.ui.components.AppBottomBar
import com.foodtracker.android.ui.components.BottomNavTab
import com.foodtracker.android.ui.components.ScanAndAddFabs
import com.foodtracker.android.ui.screens.AddEditItemScreen
import com.foodtracker.android.ui.screens.CalendarScreen
import com.foodtracker.android.ui.screens.CategorySettingsScreen
import com.foodtracker.android.ui.screens.PantryDashboardScreen
import com.foodtracker.android.ui.screens.ScannerScreen
import com.foodtracker.android.ui.screens.SettingsScreen
import com.foodtracker.android.ui.screens.ShoppingListMatchDialog
import com.foodtracker.android.ui.screens.ShoppingListScreen
import com.foodtracker.android.ui.theme.FoodTrackerTheme
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
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import com.foodtracker.android.ui.components.BackgroundLocationDisclosureDialog

enum class AppScreen {
    DASHBOARD,
    SCANNER,
    ADD_EDIT,
    SETTINGS,
    CATEGORY_SETTINGS,
    CALENDAR,
    SHOPPING_LIST
}

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
    currentScreenState: MutableState<AppScreen>,
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

    val darkTheme = when (settings.theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }

    FoodTrackerTheme(darkTheme = darkTheme) {
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
            // Hoisted above the Dashboard so it survives navigating away to Add/Edit
            // and back — the Dashboard composable itself is torn down and rebuilt by
            // the `when` below, which would otherwise reset scroll position on return.
            val dashboardGridState = rememberLazyGridState()
            var editingItem by remember { mutableStateOf<PantryItem?>(null) }
            var prefillBarcode by remember { mutableStateOf<String?>(null) }
            var prefillName by remember { mutableStateOf<String?>(null) }
            var prefillBrand by remember { mutableStateOf<String?>(null) }
            var prefillImageUrl by remember { mutableStateOf<String?>(null) }
            var prefillExpiryDate by remember { mutableStateOf<LocalDate?>(null) }
            var prefillCategory by remember { mutableStateOf<String?>(null) }
            // Expiry to start a new item from when the scan produced no date of its
            // own — the shelf life this barcode was last given, or its category's
            // default. Reset alongside the other prefills so a suggestion from one scan
            // can't leak into the next, unrelated Add/Edit.
            var prefillSuggestion by remember { mutableStateOf<ShelfLifeSuggestion?>(null) }
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
            var scanReturnScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
            // Holds a generic shopping-list scan's ranked candidates while the "Did you
            // mean?" dialog is up; non-null triggers that dialog as an overlay.
            var pendingShoppingListMatch by remember { mutableStateOf<PendingShoppingListMatch?>(null) }

            // Camera permission launcher
            val cameraPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (isGranted) {
                    currentScreen = AppScreen.SCANNER
                }
            }

            // Notification permission launcher (Android 13+)
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { _ -> }

            androidx.compose.runtime.LaunchedEffect(Unit) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                com.foodtracker.android.notification.NotificationHelper.scheduleDigest(
                    context,
                    settings.notifications
                )
            }

            val navigateToDashboard: () -> Unit = {
                currentScreen = AppScreen.DASHBOARD
            }
            val navigateBackFromScanner: () -> Unit = {
                stateHolder.clearScanState()
                scanningShoppingListEntry = null
                isScanningForNewShoppingListEntry = false
                currentScreen = scanReturnScreen
            }

            val onNavigateToScanner: () -> Unit = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    currentScreen = AppScreen.SCANNER
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
            // Invoked from the global floating "scan" button, reachable from any of the
            // four bottom-nav tabs, so it returns to whichever tab it was opened from
            // rather than always landing back on the Dashboard.
            val onNavigateToScannerGlobal: () -> Unit = {
                scanReturnScreen = currentScreen
                scanningShoppingListEntry = null
                isScanningForNewShoppingListEntry = false
                onNavigateToScanner()
            }
            val onScanNewShoppingListEntry: () -> Unit = {
                scanReturnScreen = AppScreen.SHOPPING_LIST
                scanningShoppingListEntry = null
                isScanningForNewShoppingListEntry = true
                onNavigateToScanner()
            }
            // Tapping a shopping list row (rather than its scan icon) skips the camera
            // entirely and goes straight to Add/Edit prefilled from the entry, for items
            // bought without a barcode to scan (produce, bakery, etc). Marking the entry
            // via beginScanForShoppingListEntry means saveItem still removes it from the
            // shopping list, same as the scan-to-complete path does.
            val onAddShoppingListEntryManually: (ShoppingListEntry) -> Unit = { entry ->
                scanReturnScreen = AppScreen.SHOPPING_LIST
                scanningShoppingListEntry = entry
                isScanningForNewShoppingListEntry = false
                stateHolder.beginScanForShoppingListEntry(entry.id)
                editingItem = null
                prefillBarcode = entry.barcode
                prefillName = entry.productName
                prefillBrand = null
                prefillImageUrl = null
                prefillExpiryDate = null
                prefillCategory = entry.category
                prefillSuggestion = null
                currentScreen = AppScreen.ADD_EDIT
            }
            val onNavigateToAddEdit: (PantryItem?) -> Unit = { item ->
                editingItem = item
                prefillBarcode = null
                prefillName = null
                prefillBrand = null
                prefillImageUrl = null
                prefillExpiryDate = null
                prefillCategory = null
                prefillSuggestion = null
                scanReturnScreen = currentScreen
                currentScreen = AppScreen.ADD_EDIT
            }
            // Maps the four bottom-nav tabs onto AppScreen and back, for the shared
            // outer Scaffold that hosts the floating pill nav bar below.
            val currentTab = when (currentScreen) {
                AppScreen.CALENDAR -> BottomNavTab.CALENDAR
                AppScreen.SHOPPING_LIST -> BottomNavTab.SHOPPING_LIST
                AppScreen.SETTINGS -> BottomNavTab.SETTINGS
                else -> BottomNavTab.HOME
            }
            val onTabSelected: (BottomNavTab) -> Unit = { tab ->
                currentScreen = when (tab) {
                    BottomNavTab.HOME -> AppScreen.DASHBOARD
                    BottomNavTab.CALENDAR -> AppScreen.CALENDAR
                    BottomNavTab.SHOPPING_LIST -> AppScreen.SHOPPING_LIST
                    BottomNavTab.SETTINGS -> AppScreen.SETTINGS
                }
            }
            // Snackbar offering to reverse a removal — a full delete, a "-" tap that
            // used up the last unit, or checking a shopping list entry off — since all
            // of these are otherwise a single tap that's easy to trigger by mistake.
            // Takes the removed thing's display name plus an undo action rather than
            // the item itself, so it works for both pantry items and shopping list
            // entries.
            val snackbarHostState = remember { SnackbarHostState() }
            val snackbarScope = rememberCoroutineScope()
            // [addedToShoppingList] swaps the message to "added to shopping list" rather
            // than "removed" — the item still left the pantry, but calling that a
            // "removal" reads as if it just vanished, when it's actually sitting on the
            // shopping list now.
            val showUndoRemovalSnackbar: (String, Boolean, () -> Unit) -> Unit = { removedName, addedToShoppingList, onUndo ->
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
            val onExportBackup: (android.net.Uri) -> Unit = { uri ->
                stateHolder.exportBackup { json, _ ->
                    snackbarScope.launch(Dispatchers.IO) {
                        val ok = try {
                            context.contentResolver.openOutputStream(uri)?.use { stream ->
                                stream.write(json.toByteArray())
                            } != null
                        } catch (_: Exception) {
                            false
                        }
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar(
                                context.getString(if (ok) R.string.backup_export_success else R.string.backup_export_failed)
                            )
                        }
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

            val onBatchCommitted: (Int) -> Unit = { added ->
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(
                        context.resources.getQuantityString(R.plurals.batch_added_snackbar, added, added)
                    )
                }
                currentScreen = scanReturnScreen
            }
            val onDuplicateMerged: (PantryItem) -> Unit = { updated ->
                // The item's date hasn't changed, but its reminder was scheduled from a
                // state that no longer exists; rescheduling keeps it consistent with
                // every other write path.
                com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, updated)
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.duplicate_added_snackbar, updated.name, updated.quantity)
                    )
                }
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
            val onDecrementQuantityHandler: (String) -> Unit = { id ->
                stateHolder.decrementQuantity(id) { removedItem ->
                    com.foodtracker.android.notification.NotificationHelper.cancelNotification(context, removedItem.id)
                    showUndoRemovalSnackbar(removedItem.name, removedItem.essential) {
                        stateHolder.restoreItem(removedItem, removedFromShoppingList = removedItem.essential)
                        com.foodtracker.android.notification.NotificationHelper.scheduleNotification(context, removedItem)
                    }
                }
            }
            // The scanner's "Use one": the last unit goes through the same removal as
            // the row's "-" button (essential items land on the shopping list, with an
            // undo), while any other unit gets its own undo, since leaving the scanner
            // gives no other sign that the count changed.
            val onUseOneHandler: (PantryItem) -> Unit = { item ->
                if (item.quantity <= 1) {
                    onDecrementQuantityHandler(item.id)
                } else {
                    stateHolder.decrementQuantity(item.id)
                    snackbarScope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = context.getString(R.string.duplicate_added_snackbar, item.name, item.quantity - 1),
                            actionLabel = context.getString(R.string.undo_action),
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            stateHolder.incrementQuantity(item.id)
                        }
                    }
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

            PredictiveBackHandler(enabled = currentScreen != AppScreen.DASHBOARD) { progress ->
                try {
                    progress.collect { backEvent ->
                        inPredictiveBack = true
                        backProgress = backEvent.progress
                        backSwipeEdge = backEvent.swipeEdge
                    }
                    inPredictiveBack = false
                    backProgress = 0f
                    when (currentScreen) {
                        AppScreen.SCANNER -> navigateBackFromScanner()
                        AppScreen.ADD_EDIT -> currentScreen = scanReturnScreen
                        AppScreen.CATEGORY_SETTINGS -> currentScreen = AppScreen.SETTINGS
                        AppScreen.SETTINGS, AppScreen.CALENDAR, AppScreen.SHOPPING_LIST -> navigateToDashboard()
                        AppScreen.DASHBOARD -> Unit
                    }
                } catch (e: CancellationException) {
                    inPredictiveBack = false
                    backProgress = 0f
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Background preview of the Dashboard, revealed as the current screen
                // scales away during an in-progress predictive back gesture.
                if (inPredictiveBack && currentScreen != AppScreen.DASHBOARD) {
                    PantryDashboardScreen(
                        stateHolder = stateHolder,
                        onNavigateToAddEdit = {},
                        onDeleteItem = onDeleteItemHandler,
                        onDecrementQuantity = onDecrementQuantityHandler,
                        onMoveToShoppingList = onMoveToShoppingListHandler,
                        gridState = dashboardGridState
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
                    val tabScreens = setOf(
                        AppScreen.DASHBOARD,
                        AppScreen.CALENDAR,
                        AppScreen.SHOPPING_LIST,
                        AppScreen.SETTINGS
                    )
                    if (currentScreen in tabScreens) {
                        // Shared chrome for the four peer tabs: a floating pill nav bar
                        // plus the scan/add-item round buttons, both reachable from any
                        // tab. Unlike a Scaffold bottomBar/FAB (which reserve layout
                        // space and push content up above them), this overlays the bar
                        // and buttons on top of full-size, edge-to-edge tab content, so
                        // the product list scrolls underneath them rather than stopping
                        // short — each tab reserves its own bottom content padding to
                        // keep its last row reachable above the overlay.
                        Box(modifier = Modifier.fillMaxSize()) {
                            // The FAB stack's bottom offset is derived from the pill's
                            // actually-measured height (rather than a hardcoded guess)
                            // so the two never overlap regardless of font scale/density.
                            // Measured directly on the pill/FAB composables themselves
                            // (not on a Box wrapping them with navigationBarsPadding/
                            // margin modifiers) so each value is exactly that element's
                            // own size, with no ambiguity about whether surrounding
                            // padding is folded in — that ambiguity previously caused the
                            // nav bar inset to be counted twice and left a large dead gap
                            // under Home's last row.
                            var pillHeightPx by remember { mutableFloatStateOf(0f) }
                            var fabStackHeightPx by remember { mutableFloatStateOf(0f) }
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            val pillHeight = with(density) { pillHeightPx.toDp() }
                            val fabStackHeight = with(density) { fabStackHeightPx.toDp() }
                            // Mirrors the pill's own visual placement (navigationBarsPadding
                            // + this same 12dp bottom margin, see the Box below) plus a small
                            // breathing-room buffer above it.
                            val pillMarginBottom = 12.dp
                            val bufferAboveOverlay = 16.dp
                            val pillClearance = navBarInset + pillMarginBottom + pillHeight + bufferAboveOverlay
                            // Home also has the FABs floating above the pill (see their own
                            // 24dp gap above it below), so it needs extra clearance the
                            // other tabs (pill only) don't.
                            val fabGapAbovePill = 24.dp
                            val homeClearance = navBarInset + pillMarginBottom + pillHeight +
                                fabGapAbovePill + fabStackHeight + bufferAboveOverlay

                            AnimatedContent(
                                targetState = currentScreen,
                                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
                                modifier = Modifier.fillMaxSize(),
                                label = "tabContent"
                            ) { screen ->
                            when (screen) {
                                AppScreen.DASHBOARD -> {
                                    PantryDashboardScreen(
                                        stateHolder = stateHolder,
                                        onNavigateToAddEdit = onNavigateToAddEdit,
                                        onDeleteItem = onDeleteItemHandler,
                                        onDecrementQuantity = onDecrementQuantityHandler,
                                        onMoveToShoppingList = onMoveToShoppingListHandler,
                                        gridState = dashboardGridState,
                                        bottomOverlayClearance = homeClearance
                                    )
                                }
                                AppScreen.CALENDAR -> {
                                    CalendarScreen(
                                        stateHolder = stateHolder,
                                        onEditItem = onNavigateToAddEdit,
                                        onUseOne = onUseOneHandler,
                                        onToss = onDeleteItemHandler,
                                        bottomOverlayClearance = pillClearance
                                    )
                                }
                                AppScreen.SHOPPING_LIST -> {
                                    ShoppingListScreen(
                                        stateHolder = stateHolder,
                                        onScanNewEntry = onScanNewShoppingListEntry,
                                        onAddEntryManually = onAddShoppingListEntryManually,
                                        onCheckOffEntry = onCheckOffShoppingListEntryHandler,
                                        onDecrementEntry = onDecrementShoppingListQuantityHandler,
                                        onImportComplete = showImportResultSnackbar,
                                        bottomOverlayClearance = pillClearance
                                    )
                                }
                                AppScreen.SETTINGS -> {
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
                                            currentScreen = AppScreen.DASHBOARD
                                        },
                                        onManageCategories = { currentScreen = AppScreen.CATEGORY_SETTINGS },
                                        onExportBackup = onExportBackup,
                                        onImportBackup = onImportBackup,
                                        backupFileName = BackupSerializer.suggestedFileName(
                                            DateUtils.today().toString()
                                        ),
                                        bottomOverlayClearance = pillClearance
                                    )
                                }
                                else -> Unit
                            }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .navigationBarsPadding()
                                    .padding(bottom = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AppBottomBar(
                                    selectedTab = currentTab,
                                    onTabSelected = onTabSelected,
                                    shoppingListCount = shoppingListCount,
                                    modifier = Modifier.onGloballyPositioned { pillHeightPx = it.size.height.toFloat() }
                                )
                            }
                            val fabsVisible = currentTab == BottomNavTab.HOME
                            AnimatedVisibility(
                                visible = fabsVisible,
                                enter = fadeIn() + slideInVertically { it / 2 },
                                exit = fadeOut() + slideOutVertically { it / 2 },
                                modifier = Modifier.align(Alignment.BottomEnd)
                            ) {
                                ScanAndAddFabs(
                                    onScan = onNavigateToScannerGlobal,
                                    onAdd = { onNavigateToAddEdit(null) },
                                    modifier = Modifier
                                        .padding(
                                            end = 16.dp,
                                            bottom = navBarInset + pillMarginBottom + pillHeight + fabGapAbovePill
                                        )
                                        .onGloballyPositioned { fabStackHeightPx = it.size.height.toFloat() }
                                )
                            }
                        }
                    } else {
                    when (currentScreen) {
                        AppScreen.SCANNER -> {
                            ScannerScreen(
                                stateHolder = stateHolder,
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
                                                editingItem = null
                                                prefillBarcode = barcode
                                                prefillName = name ?: result.entry.productName
                                                prefillBrand = brand
                                                prefillImageUrl = imageUrl
                                                prefillExpiryDate = expiryDate
                                                prefillCategory = category ?: result.entry.category
                                                prefillSuggestion = suggestedExpiry
                                                currentScreen = AppScreen.ADD_EDIT
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
                                                editingItem = null
                                                prefillBarcode = barcode
                                                prefillName = name
                                                prefillBrand = brand
                                                prefillImageUrl = imageUrl
                                                prefillExpiryDate = expiryDate
                                                prefillCategory = category
                                                prefillSuggestion = suggestedExpiry
                                                currentScreen = AppScreen.ADD_EDIT
                                            }
                                        }
                                    } else {
                                        val fallbackEntry = scanningShoppingListEntry
                                        editingItem = null
                                        prefillBarcode = barcode
                                        prefillName = name ?: fallbackEntry?.productName
                                        prefillBrand = brand
                                        prefillImageUrl = imageUrl
                                        prefillExpiryDate = expiryDate
                                        prefillCategory = category ?: fallbackEntry?.category
                                        prefillSuggestion = suggestedExpiry
                                        currentScreen = AppScreen.ADD_EDIT
                                    }
                                }
                            )
                        }

                        AppScreen.ADD_EDIT -> {
                            AddEditItemScreen(
                                stateHolder = stateHolder,
                                initialItem = editingItem,
                                prefillBarcode = prefillBarcode,
                                prefillName = prefillName,
                                prefillBrand = prefillBrand,
                                prefillImageUrl = prefillImageUrl,
                                prefillExpiryDate = prefillExpiryDate,
                                prefillCategory = prefillCategory,
                                prefillSuggestion = prefillSuggestion,
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

                        AppScreen.CATEGORY_SETTINGS -> {
                            CategorySettingsScreen(
                                stateHolder = stateHolder,
                                onNavigateBack = { currentScreen = AppScreen.SETTINGS }
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
                            editingItem = null
                            prefillBarcode = pending.barcode
                            prefillName = pending.name ?: chosen.productName
                            prefillBrand = pending.brand
                            prefillImageUrl = pending.imageUrl
                            prefillExpiryDate = pending.expiryDate
                            prefillCategory = pending.category ?: chosen.category
                            prefillSuggestion = pending.suggestedExpiry
                            pendingShoppingListMatch = null
                            currentScreen = AppScreen.ADD_EDIT
                        },
                        onNoneOfThese = {
                            editingItem = null
                            prefillBarcode = pending.barcode
                            prefillName = pending.name
                            prefillBrand = pending.brand
                            prefillImageUrl = pending.imageUrl
                            prefillExpiryDate = pending.expiryDate
                            prefillCategory = pending.category
                            prefillSuggestion = pending.suggestedExpiry
                            pendingShoppingListMatch = null
                            currentScreen = AppScreen.ADD_EDIT
                        },
                        onDismiss = { pendingShoppingListMatch = null }
                    )
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 88.dp)
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        actionColor = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
