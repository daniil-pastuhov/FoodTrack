package com.foodtracker.android.ui.screens

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.EmptyState
import com.foodtracker.android.ui.design.LarderButton

/** A stand-in for the first-run flow, so the route exists and leads somewhere. */
@Composable
fun OnboardingScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.onboarding_placeholder_title),
        modifier = modifier.safeDrawingPadding(),
        action = {
            LarderButton(stringResource(R.string.onboarding_placeholder_continue), onClick = onFinished)
        }
    )
}
