package com.foodtracker.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.foodtracker.android.R
import com.foodtracker.android.ui.screens.AddEditItemScreen
import com.foodtracker.android.ui.screens.CalendarScreen
import com.foodtracker.android.ui.screens.PantryDashboardScreen
import com.foodtracker.android.ui.screens.ScannerScreen
import com.foodtracker.android.ui.screens.SettingsScreen
import com.foodtracker.android.ui.screens.ShoppingListScreen
import com.foodtracker.android.ui.screens.ShoppingListMatchDialog
import com.foodtracker.android.ui.components.AppBottomBar
import com.foodtracker.android.ui.components.BottomNavTab
import com.foodtracker.android.ui.components.ScanAndAddFabs
import com.foodtracker.android.ui.theme.FoodTrackerTheme
import com.foodtracker.match.ShoppingListMatchResult
import com.foodtracker.match.ShoppingListMatcher
import com.foodtracker.model.AppTheme
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ShoppingListEntry
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.koin.android.ext.android.inject

enum class AppScreen {
    DASHBOARD,
    SCANNER,
    ADD_EDIT,
    SETTINGS,
    CALENDAR,
    SHOPPING_LIST
}

/** True while the list is scrolling up (or at rest) and false while actively scrolling down,
 *  by comparing each recomposition's first-visible-item position against the last one seen.
 *  Drives the scan/add FAB's collapse — it hides on the same downward scroll gesture that
 *  collapses the dashboard's header, and reappears on the same upward scroll that expands it. */
@Composable
private fun LazyGridState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableStateOf(firstVisibleItemIndex) }
    var previousScrollOffset by remember(this) { mutableStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
        derivedStateOf {
            val scrollingUp = if (previousIndex != firstVisibleItemIndex) {
                previousIndex > firstVisibleItemIndex
            } else {
                previousScrollOffset >= firstVisibleItemScrollOffset
            }
            previousIndex = firstVisibleItemIndex
            previousScrollOffset = firstVisibleItemScrollOffset
            scrollingUp
        }
    }.value
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
    val candidates: List<ShoppingListEntry>
)

// Material predictive-back visual treatment for the screen being swiped away: it shrinks
// slightly, shifts toward the swipe edge, and gains rounded corners as the gesture progresses.
private const val PREDICTIVE_BACK_SCALE_FACTOR = 0.1f
private const val PREDICTIVE_BACK_MAX_TRANSLATION_FRACTION = 0.05f
private val PREDICTIVE_BACK_MAX_CORNER_RADIUS_DP = 32.dp

class MainActivity : ComponentActivity() {

    private val stateHolder: PantryStateHolder by inject()

    // Hoisted out of the composable (rather than `remember`ed) so the Shopping List widget's
    // tap intent can navigate there via onNewIntent even when MainActivity is already running,
    // not just on a cold start.
    private val currentScreenState = mutableStateOf(AppScreen.DASHBOARD)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenFromIntent(intent)

        setContent {
            val uiState by stateHolder.uiState.collectAsState()
            val darkTheme = when (uiState.settings.theme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }

            FoodTrackerTheme(darkTheme = darkTheme) {
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
                            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        com.foodtracker.android.notification.NotificationHelper.scheduleDigest(
                            this@MainActivity,
                            uiState.settings.notifications
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
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
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
                    val onScanShoppingListEntry: (ShoppingListEntry) -> Unit = { entry ->
                        scanReturnScreen = AppScreen.SHOPPING_LIST
                        scanningShoppingListEntry = entry
                        isScanningForNewShoppingListEntry = false
                        stateHolder.beginScanForShoppingListEntry(entry.id)
                        onNavigateToScanner()
                    }
                    val onScanNewShoppingListEntry: () -> Unit = {
                        scanReturnScreen = AppScreen.SHOPPING_LIST
                        scanningShoppingListEntry = null
                        isScanningForNewShoppingListEntry = true
                        onNavigateToScanner()
                    }
                    val onNavigateToAddEdit: (PantryItem?) -> Unit = { item ->
                        editingItem = item
                        prefillBarcode = null
                        prefillName = null
                        prefillBrand = null
                        prefillImageUrl = null
                        prefillExpiryDate = null
                        prefillCategory = null
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
                    val showUndoRemovalSnackbar: (String, () -> Unit) -> Unit = { removedName, onUndo ->
                        snackbarScope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = this@MainActivity.getString(R.string.item_removed_snackbar, removedName),
                                actionLabel = this@MainActivity.getString(R.string.undo_action),
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                onUndo()
                            }
                        }
                    }
                    val onDeleteItemHandler: (String) -> Unit = { id ->
                        stateHolder.deleteItem(id) { removedItem ->
                            com.foodtracker.android.notification.NotificationHelper.cancelNotification(this@MainActivity, removedItem.id)
                            showUndoRemovalSnackbar(removedItem.name) {
                                stateHolder.restoreItem(removedItem)
                                com.foodtracker.android.notification.NotificationHelper.scheduleNotification(this@MainActivity, removedItem)
                            }
                        }
                    }
                    val onDecrementQuantityHandler: (String) -> Unit = { id ->
                        stateHolder.decrementQuantity(id) { removedItem ->
                            com.foodtracker.android.notification.NotificationHelper.cancelNotification(this@MainActivity, removedItem.id)
                            showUndoRemovalSnackbar(removedItem.name) {
                                stateHolder.restoreItem(removedItem)
                                com.foodtracker.android.notification.NotificationHelper.scheduleNotification(this@MainActivity, removedItem)
                            }
                        }
                    }
                    val onCheckOffShoppingListEntryHandler: (String) -> Unit = { id ->
                        stateHolder.deleteShoppingListEntry(id) { removedEntry ->
                            showUndoRemovalSnackbar(removedEntry.productName) {
                                stateHolder.restoreShoppingListEntry(removedEntry)
                            }
                        }
                    }
                    val onDecrementShoppingListQuantityHandler: (String) -> Unit = { id ->
                        stateHolder.decrementShoppingListQuantity(id) { removedEntry ->
                            showUndoRemovalSnackbar(removedEntry.productName) {
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
                                                gridState = dashboardGridState
                                            )
                                        }
                                        AppScreen.CALENDAR -> {
                                            CalendarScreen(stateHolder = stateHolder)
                                        }
                                        AppScreen.SHOPPING_LIST -> {
                                            ShoppingListScreen(
                                                stateHolder = stateHolder,
                                                onScanEntry = onScanShoppingListEntry,
                                                onScanNewEntry = onScanNewShoppingListEntry,
                                                onCheckOffEntry = onCheckOffShoppingListEntryHandler,
                                                onDecrementEntry = onDecrementShoppingListQuantityHandler
                                            )
                                        }
                                        AppScreen.SETTINGS -> {
                                            SettingsScreen(
                                                settings = uiState.settings,
                                                onSave = { newSettings ->
                                                    val notificationsChanged = newSettings.notifications != uiState.settings.notifications
                                                    stateHolder.updateSettings(newSettings)
                                                    if (notificationsChanged) {
                                                        com.foodtracker.android.notification.NotificationHelper.rescheduleAll(
                                                            this@MainActivity,
                                                            stateHolder.uiState.value.items
                                                        )
                                                        com.foodtracker.android.notification.NotificationHelper.scheduleDigest(
                                                            this@MainActivity,
                                                            newSettings.notifications
                                                        )
                                                    }
                                                },
                                                onClearAllData = {
                                                    stateHolder.uiState.value.items.forEach { item ->
                                                        com.foodtracker.android.notification.NotificationHelper.cancelNotification(this@MainActivity, item.id)
                                                    }
                                                    stateHolder.clearAllData()
                                                    currentScreen = AppScreen.DASHBOARD
                                                }
                                            )
                                        }
                                        else -> Unit
                                    }
                                    }

                                    // The FAB stack's bottom offset is derived from the pill's
                                    // actually-measured height (rather than a hardcoded guess)
                                    // so the two never overlap regardless of font scale/density.
                                    var pillBarHeightPx by remember { mutableFloatStateOf(0f) }
                                    val density = androidx.compose.ui.platform.LocalDensity.current

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .navigationBarsPadding()
                                            .padding(bottom = 12.dp)
                                            .onGloballyPositioned { pillBarHeightPx = it.size.height.toFloat() },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        AppBottomBar(
                                            selectedTab = currentTab,
                                            onTabSelected = onTabSelected,
                                            shoppingListCount = uiState.shoppingList.size
                                        )
                                    }
                                    // Scan/Add are only meaningful from the pantry list, so they
                                    // only show on Home — collapsing away on the same downward
                                    // scroll gesture that collapses the dashboard's own header,
                                    // and floating out entirely when navigating to another tab.
                                    val fabsVisible = currentTab == BottomNavTab.HOME && dashboardGridState.isScrollingUp()
                                    AnimatedVisibility(
                                        visible = fabsVisible,
                                        enter = fadeIn() + slideInVertically { it / 2 },
                                        exit = fadeOut() + slideOutVertically { it / 2 },
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                    ) {
                                        ScanAndAddFabs(
                                            onScan = onNavigateToScannerGlobal,
                                            onAdd = { onNavigateToAddEdit(null) },
                                            modifier = Modifier.padding(
                                                end = 16.dp,
                                                bottom = with(density) { pillBarHeightPx.toDp() } + 24.dp
                                            )
                                        )
                                    }
                                }
                            } else {
                            when (currentScreen) {
                                AppScreen.SCANNER -> {
                                    ScannerScreen(
                                        stateHolder = stateHolder,
                                        onNavigateBack = navigateBackFromScanner,
                                        onProceedToAddItem = { barcode, name, brand, imageUrl, expiryDate, category ->
                                            if (isScanningForNewShoppingListEntry) {
                                                isScanningForNewShoppingListEntry = false
                                                when (val result = ShoppingListMatcher.match(name, barcode, uiState.shoppingList)) {
                                                    is ShoppingListMatchResult.ExactBarcodeMatch -> {
                                                        stateHolder.beginScanForShoppingListEntry(result.entry.id)
                                                        editingItem = null
                                                        prefillBarcode = barcode
                                                        prefillName = name ?: result.entry.productName
                                                        prefillBrand = brand
                                                        prefillImageUrl = imageUrl
                                                        prefillExpiryDate = expiryDate
                                                        prefillCategory = category ?: result.entry.category
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
                                                currentScreen = AppScreen.ADD_EDIT
                                            }
                                        }
                                    )
                                }

                                AppScreen.ADD_EDIT -> {
                                    AddEditItemScreen(
                                        initialItem = editingItem,
                                        prefillBarcode = prefillBarcode,
                                        prefillName = prefillName,
                                        prefillBrand = prefillBrand,
                                        prefillImageUrl = prefillImageUrl,
                                        prefillExpiryDate = prefillExpiryDate,
                                        prefillCategory = prefillCategory,
                                        settings = uiState.settings,
                                        onNavigateBack = { currentScreen = scanReturnScreen },
                                        onSaveItem = { item ->
                                            com.foodtracker.android.notification.NotificationHelper.scheduleNotification(this@MainActivity, item)
                                            stateHolder.saveItem(item)
                                            scanningShoppingListEntry = null
                                            currentScreen = scanReturnScreen
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
                                    editingItem = null
                                    prefillBarcode = pending.barcode
                                    prefillName = pending.name ?: chosen.productName
                                    prefillBrand = pending.brand
                                    prefillImageUrl = pending.imageUrl
                                    prefillExpiryDate = pending.expiryDate
                                    prefillCategory = pending.category ?: chosen.category
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScreenFromIntent(intent)
    }

    private fun applyScreenFromIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_SHOPPING_LIST, false)) {
            currentScreenState.value = AppScreen.SHOPPING_LIST
        }
    }

    companion object {
        const val EXTRA_OPEN_SHOPPING_LIST = "com.foodtracker.android.EXTRA_OPEN_SHOPPING_LIST"
    }
}
