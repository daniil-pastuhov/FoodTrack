package com.foodtracker.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderDialog
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderPreview

/**
 * Google Play's "prominent disclosure" for background location: shown in the app, right before
 * the system's "Allow all the time" request, saying what location is used for and that it is used
 * while the app is closed. Play rejects background location without it, and its reviewers look
 * for it in the declaration video. [onContinue] goes on to the system request; [onDismiss]
 * skips it, and shop reminders then only work while the app is open.
 */
@Composable
fun BackgroundLocationDisclosureDialog(onContinue: () -> Unit, onDismiss: () -> Unit) {
    LarderDialog(
        title = stringResource(R.string.background_location_disclosure_title),
        text = stringResource(R.string.background_location_disclosure_message),
        confirmLabel = stringResource(R.string.background_location_disclosure_continue),
        onConfirm = onContinue,
        onDismissRequest = onDismiss,
        dismissLabel = stringResource(R.string.background_location_disclosure_not_now),
        icon = LarderIcons.MapPin
    )
}

@PreviewLightDark
@Composable
internal fun BackgroundLocationDisclosureDialogPreview() = LarderPreview {
    BackgroundLocationDisclosureDialog({}, {})
}
