package com.foodtracker.android.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.model.MAX_ITEM_QUANTITY

/** Lines digits up, for counts and quantities. Merge into a text style. */
internal val LarderTabularNumbers = TextStyle(fontFeatureSettings = "tnum")

/**
 * A filter or single-choice chip: 32dp to look at, 48dp to tap, which is why it takes 48dp of
 * layout height. In a wrapped row, no extra row spacing is needed. [count] is the number after
 * the label, as on Home's status chips.
 */
@Composable
fun LarderChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: LarderIcons? = null,
    count: String? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null
) {
    LarderChipImpl(label, selected, onClick, modifier, leadingIcon, count, enabled, interactionSource, false)
}

@Composable
private fun LarderChipImpl(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    leadingIcon: LarderIcons?,
    count: String?,
    enabled: Boolean,
    interactionSource: MutableInteractionSource?,
    forceFocus: Boolean
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val content = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        selected -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = selected,
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onClick() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .focusRing(focused || forceFocus, LarderTheme.colors.focusRing, CircleShape)
                .clip(CircleShape)
                .indication(source, ripple())
                .then(
                    if (selected) {
                        Modifier.background(scheme.primaryContainer)
                    } else {
                        Modifier.border(1.dp, scheme.outlineVariant, CircleShape)
                    }
                )
                .heightIn(min = 32.dp)
                .padding(horizontal = LarderSpace.s3),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingIcon?.let { LarderIcon(it, null, size = 16.dp, tint = content) }
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            count?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge.merge(LarderTabularNumbers),
                    color = content,
                    maxLines = 1
                )
            }
        }
    }
}

/** The round tick that marks something done: a 24dp ring on a 48dp target. Off is an empty ring.
 *  [contentDescription] is the thing being ticked, such as the product's name. */
@Composable
fun LarderTick(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val ring = scheme.outline
    val fill = scheme.primary
    val mark = scheme.onPrimary
    Box(
        modifier = modifier
            .size(LarderSize.touch)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp),
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            if (checked) {
                drawCircle(fill)
                // Lucide's check at the design's 16dp and stroke of 3, centred in the ring.
                val unit = 16.dp.toPx() / 24f
                val inset = (size.width - 16.dp.toPx()) / 2f
                val path = Path().apply {
                    moveTo(inset + 20 * unit, inset + 6 * unit)
                    lineTo(inset + 9 * unit, inset + 17 * unit)
                    lineTo(inset + 4 * unit, inset + 12 * unit)
                }
                drawPath(
                    path,
                    mark,
                    style = Stroke(3 * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            } else {
                val stroke = 2.dp.toPx()
                drawCircle(ring, radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
            }
        }
    }
}

/** Material's switch in Larder colours. It changes a setting straight away. Give it a
 *  [contentDescription] unless it sits in a row whose text labels it. */
@Composable
fun LarderSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { contentDescription?.let { this.contentDescription = it } },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = scheme.primary,
            uncheckedThumbColor = scheme.outline,
            uncheckedTrackColor = scheme.surfaceContainerHigh,
            uncheckedBorderColor = scheme.outline
        )
    )
}

/** A quantity stepper. The buttons turn off at [min] and [max]. */
@Composable
fun LarderStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = MAX_ITEM_QUANTITY,
    decreaseDescription: String = stringResource(R.string.quantity_decrease_content_description),
    increaseDescription: String = stringResource(R.string.quantity_increase_content_description)
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.border(1.dp, scheme.outline, CircleShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIconButton(
            icon = LarderIcons.Minus,
            contentDescription = decreaseDescription,
            onClick = { onValueChange((value - 1).coerceAtLeast(min)) },
            enabled = value > min,
            tint = scheme.primary
        )
        Text(
            text = value.toString(),
            modifier = Modifier.widthIn(min = LarderSpace.s8),
            style = MaterialTheme.typography.titleMedium.merge(LarderTabularNumbers),
            color = scheme.onSurface,
            textAlign = TextAlign.Center
        )
        LarderIconButton(
            icon = LarderIcons.Plus,
            contentDescription = increaseDescription,
            onClick = { onValueChange((value + 1).coerceAtMost(max)) },
            enabled = value < max,
            tint = scheme.primary
        )
    }
}

/** One choice out of a few. 40dp to look at and 48dp to tap, so it takes 48dp of height. */
@Composable
fun <T> LarderSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val outline = scheme.outline
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .drawBehind {
                val inset = LarderSpace.s1.toPx()
                val height = size.height - inset * 2
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(0f, inset),
                    size = Size(size.width, height),
                    cornerRadius = CornerRadius(height / 2f),
                    style = Stroke(1.dp.toPx())
                )
            }
            .selectableGroup()
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val startPercent = if (index == 0) 50 else 0
            val endPercent = if (index == options.lastIndex) 50 else 0
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxHeight()
                    .heightIn(min = LarderSize.touch)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) }
                    )
                    .padding(vertical = LarderSpace.s1)
                    .clip(RoundedCornerShape(startPercent, endPercent, endPercent, startPercent))
                    .background(if (isSelected) scheme.primaryContainer else Color.Transparent)
                    .padding(horizontal = LarderSpace.s4),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** A small count, such as the shopping list's on the nav. Shows 99+ above 99. Callers put the
 *  real number in the surrounding content description. */
@Composable
fun LarderBadge(count: Int, modifier: Modifier = Modifier, compact: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val height = if (compact) 16.dp else 20.dp
    val style = MaterialTheme.typography.labelMedium.merge(LarderTabularNumbers)
    Box(
        modifier = modifier
            .heightIn(min = height)
            .widthIn(min = height)
            .background(scheme.primary, CircleShape)
            .padding(horizontal = if (compact) LarderSpace.s1 else 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = if (compact) style.copy(fontSize = 10.sp, lineHeight = 14.sp) else style,
            color = scheme.onPrimary,
            maxLines = 1
        )
    }
}

@PreviewLightDark
@Composable
internal fun LarderChipPreview() = LarderPreview {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        LarderChip("Fridge", selected = true, onClick = {})
        LarderChip("Freezer", selected = false, onClick = {})
        LarderChip("Soon", selected = false, onClick = {}, count = "4")
        LarderChip("Pantry", selected = false, onClick = {}, enabled = false)
        LarderChipImpl("Cellar", false, {}, Modifier, null, null, true, null, forceFocus = true)
    }
}

@PreviewLightDark
@Composable
internal fun LarderTickAndSwitchPreview() = LarderPreview {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderTick(false, {}, "Oat milk")
        LarderTick(true, {}, "Eggs")
        LarderSwitch(true, {}, contentDescription = "Remind me")
        LarderSwitch(false, {}, contentDescription = "Essential")
        LarderSwitch(true, {}, enabled = false)
    }
}

@PreviewLightDark
@Composable
internal fun LarderStepperPreview() = LarderPreview {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
    ) {
        LarderStepper(2, {})
        LarderStepper(1, {})
        LarderStepper(99, {})
    }
}

@PreviewLightDark
@Composable
internal fun LarderSegmentedPreview() = LarderPreview {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        LarderSegmented(listOf("Each item", "Summary", "Both"), "Each item", {}, { it })
        LarderSegmented(listOf("Light", "Dark"), "Dark", {}, { it })
    }
}

@PreviewLightDark
@Composable
internal fun LarderBadgePreview() = LarderPreview {
    Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
        LarderBadge(5)
        LarderBadge(120)
        LarderBadge(5, compact = true)
    }
}
