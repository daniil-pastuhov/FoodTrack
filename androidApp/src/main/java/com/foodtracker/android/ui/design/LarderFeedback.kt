package com.foodtracker.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.ui.theme.larderShadow
import kotlinx.datetime.LocalDate

private const val MILLIS_PER_DAY = 86_400_000L

enum class LarderCalloutKind { Info, Warning, Error, Success }

/**
 * A condition that lasts (T4), or the summary above a form (T2). Give it a [title], a [message]
 * or both; [content] adds anything else, such as a list of links. [icon] defaults to one that
 * suits [kind]. [onDismiss] adds a close button, for callouts that aren't safety messages.
 */
@Composable
fun LarderCallout(
    kind: LarderCalloutKind,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    icon: LarderIcons = when (kind) {
        LarderCalloutKind.Info -> LarderIcons.Info
        LarderCalloutKind.Warning -> LarderIcons.TriangleAlert
        LarderCalloutKind.Error -> LarderIcons.CircleAlert
        LarderCalloutKind.Success -> LarderIcons.CircleCheck
    },
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    dismissDescription: String = stringResource(R.string.clear_content_description),
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val colors = LarderTheme.colors
    val (container, onContainer) = when (kind) {
        LarderCalloutKind.Info -> colors.infoContainer to colors.onInfoContainer
        LarderCalloutKind.Warning -> colors.warningContainer to colors.onWarningContainer
        LarderCalloutKind.Error -> colors.errorContainer to colors.onErrorContainer
        LarderCalloutKind.Success -> colors.successContainer to colors.onSuccessContainer
    }
    CompositionLocalProvider(LocalContentColor provides onContainer) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(container, RoundedCornerShape(LarderRadius.md))
                .padding(
                    start = LarderSpace.s4,
                    end = if (onDismiss != null) LarderSpace.s1 else LarderSpace.s4,
                    top = LarderSpace.s3,
                    bottom = LarderSpace.s3
                )
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)
        ) {
            LarderIcon(icon, contentDescription = null, tint = onContainer)
            Column(modifier = Modifier.weight(1f)) {
                title?.let {
                    Text(text = it, style = MaterialTheme.typography.labelLarge, color = onContainer)
                }
                message?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium, color = onContainer)
                }
                content?.invoke(this)
                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .offset(x = -LarderSpace.s3)
                    ) {
                        Text(text = actionLabel, style = MaterialTheme.typography.labelLarge, color = onContainer)
                    }
                }
            }
            onDismiss?.let {
                LarderIconButton(
                    icon = LarderIcons.X,
                    contentDescription = dismissDescription,
                    onClick = it,
                    tint = onContainer,
                    modifier = Modifier.offset(y = -LarderSpace.s2)
                )
            }
        }
    }
}

/** An event (T3): what just happened, with [actionLabel] usually Undo. Most screens only need to
 *  call `showSnackbar` on the root's host, which draws this through the [SnackbarData] overload. */
@Composable
fun LarderSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(LarderRadius.md)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .larderShadow(LarderTheme.shadows.level3, shape)
            .background(scheme.inverseSurface, shape)
            .heightIn(min = 48.dp)
            .padding(start = LarderSpace.s4, end = LarderSpace.s2, top = LarderSpace.s2, bottom = LarderSpace.s2)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.inverseOnSurface
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 40.dp)) {
                Text(text = actionLabel, style = MaterialTheme.typography.labelLarge, color = scheme.inversePrimary)
            }
        }
    }
}

@Composable
fun LarderSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    LarderSnackbar(
        message = data.visuals.message,
        modifier = modifier,
        actionLabel = data.visuals.actionLabel,
        onAction = { data.performAction() }
    )
}

/**
 * A decision (T6): for irreversible actions and real choices only, because reversible ones get
 * Undo. [confirmLabel] says what will happen ("Clear all data"). [destructive] colours it as a
 * danger. [dismissLabel] is the way out ("Keep my pantry"). [content] goes under [text], for
 * dialogs that hold a field or a list.
 */
@Composable
fun LarderDialog(
    title: String,
    /** Null for a dialog whose only button is [dismissLabel], or whose [content] holds its choices. */
    confirmLabel: String?,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissLabel: String? = null,
    onDismiss: () -> Unit = onDismissRequest,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    /** Overrides the confirm button's style, for a dialog whose confirm isn't the main action. */
    confirmStyle: LarderButtonStyle? = null,
    icon: LarderIcons? = null,
    /** False when an outside tap could lose work. System back still calls [onDismissRequest]. */
    dismissOnClickOutside: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = DialogProperties(dismissOnClickOutside = dismissOnClickOutside),
        icon = icon?.let { { LarderIcon(it, contentDescription = null) } },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = if (text != null || content != null) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
                    text?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    content?.invoke(this)
                }
            }
        } else {
            null
        },
        confirmButton = {
            if (confirmLabel != null) {
                LarderButton(
                    text = confirmLabel,
                    onClick = onConfirm,
                    style = confirmStyle
                        ?: if (destructive) LarderButtonStyle.Danger else LarderButtonStyle.Primary,
                    enabled = confirmEnabled
                )
            }
        },
        dismissButton = dismissLabel?.let { label ->
            { LarderButton(text = label, onClick = onDismiss, style = LarderButtonStyle.Text) }
        },
        shape = RoundedCornerShape(LarderRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Material's modal bottom sheet, for filters, sort and product details. [title] is the serif
 * heading. Content scrolls by itself when it's taller than the sheet; to pin actions below a
 * scrolling part, turn that off with [scrollable] and lay it out yourself. On tablets it caps at
 * the design's 560dp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LarderSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetMaxWidth = 560.dp,
        shape = RoundedCornerShape(topStart = LarderRadius.xl, topEnd = LarderRadius.xl),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = LarderTheme.colors.scrim.copy(alpha = 0.4f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = LarderSpace.s3, bottom = LarderSpace.s4)
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = LarderSpace.s4)
        ) {
            title?.let {
                Text(
                    text = it,
                    modifier = Modifier
                        .padding(bottom = LarderSpace.s3)
                        .semantics { heading() },
                    style = MaterialTheme.typography.titleLarge
                )
            }
            content()
            Spacer(Modifier.padding(bottom = LarderSpace.s6))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** Material's date picker dialog in day-only terms: it takes and gives a [LocalDate], so callers
 *  never handle its UTC milliseconds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LarderDatePickerDialog(
    initialDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String = stringResource(R.string.date_picker_ok),
    dismissLabel: String = stringResource(R.string.date_picker_cancel)
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate?.let { it.toEpochDays() * MILLIS_PER_DAY }
    )
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        confirmButton = {
            LarderButton(
                text = confirmLabel,
                onClick = {
                    state.selectedDateMillis?.let {
                        onDateSelected(LocalDate.fromEpochDays(it / MILLIS_PER_DAY))
                    }
                },
                style = LarderButtonStyle.Text,
                enabled = state.selectedDateMillis != null
            )
        },
        dismissButton = {
            LarderButton(text = dismissLabel, onClick = onDismissRequest, style = LarderButtonStyle.Text)
        },
        shape = RoundedCornerShape(LarderRadius.xl)
    ) {
        DatePicker(state = state)
    }
}

/** Whole-area first-use state. [art] can be null for a dead end such as no search results, which
 *  shouldn't look like a welcome. [action] is usually a [LarderButton]. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    art: LarderArt? = LarderArt.Shelf,
    action: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = LarderSpace.s6, vertical = LarderSpace.s8),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        art?.let { LarderIllustration(it, Modifier.size(160.dp, 120.dp)) }
        CenteredHeading(title)
        message?.let { CenteredBody(it) }
        action?.invoke(this)
    }
}

/** Whole-area failure (T5): the screen's content can't be shown at all. [actions] holds the way
 *  forward, such as Open settings. */
@Composable
fun ScreenError(
    icon: LarderIcons,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = LarderSpace.s6, vertical = LarderSpace.s8),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LarderIcon(
            icon,
            contentDescription = null,
            size = 40.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CenteredHeading(title)
        message?.let { CenteredBody(it) }
        actions?.invoke(this)
    }
}

@Composable
private fun CenteredHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun CenteredBody(text: String) {
    Text(
        text = text,
        modifier = Modifier.widthIn(max = 320.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

/** For barcode lookups, receipts and image reads only: pantry data is local and never loads. */
@Composable
fun LarderSpinner(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    CircularProgressIndicator(modifier = modifier.size(32.dp), color = color, strokeWidth = 3.dp)
}

/** The thin indeterminate bar, under the same rule as [LarderSpinner]. */
@Composable
fun LarderProgressBar(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

@PreviewLightDark
@Composable
internal fun LarderCalloutPreview() = LarderPreview {
    LarderCallout(
        kind = LarderCalloutKind.Warning,
        icon = LarderIcons.BellOff,
        title = "Reminders are off",
        message = "Notifications are turned off for BestBefore in your phone's settings.",
        actionLabel = "Open settings",
        onAction = {},
        onDismiss = {}
    )
    LarderCallout(LarderCalloutKind.Info, title = "You're offline", message = "Add this one by hand.")
    LarderCallout(
        LarderCalloutKind.Error,
        title = "2 things to fix before saving",
        content = {
            Text("Product name", style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)
            Text("Best before", style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current)
        }
    )
    LarderCallout(LarderCalloutKind.Success, message = "Backup saved.")
}

@PreviewLightDark
@Composable
internal fun LarderSnackbarPreview() = LarderPreview {
    LarderSnackbar("Baby spinach removed", actionLabel = "Undo", onAction = {})
    LarderSnackbar("Mature cheddar saved to Fridge")
}

@PreviewLightDark
@Composable
internal fun LarderDialogPreview() = LarderPreview {
    LarderDialog(
        title = "Clear all pantry data?",
        text = "This removes all 13 items and cancels their reminders. You can't undo this.",
        confirmLabel = "Clear all data",
        onConfirm = {},
        dismissLabel = "Keep my pantry",
        onDismissRequest = {},
        destructive = true
    )
}

@PreviewLightDark
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun LarderSheetPreview() = LarderPreview {
    LarderSheet(onDismissRequest = {}, title = "Sort by") {
        LarderChip("Expiry date", selected = true, onClick = {})
        LarderChip("Name", selected = false, onClick = {})
    }
}

@PreviewLightDark
@Composable
internal fun LarderDatePickerPreview() = LarderPreview {
    LarderDatePickerDialog(LocalDate(2026, 11, 24), {}, {})
}

@PreviewLightDark
@Composable
internal fun EmptyStatePreview() = LarderPreview {
    Box(Modifier.heightIn(max = 420.dp)) {
        EmptyState(
            title = "Your shelves are empty",
            message = "Scan your first product and it will show up here, soonest date first.",
            action = { LarderButton("Scan a product", {}) }
        )
    }
    Box(Modifier.heightIn(max = 200.dp)) {
        EmptyState(title = "Nothing matches", message = "Try another word.", art = null)
    }
}

@PreviewLightDark
@Composable
internal fun ScreenErrorPreview() = LarderPreview {
    Box(Modifier.heightIn(max = 320.dp)) {
        ScreenError(
            icon = LarderIcons.CameraOff,
            title = "The camera is off",
            message = "BestBefore needs the camera to scan. You can still add food by hand.",
            actions = { LarderButton("Open settings", {}) }
        )
    }
}

@PreviewLightDark
@Composable
internal fun LarderLoadingPreview() = LarderPreview {
    LarderSpinner()
    LarderProgressBar()
}
