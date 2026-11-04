package com.foodtracker.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme

enum class LarderButtonStyle { Primary, Secondary, Outline, Text, Danger, DangerText }

/** A state a preview can pin, since nothing is hovering or pressing it there. */
internal enum class ForcedInteraction { Hover, Focus, Pressed }

/** The 2dp ring keyboard focus draws, 2dp clear of the control. */
internal fun Modifier.focusRing(visible: Boolean, color: Color, shape: Shape): Modifier =
    if (!visible) {
        this
    } else {
        drawBehind {
            val gap = 3.dp.toPx()
            val outline = shape.createOutline(
                Size(size.width + gap * 2, size.height + gap * 2),
                layoutDirection,
                this
            )
            translate(-gap, -gap) { drawOutline(outline, color, style = Stroke(2.dp.toPx())) }
        }
    }

/**
 * The app's button. One [LarderButtonStyle.Primary] per view; [LarderButtonStyle.Danger] is for
 * confirmations only. Labels are verbs that say what happens. For a full-width button, pass
 * `Modifier.fillMaxWidth()`.
 *
 * [loading] keeps the button's width and ignores taps, so nothing jumps while work runs.
 */
@Composable
fun LarderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LarderButtonStyle = LarderButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: LarderIcons? = null,
    interactionSource: MutableInteractionSource? = null
) {
    LarderButtonImpl(text, onClick, modifier, style, enabled, loading, leadingIcon, interactionSource, null)
}

@Composable
private fun LarderButtonImpl(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    style: LarderButtonStyle,
    enabled: Boolean,
    loading: Boolean,
    leadingIcon: LarderIcons?,
    interactionSource: MutableInteractionSource?,
    forced: ForcedInteraction?
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()

    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when (style) {
        LarderButtonStyle.Primary -> scheme.primary to scheme.onPrimary
        LarderButtonStyle.Secondary -> scheme.primaryContainer to scheme.onPrimaryContainer
        LarderButtonStyle.Outline, LarderButtonStyle.Text -> Color.Transparent to scheme.primary
        LarderButtonStyle.Danger -> scheme.error to scheme.onError
        LarderButtonStyle.DangerText -> Color.Transparent to scheme.error
    }
    val overlay = when {
        pressed || forced == ForcedInteraction.Pressed -> 0.14f
        hovered || forced == ForcedInteraction.Hover -> 0.08f
        else -> 0f
    }
    val container = when {
        overlay == 0f -> background
        background.alpha == 0f -> foreground.copy(alpha = overlay)
        else -> foreground.copy(alpha = overlay).compositeOver(background)
    }
    val horizontalPadding = when (style) {
        LarderButtonStyle.Text, LarderButtonStyle.DangerText -> LarderSpace.s3
        else -> LarderSpace.s5
    }
    val loadingLabel = stringResource(R.string.loading_state_description)

    // The press tint above stands in for the ripple, as the design specifies it.
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        Button(
            onClick = { if (!loading) onClick() },
            modifier = modifier
                .heightIn(min = LarderSize.control)
                .widthIn(min = LarderSize.touch)
                .focusRing(
                    visible = focused || forced == ForcedInteraction.Focus,
                    color = LarderTheme.colors.focusRing,
                    shape = CircleShape
                )
                .semantics { if (loading) stateDescription = loadingLabel },
            enabled = enabled,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = foreground,
                disabledContainerColor = scheme.onSurface.copy(alpha = 0.12f),
                disabledContentColor = scheme.onSurface.copy(alpha = 0.38f)
            ),
            elevation = null,
            border = if (style == LarderButtonStyle.Outline && enabled) {
                BorderStroke(1.dp, scheme.outline)
            } else {
                null
            },
            contentPadding = PaddingValues(horizontal = horizontalPadding),
            interactionSource = source
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.alpha(if (loading) 0f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leadingIcon?.let { LarderIcon(it, contentDescription = null) }
                    Text(text = text, style = MaterialTheme.typography.labelLarge)
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = foreground,
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

/** A 48dp round icon button. [contentDescription] is required: an icon alone says nothing to a
 *  screen reader. */
@Composable
fun LarderIconButton(
    icon: LarderIcons,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    interactionSource: MutableInteractionSource? = null
) {
    LarderIconButtonImpl(icon, contentDescription, onClick, modifier, enabled, tint, interactionSource, false)
}

@Composable
private fun LarderIconButtonImpl(
    icon: LarderIcons,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    tint: Color,
    interactionSource: MutableInteractionSource?,
    forceFocus: Boolean
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(LarderSize.touch)
            .focusRing(focused || forceFocus, LarderTheme.colors.focusRing, CircleShape),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = tint,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        interactionSource = source
    ) {
        LarderIcon(icon, contentDescription)
    }
}

@PreviewLightDark
@Composable
internal fun LarderButtonStatesPreview() = LarderPreview {
    val label = "Save to Fridge"
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
    ) {
        LarderButton(label, {})
        ForcedInteraction.entries.forEach { forced ->
            LarderButtonImpl(label, {}, Modifier, LarderButtonStyle.Primary, true, false, null, null, forced)
        }
        LarderButton(label, {}, loading = true)
        LarderButton(label, {}, enabled = false)
    }
}

@PreviewLightDark
@Composable
internal fun LarderButtonStylesPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        LarderButton("Add one more", {}, style = LarderButtonStyle.Secondary)
        LarderButton("Keep separate", {}, style = LarderButtonStyle.Outline)
        LarderButton("Undo", {}, style = LarderButtonStyle.Text)
        LarderButton("Clear all data", {}, style = LarderButtonStyle.Danger)
        LarderButton("Delete", {}, style = LarderButtonStyle.DangerText, leadingIcon = LarderIcons.Trash2)
        LarderButton("Use one", {}, leadingIcon = LarderIcons.CircleMinus, modifier = Modifier.fillMaxWidth())
        LarderButton("Keep separate", {}, style = LarderButtonStyle.Outline, enabled = false)
    }
}

@PreviewLightDark
@Composable
internal fun LarderIconButtonPreview() = LarderPreview {
    Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        LarderIconButton(LarderIcons.Search, "Search", {})
        LarderIconButtonImpl(
            LarderIcons.Search,
            "Search",
            {},
            Modifier,
            true,
            MaterialTheme.colorScheme.onSurfaceVariant,
            null,
            forceFocus = true
        )
        LarderIconButton(LarderIcons.Search, "Search", {}, enabled = false)
    }
}
