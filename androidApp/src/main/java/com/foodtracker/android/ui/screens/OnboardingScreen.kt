package com.foodtracker.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCard
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderGroup
import com.foodtracker.android.ui.design.LarderIcon
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderListItem
import com.foodtracker.android.ui.design.LarderPreview
import com.foodtracker.android.ui.design.LarderThumb
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme

/** `.ob-col`: one centred column on every width, so tablets don't stretch it. */
private val OnboardingColumnWidth = 480.dp
private val OnboardingMarkSize = 72.dp

private val OnboardingFlowSaver = listSaver<OnboardingFlow, Any>(
    save = { listOf(it.step.name, it.remindersDeclined, it.cameraDeclined) },
    restore = {
        OnboardingFlow(OnboardingStep.valueOf(it[0] as String), it[1] as Boolean, it[2] as Boolean)
    }
)

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun onboardingAsks(context: Context) = OnboardingAsks(
    notifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS),
    camera = !hasPermission(context, Manifest.permission.CAMERA)
)

/**
 * The first-run flow: each permission is explained just before the system asks, and nothing
 * depends on the answer. [onFinished] says where to go next.
 *
 * The camera is asked here only because this is where it is explained; [ScannerScreen] still owns
 * every other case. Both use the multiple-permissions contract, so a request the system cancels
 * reads as unanswered in both and the scanner asks when it opens. An answer given here carries
 * over through the system's own record, so the scanner never asks a second time.
 */
@Composable
fun OnboardingScreen(onFinished: (OnboardingExit) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var flow by rememberSaveable(stateSaver = OnboardingFlowSaver) { mutableStateOf(OnboardingFlow()) }
    // A second request while one is up gets cancelled, and that answer would move the flow on
    // from under the prompt.
    var asking by rememberSaveable { mutableStateOf(false) }

    val notificationPrompt = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        asking = false
        flow = flow.notificationsAnswered(results[Manifest.permission.POST_NOTIFICATIONS], onboardingAsks(context))
    }
    val cameraPrompt = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        asking = false
        flow = flow.cameraAnswered(results[Manifest.permission.CAMERA])
    }

    BackHandler(enabled = flow.step != OnboardingStep.Welcome) {
        flow = flow.back(onboardingAsks(context))
    }

    Crossfade(targetState = flow.step, modifier = modifier, label = "onboardingStep") { step ->
        OnboardingStepContent(
            step = step,
            flow = flow,
            onStart = { flow = flow.started(onboardingAsks(context)) },
            onAllowNotifications = {
                if (!asking) {
                    asking = true
                    notificationPrompt.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            },
            onSkipNotifications = { flow = flow.notificationsAnswered(null, onboardingAsks(context)) },
            onAllowCamera = {
                if (!asking) {
                    asking = true
                    cameraPrompt.launch(arrayOf(Manifest.permission.CAMERA))
                }
            },
            onSkipCamera = { flow = flow.cameraAnswered(null) },
            onFinished = onFinished
        )
    }
}

@Composable
private fun OnboardingStepContent(
    step: OnboardingStep,
    flow: OnboardingFlow,
    onStart: () -> Unit,
    onAllowNotifications: () -> Unit,
    onSkipNotifications: () -> Unit,
    onAllowCamera: () -> Unit,
    onSkipCamera: () -> Unit,
    onFinished: (OnboardingExit) -> Unit
) {
    when (step) {
        OnboardingStep.Welcome -> WelcomeStep(onStart)
        OnboardingStep.Notifications -> NotificationsStep(onAllowNotifications, onSkipNotifications)
        OnboardingStep.Camera -> CameraStep(onAllowCamera, onSkipCamera)
        OnboardingStep.Ready -> ReadyStep(onFinished)
        OnboardingStep.Declined -> DeclinedStep(flow, onFinished)
    }
}

/** The column in the middle, scrolling when large text needs it, and the actions always in reach
 *  at the bottom. */
@Composable
private fun OnboardingPage(
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val visibleHeight = maxHeight
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = visibleHeight)
                    .padding(start = LarderSpace.s6, top = LarderSpace.s6, end = LarderSpace.s6, bottom = LarderSpace.s4),
                contentAlignment = Alignment.Center
            ) {
                Column(modifier = Modifier.widthIn(max = OnboardingColumnWidth).fillMaxWidth(), content = content)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = LarderSpace.s6, top = LarderSpace.s2, end = LarderSpace.s6, bottom = LarderSpace.s6),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.widthIn(max = OnboardingColumnWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(LarderSpace.s1),
                content = actions
            )
        }
    }
}

@Composable
private fun OnboardingTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = LarderSpace.s3).semantics { heading() },
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun OnboardingBody(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(bottom = LarderSpace.s5),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun OnboardingPrimary(text: String, onClick: () -> Unit) {
    LarderButton(text, onClick, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun OnboardingSecondary(text: String, onClick: () -> Unit) {
    LarderButton(text, onClick, modifier = Modifier.fillMaxWidth(), style = LarderButtonStyle.Text)
}

@Composable
private fun WelcomeStep(onStart: () -> Unit) {
    OnboardingPage(
        actions = { OnboardingPrimary(stringResource(R.string.onboarding_get_started), onStart) }
    ) {
        JarMark(Modifier.padding(bottom = LarderSpace.s5))
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.padding(bottom = LarderSpace.s2),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        OnboardingTitle(stringResource(R.string.onboarding_welcome_title))
        LarderCard(modifier = Modifier.fillMaxWidth().padding(top = LarderSpace.s2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
                LarderIcon(LarderIcons.CircleCheck, null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        text = stringResource(R.string.onboarding_private_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.onboarding_private_body),
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** The app's jar, drawn in theme colours from the design's 120-unit artwork. */
@Composable
private fun JarMark(modifier: Modifier = Modifier) {
    val colors = LarderTheme.colors
    Canvas(modifier = modifier.size(OnboardingMarkSize)) {
        val unit = size.width / 120f
        fun rect(x: Float, y: Float, width: Float, height: Float, radius: Float, color: Color) =
            drawRoundRect(
                color = color,
                topLeft = Offset(x * unit, y * unit),
                size = Size(width * unit, height * unit),
                cornerRadius = CornerRadius(radius * unit)
            )
        rect(0f, 0f, 120f, 120f, 27f, colors.primary)
        rect(36f, 38f, 48f, 56f, 10f, colors.onPrimary)
        rect(42f, 26f, 36f, 12f, 4f, colors.primaryContainer)
        rect(36f, 56f, 48f, 20f, 0f, colors.soonContainer)
        drawPath(
            path = Path().apply {
                moveTo(50f * unit, 66f * unit)
                lineTo(57f * unit, 73f * unit)
                lineTo(70f * unit, 59f * unit)
            },
            color = colors.primary,
            style = Stroke(width = 5f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun NotificationsStep(onAllow: () -> Unit, onSkip: () -> Unit) {
    OnboardingPage(
        actions = {
            OnboardingPrimary(stringResource(R.string.onboarding_notifications_allow), onAllow)
            OnboardingSecondary(stringResource(R.string.onboarding_not_now), onSkip)
        }
    ) {
        OnboardingTitle(stringResource(R.string.onboarding_notifications_title))
        OnboardingBody(stringResource(R.string.onboarding_notifications_body))
        SampleReminder()
    }
}

/** Shows what the permission buys, in place of an illustration. */
@Composable
private fun SampleReminder() {
    val shape = RoundedCornerShape(LarderRadius.lg)
    val source = stringResource(R.string.onboarding_sample_source, stringResource(R.string.app_name))
    val title = stringResource(R.string.onboarding_sample_title)
    val body = stringResource(R.string.onboarding_sample_body)
    val description = stringResource(R.string.onboarding_sample_description, title, body)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            LarderIcon(
                LarderIcons.Bell,
                null,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Column {
            Text(
                text = source,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CameraStep(onAllow: () -> Unit, onSkip: () -> Unit) {
    OnboardingPage(
        actions = {
            OnboardingPrimary(stringResource(R.string.onboarding_camera_allow), onAllow)
            OnboardingSecondary(stringResource(R.string.onboarding_not_now), onSkip)
        }
    ) {
        OnboardingTitle(stringResource(R.string.onboarding_camera_title))
        OnboardingBody(stringResource(R.string.onboarding_camera_body, stringResource(R.string.app_name)))
        LarderGroup {
            OnboardingRow(
                LarderIcons.ScanBarcode,
                stringResource(R.string.onboarding_camera_barcodes_title),
                stringResource(R.string.onboarding_camera_barcodes_body)
            )
            LarderDivider()
            OnboardingRow(
                LarderIcons.ScanText,
                stringResource(R.string.onboarding_camera_dates_title),
                stringResource(R.string.onboarding_camera_dates_body)
            )
        }
    }
}

@Composable
internal fun OnboardingRow(icon: LarderIcons, title: String, supporting: String) {
    LarderListItem(
        title = title,
        supporting = supporting,
        leading = { LarderThumb(imageUrl = null, fallbackIcon = icon) }
    )
}

@Composable
private fun ReadyStep(onFinished: (OnboardingExit) -> Unit) {
    OnboardingPage(
        actions = {
            OnboardingPrimary(stringResource(R.string.home_empty_scan)) { onFinished(OnboardingExit.Scanner) }
            OnboardingSecondary(stringResource(R.string.onboarding_skip)) { onFinished(OnboardingExit.Home) }
        }
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = LarderSpace.s5)
                .size(OnboardingMarkSize)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            LarderIcon(
                LarderIcons.ScanBarcode,
                null,
                size = 32.dp,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        OnboardingTitle(stringResource(R.string.onboarding_ready_title))
        OnboardingBody(stringResource(R.string.onboarding_ready_body))
    }
}

@Composable
private fun DeclinedStep(flow: OnboardingFlow, onFinished: (OnboardingExit) -> Unit) {
    OnboardingPage(
        actions = {
            val exit = flow.primaryExit
            OnboardingPrimary(
                stringResource(
                    if (exit == OnboardingExit.AddByHand) R.string.onboarding_add_by_hand else R.string.home_empty_scan
                )
            ) { onFinished(exit) }
            OnboardingSecondary(stringResource(R.string.onboarding_skip)) { onFinished(OnboardingExit.Home) }
        }
    ) {
        OnboardingTitle(stringResource(R.string.onboarding_declined_title))
        OnboardingBody(stringResource(R.string.onboarding_declined_body))
        LarderGroup {
            if (flow.remindersDeclined) {
                OnboardingRow(
                    LarderIcons.BellOff,
                    stringResource(R.string.onboarding_declined_reminders_title),
                    stringResource(R.string.onboarding_declined_reminders_body)
                )
            }
            if (flow.remindersDeclined && flow.cameraDeclined) LarderDivider()
            if (flow.cameraDeclined) {
                OnboardingRow(
                    LarderIcons.CameraOff,
                    stringResource(R.string.onboarding_declined_camera_title),
                    stringResource(R.string.onboarding_declined_camera_body)
                )
            }
        }
    }
}

@Composable
private fun OnboardingStepPreview(step: OnboardingStep, flow: OnboardingFlow = OnboardingFlow(step)) =
    LarderPreview {
        Box(Modifier.height(720.dp)) {
            OnboardingStepContent(step, flow, {}, {}, {}, {}, {}, {})
        }
    }

@PreviewLightDark
@Composable
internal fun OnboardingWelcomePreview() = OnboardingStepPreview(OnboardingStep.Welcome)

@PreviewLightDark
@Composable
internal fun OnboardingNotificationsPreview() = OnboardingStepPreview(OnboardingStep.Notifications)

@PreviewLightDark
@Composable
internal fun OnboardingCameraPreview() = OnboardingStepPreview(OnboardingStep.Camera)

@PreviewLightDark
@Composable
internal fun OnboardingReadyPreview() = OnboardingStepPreview(OnboardingStep.Ready)

@PreviewLightDark
@Composable
internal fun OnboardingDeclinedBothPreview() = OnboardingStepPreview(
    OnboardingStep.Declined,
    OnboardingFlow(OnboardingStep.Declined, remindersDeclined = true, cameraDeclined = true)
)

@PreviewLightDark
@Composable
internal fun OnboardingDeclinedRemindersPreview() = OnboardingStepPreview(
    OnboardingStep.Declined,
    OnboardingFlow(OnboardingStep.Declined, remindersDeclined = true)
)
