package com.foodtracker.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandPrimary

/**
 * Google Play's "prominent disclosure" for background location: shown in the app, right before
 * the system's "Allow all the time" request, saying what location is used for and that it is used
 * while the app is closed. Play rejects background location without it, and its reviewers look
 * for it in the declaration video. [onContinue] goes on to the system request; [onDismiss]
 * skips it, and shop reminders then only work while the app is open.
 */
@Composable
fun BackgroundLocationDisclosureDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = BrandPrimary) },
        title = { Text(stringResource(R.string.background_location_disclosure_title)) },
        text = { Text(stringResource(R.string.background_location_disclosure_message)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.background_location_disclosure_continue), color = BrandPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.background_location_disclosure_not_now))
            }
        }
    )
}
