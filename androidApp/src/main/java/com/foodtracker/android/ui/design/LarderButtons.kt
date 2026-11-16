package com.foodtracker.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderRadius
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
    interactionSource: MutableInteractionSource? = null,
    /** Set to 1 to truncate a long label rather than wrap it. */
    maxLines: Int = Int.MAX_VALUE
) {
    LarderButtonImpl(text, onClick, modifier, style, enabled, loading, leadingIcon, interactionSource, null, maxLines)
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
    forced: ForcedInteraction?,
    maxLines: Int = Int.MAX_VALUE
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
            // The vertical padding keeps a wrapped label clear of the capsule's curve; at the usual
            // sizes it stays inside the minimum height, so those keep their height.
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = LarderSpace.s2),
            interactionSource = source
        ) {
            Box(contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier.alpha(if (loading) 0f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    leadingIcon?.let { LarderIcon(it, contentDescription = null) }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                        // Centred when a label too long for the whole width wraps between words.
                        textAlign = TextAlign.Center
                    )
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

/**
 * A form's primary action, the first and only filled button of its group (the Recipe tab's
 * Generate or Share): full width as the caller sizes it, at least 56dp high, a rounded rectangle
 * whose 28dp corners make it a pill at that height (never an oval when it grows), with [cost] as a
 * small outlined pill at the end ("Free", "5 recipes") that never wraps or shrinks. [subtitle] is a
 * quieter line under the label. Label and subtitle take up to two lines each beside the icon and the
 * pill; when they need more, or with font scale 1.5 and over, the icon goes and label, subtitle and
 * pill stack, centred. Drawn in the flow of the form, not floating. One element to a screen reader:
 * label, cost, subtitle.
 */
@Composable
fun LarderPrimaryAction(
    text: String,
    icon: LarderIcons,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cost: String? = null,
    enabled: Boolean = true,
    subtitle: String? = null
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val hovered by source.collectIsHoveredAsState()
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val overlay = when {
        pressed -> 0.14f
        hovered -> 0.08f
        else -> 0f
    }
    val container = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.12f)
        overlay == 0f -> scheme.primary
        else -> scheme.onPrimary.copy(alpha = overlay).compositeOver(scheme.primary)
    }
    val content = if (enabled) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f)
    val shape = RoundedCornerShape(PRIMARY_ACTION_RADIUS)
    val spoken = listOfNotNull(text, cost, subtitle).joinToString(", ")
    val labelStyle = MaterialTheme.typography.labelLarge
    val subtitleStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal)
    val costStyle = MaterialTheme.typography.labelSmall
    val horizontalPadding = LarderSpace.s5
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        BoxWithConstraints(modifier = modifier) {
            // Side by side when label and subtitle each fit in two lines beside the icon and the pill.
            val stacked = density.fontScale >= 1.5f || run {
                val pillWidth = cost?.let {
                    with(density) { measurer.measure(it, costStyle, maxLines = 1, softWrap = false).size.width.toDp() } + LarderSpace.s2 * 2 + 2.dp
                } ?: 0.dp
                val gaps = LarderSpace.s3 * (if (cost != null) 2 else 1)
                val textWidth = maxWidth - horizontalPadding * 2 - LarderIconSize.Default - gaps - pillWidth
                if (textWidth <= 0.dp) return@run true
                val px = with(density) { textWidth.roundToPx() }
                val fits = { s: String, style: TextStyle -> measurer.measure(s, style, constraints = Constraints(maxWidth = px)).lineCount <= 2 }
                !fits(text, labelStyle) || (subtitle != null && !fits(subtitle, subtitleStyle))
            }
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .focusRing(focused, LarderTheme.colors.focusRing, shape)
                    .semantics { contentDescription = spoken },
                enabled = enabled,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = content,
                    disabledContainerColor = container,
                    disabledContentColor = content
                ),
                elevation = null,
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = LarderSpace.s2),
                interactionSource = source
            ) {
                val pill: @Composable () -> Unit = {
                    cost?.let {
                        Text(
                            text = it,
                            modifier = Modifier
                                .border(1.dp, content, RoundedCornerShape(LarderRadius.xl))
                                .padding(horizontal = LarderSpace.s2, vertical = 2.dp),
                            style = costStyle,
                            color = content,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                val subColor = content.copy(alpha = content.alpha * 0.8f)
                if (stacked) {
                    Column(
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
                    ) {
                        Text(text = text, style = labelStyle, color = content, textAlign = TextAlign.Center)
                        subtitle?.let { Text(text = it, style = subtitleStyle, color = subColor, textAlign = TextAlign.Center) }
                        pill()
                    }
                } else {
                    Row(
                        modifier = Modifier.clearAndSetSemantics { },
                        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LarderIcon(icon, contentDescription = null, tint = content)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = text, style = labelStyle, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            subtitle?.let { Text(text = it, style = subtitleStyle, color = subColor, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        pill()
                    }
                }
            }
        }
    }
}

/** Half the primary action's 56dp minimum: a pill at that height, a rounded rectangle when taller. */
private val PRIMARY_ACTION_RADIUS = 28.dp

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
internal fun LarderPrimaryActionPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
        LarderPrimaryAction("Generate recipe", LarderIcons.Sparkles, {}, Modifier.fillMaxWidth(), cost = "1 recipe")
        LarderPrimaryAction("Generate on this device", LarderIcons.Smartphone, {}, Modifier.fillMaxWidth(), cost = "Free")
        LarderPrimaryAction("Share prompt", LarderIcons.Share2, {}, Modifier.fillMaxWidth())
        LarderPrimaryAction("Generate recipe", LarderIcons.Sparkles, {}, Modifier.fillMaxWidth(), cost = "1 recipe", subtitle = "Uses your taste profile")
        LarderPrimaryAction("Составить на этом устройстве с длинной подписью", LarderIcons.Smartphone, {}, Modifier.widthIn(max = 280.dp), cost = "Бесплатно", subtitle = "С учётом вашего вкусового профиля")
        LarderPrimaryAction("Generate on this device", LarderIcons.Smartphone, {}, Modifier.fillMaxWidth(), cost = "Free", enabled = false)
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
