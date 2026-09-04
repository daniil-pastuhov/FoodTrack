package com.foodtracker.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.foodtracker.android.ui.screens.AddEditItemScreen
import com.foodtracker.android.ui.screens.PantryDashboardScreen
import com.foodtracker.android.ui.screens.ScannerScreen
import com.foodtracker.android.ui.screens.SettingsScreen
import com.foodtracker.android.ui.theme.FoodTrackerTheme
import com.foodtracker.model.AppTheme
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate

enum class AppScreen {
    DASHBOARD,
    SCANNER,
    ADD_EDIT,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as FoodTrackerApp
        val stateHolder = app.stateHolder

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

                    when (currentScreen) {
                        AppScreen.DASHBOARD -> {
                            PantryDashboardScreen(
                                stateHolder = stateHolder,
                                onNavigateToScanner = {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                                        == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        currentScreen = AppScreen.SCANNER
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                onNavigateToAddEdit = { item ->
                                    editingItem = item
                                    prefillBarcode = null
                                    prefillName = null
                                    prefillBrand = null
                                    prefillImageUrl = null
                                    prefillExpiryDate = null
                                    currentScreen = AppScreen.ADD_EDIT
                                },
                                onNavigateToSettings = {
                                    currentScreen = AppScreen.SETTINGS
                                },
                                onDeleteItem = { id ->
                                    com.foodtracker.android.notification.NotificationHelper.cancelNotification(this@MainActivity, id)
                                    stateHolder.deleteItem(id)
                                }
                            )
                        }

                        AppScreen.SCANNER -> {
                            ScannerScreen(
                                stateHolder = stateHolder,
                                onNavigateBack = {
                                    stateHolder.clearScanState()
                                    currentScreen = AppScreen.DASHBOARD
                                },
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
                                onNavigateBack = {
                                    currentScreen = AppScreen.DASHBOARD
                                },
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
                                onNavigateBack = {
                                    currentScreen = AppScreen.DASHBOARD
                                },
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
