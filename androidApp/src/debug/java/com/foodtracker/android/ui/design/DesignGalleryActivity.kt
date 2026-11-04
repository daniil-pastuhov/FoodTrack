package com.foodtracker.android.ui.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier

/**
 * Every component state on a real device, in debug builds only, since previews need Android
 * Studio. Follows the system's dark mode.
 *
 *     adb shell am start -n com.foodtracker.android/.ui.design.DesignGalleryActivity
 *     adb shell am start -n com.foodtracker.android/.ui.design.DesignGalleryActivity --es overlay sheet
 *
 * `overlay` shows one of the things that can't sit in a list: dialog, sheet or date.
 */
class DesignGalleryActivity : ComponentActivity() {

    private val sections = listOf<@androidx.compose.runtime.Composable () -> Unit>(
        { LarderButtonStatesPreview() },
        { LarderButtonStylesPreview() },
        { LarderIconButtonPreview() },
        { LarderTextFieldPreview() },
        { LarderChipPreview() },
        { LarderTickAndSwitchPreview() },
        { LarderStepperPreview() },
        { LarderSegmentedPreview() },
        { LarderBadgePreview() },
        { StatusChipPreview() },
        { PantryRowPreview() },
        { LarderSwipeRowPreview() },
        { LarderGroupPreview() },
        { LarderCardPreview() },
        { LedeHeaderPreview() },
        { LarderTopBarPreview() },
        { LarderCalloutPreview() },
        { LarderSnackbarPreview() },
        { EmptyStatePreview() },
        { ScreenErrorPreview() },
        { LarderLoadingPreview() },
        { FloatingNavPreview() },
        { ScanFabPreview() },
        { LarderIconsPreview() },
        { LarderIllustrationsPreview() }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val overlay = intent.getStringExtra("overlay")
        setContent {
            when (overlay) {
                "dialog" -> LarderDialogPreview()
                "sheet" -> LarderSheetPreview()
                "date" -> LarderDatePickerPreview()
                else -> LarderPreview {
                    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding()) {
                        items(sections.size) { index -> sections[index]() }
                    }
                }
            }
        }
    }
}
