package com.foodtracker.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.foodtracker.android.ui.screens.AddEditItemScreen
import com.foodtracker.android.ui.screens.CalendarScreen
import com.foodtracker.android.ui.screens.PantryDashboardScreen
import com.foodtracker.android.ui.screens.ScannerScreen
import com.foodtracker.android.ui.screens.SettingsScreen
import com.foodtracker.android.ui.screens.ShoppingListScreen
import com.foodtracker.android.ui.theme.FoodTrackerTheme
import com.foodtracker.model.AppTheme
import com.foodtracker.model.PantryItem
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.CancellationException
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

// Material predictive-back visual treatment for the screen being swiped away: it shrinks
// slightly, shifts toward the swipe edge, and gains rounded corners as the gesture progresses.
private const val PREDICTIVE_BACK_SCALE_FACTOR = 0.1f
private const val PREDICTIVE_BACK_MAX_TRANSLATION_FRACTION = 0.05f
private val PREDICTIVE_BACK_MAX_CORNER_RADIUS_DP = 32.dp

class MainActivity : ComponentActivity() {

    private val stateHolder: PantryStateHolder by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    var currentScreen by remember { mutableStateOf(AppScreen.DASHBOARD) }
                    var editingItem by remember { mutableStateOf<PantryItem?>(null) }
                    var prefillBarcode by remember { mutableStateOf<String?>(null) }
                    var prefillName by remember { mutableStateOf<String?>(null) }
                    var prefillBrand by remember { mutableStateOf<String?>(null) }
                    var prefillImageUrl by remember { mutableStateOf<String?>(null) }
                    var prefillExpiryDate by remember { mutableStateOf<LocalDate?>(null) }

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
                    }

                    val navigateToDashboard: () -> Unit = {
                        currentScreen = AppScreen.DASHBOARD
                    }
                    val navigateBackFromScanner: () -> Unit = {
                        stateHolder.clearScanState()
                        currentScreen = AppScreen.DASHBOARD
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
                    val onNavigateToAddEdit: (PantryItem?) -> Unit = { item ->
                        editingItem = item
                        prefillBarcode = null
                        prefillName = null
                        prefillBrand = null
                        prefillImageUrl = null
                        prefillExpiryDate = null
                        currentScreen = AppScreen.ADD_EDIT
                    }
                    val onNavigateToSettingsScreen: () -> Unit = {
                        currentScreen = AppScreen.SETTINGS
                    }
                    val onNavigateToCalendarScreen: () -> Unit = {
                        currentScreen = AppScreen.CALENDAR
                    }
                    val onNavigateToShoppingListScreen: () -> Unit = {
                        currentScreen = AppScreen.SHOPPING_LIST
                    }
                    val onDeleteItemHandler: (String) -> Unit = { id ->
                        com.foodtracker.android.notification.NotificationHelper.cancelNotification(this@MainActivity, id)
                        stateHolder.deleteItem(id)
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
                                AppScreen.ADD_EDIT, AppScreen.SETTINGS, AppScreen.CALENDAR, AppScreen.SHOPPING_LIST -> navigateToDashboard()
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
                                onNavigateToScanner = {},
                                onNavigateToAddEdit = {},
                                onNavigateToSettings = {},
                                onNavigateToCalendar = {},
                                onNavigateToShoppingList = {}
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
                            when (currentScreen) {
                                AppScreen.DASHBOARD -> {
                                    PantryDashboardScreen(
                                        stateHolder = stateHolder,
                                        onNavigateToScanner = onNavigateToScanner,
                                        onNavigateToAddEdit = onNavigateToAddEdit,
                                        onNavigateToSettings = onNavigateToSettingsScreen,
                                        onNavigateToCalendar = onNavigateToCalendarScreen,
                                        onNavigateToShoppingList = onNavigateToShoppingListScreen,
                                        onDeleteItem = onDeleteItemHandler
                                    )
                                }

                                AppScreen.CALENDAR -> {
                                    CalendarScreen(
                                        stateHolder = stateHolder,
                                        onNavigateBack = navigateToDashboard
                                    )
                                }

                                AppScreen.SHOPPING_LIST -> {
                                    ShoppingListScreen(
                                        stateHolder = stateHolder,
                                        onNavigateBack = navigateToDashboard
                                    )
                                }

                                AppScreen.SCANNER -> {
                                    ScannerScreen(
                                        stateHolder = stateHolder,
                                        onNavigateBack = navigateBackFromScanner,
                                        onProceedToAddItem = { barcode, name, brand, imageUrl, expiryDate ->
                                            editingItem = null
                                            prefillBarcode = barcode
                                            prefillName = name
                                            prefillBrand = brand
                                            prefillImageUrl = imageUrl
                                            prefillExpiryDate = expiryDate
                                            currentScreen = AppScreen.ADD_EDIT
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
                                        settings = uiState.settings,
                                        onNavigateBack = navigateToDashboard,
                                        onSaveItem = { item ->
                                            com.foodtracker.android.notification.NotificationHelper.scheduleNotification(this@MainActivity, item)
                                            stateHolder.saveItem(item)
                                            currentScreen = AppScreen.DASHBOARD
                                        }
                                    )
                                }

                                AppScreen.SETTINGS -> {
                                    SettingsScreen(
                                        settings = uiState.settings,
                                        onNavigateBack = navigateToDashboard,
                                        onSave = { newSettings ->
                                            val notificationsChanged = newSettings.notifications != uiState.settings.notifications
                                            stateHolder.updateSettings(newSettings)
                                            if (notificationsChanged) {
                                                com.foodtracker.android.notification.NotificationHelper.rescheduleAll(
                                                    this@MainActivity,
                                                    stateHolder.uiState.value.items
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
                            }
                        }
                    }
                }
            }
        }
    }
}
