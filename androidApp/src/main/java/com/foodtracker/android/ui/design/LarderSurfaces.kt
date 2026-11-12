package com.foodtracker.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.ui.theme.larderShadow

/** Previews and the debug gallery: the theme for the current mode, on the page colour. */
@Composable
internal fun LarderPreview(content: @Composable ColumnScope.() -> Unit) {
    LarderTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(LarderSpace.s4),
                verticalArrangement = Arrangement.spacedBy(LarderSpace.s3),
                content = content
            )
        }
    }
}

/** A hairline between rows of a [LarderGroup]. */
@Composable
fun LarderDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * Rows on one sheet of paper, for a known handful of rows. Put a [LarderDivider] between them.
 * For a lazy list, use [larderGroupItem] on each row instead, so rows stay lazy.
 */
@Composable
fun LarderGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(LarderRadius.lg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        content = content
    )
}

/** Where a lazy row falls in its group, for [larderGroupItem]. */
enum class LarderGroupPosition {
    Single, First, Middle, Last;

    companion object {
        fun of(index: Int, count: Int): LarderGroupPosition = when {
            count <= 1 -> Single
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

/**
 * Draws one row of a [LarderGroup] inside a lazy list: the page, the rounded ends of the outline
 * and the hairline above it. Use [LarderGroupPosition.of] to get [position].
 */
@Composable
fun Modifier.larderGroupItem(position: LarderGroupPosition): Modifier {
    val fill = MaterialTheme.colorScheme.surface
    val line = MaterialTheme.colorScheme.outlineVariant
    val roundTop = position == LarderGroupPosition.Single || position == LarderGroupPosition.First
    val roundBottom = position == LarderGroupPosition.Single || position == LarderGroupPosition.Last
    val top = if (roundTop) LarderRadius.lg else 0.dp
    val bottom = if (roundBottom) LarderRadius.lg else 0.dp
    return clip(RoundedCornerShape(top, top, bottom, bottom))
        .background(fill)
        .drawWithContent {
            drawContent()
            val stroke = 1.dp.toPx()
            val radius = LarderRadius.lg.toPx()
            // The outline runs past an open end, where the clip above cuts it off square.
            val overrun = radius * 2
            val startY = if (roundTop) stroke / 2 else -overrun
            val endY = if (roundBottom) size.height - stroke / 2 else size.height + overrun
            drawRoundRect(
                color = line,
                topLeft = Offset(stroke / 2, startY),
                size = Size(size.width - stroke, endY - startY),
                cornerRadius = CornerRadius(radius),
                style = Stroke(stroke)
            )
            if (!roundTop) {
                drawLine(line, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
            }
        }
}

/**
 * The label above a group of rows. [first] drops the top margin for the first one on a screen.
 * [trailing] holds a small action, such as a text button.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (first) LarderSpace.s1 else LarderSpace.s4, bottom = LarderSpace.s2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        trailing?.invoke(this)
    }
}

/** A flat card on the surface-container tone; [elevated] lifts it onto the surface colour with a
 *  light shadow. Give it [onClick] only if the whole card is one action. */
@Composable
fun LarderCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(LarderRadius.lg)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .then(if (elevated) Modifier.larderShadow(LarderTheme.shadows.level1, shape) else Modifier)
            .clip(shape)
            .background(if (elevated) scheme.surface else scheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(LarderSpace.s4),
        content = content
    )
}

/**
 * A plain row for a [LarderGroup]: settings, lists of names, anything that isn't a product.
 * At least 56dp tall. The whole row is one target when [onClick] is set.
 */
@Composable
fun LarderListItem(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * The tab screens' header: the brand bar with its [actions], then the sentence that answers the
 * screen's question in the display style. [below] sits under it, usually the chips. It pads for
 * the status bar itself.
 */
@Composable
fun LedeHeader(
    lede: String,
    modifier: Modifier = Modifier,
    brand: String = stringResource(R.string.app_name),
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3)
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = LarderSize.touch)
                .bleedEnd(LarderSpace.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = brand,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            actions()
        }
        Text(
            text = lede,
            modifier = Modifier
                .padding(top = LarderSpace.s1, bottom = if (below == null) 0.dp else LarderSpace.s3)
                .semantics { heading() },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        below?.invoke(this)
    }
}

/** Runs [bleed] past the parent's end edge and no further at the start, so an action's touch
 *  target can overhang the padding while the brand lines up with the lede below. */
private fun Modifier.bleedEnd(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = extra))
    layout(placeable.width - extra, placeable.height) { placeable.placeRelative(0, 0) }
}

/** The bar on pushed screens such as Add item: back, a serif title, then [actions]. On the page
 *  colour, so it reads as part of the screen. Leave [onBack] null for no back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LarderTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backDescription: String = stringResource(R.string.back_content_description),
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
        navigationIcon = {
            onBack?.let { LarderIconButton(LarderIcons.ArrowLeft, backDescription, it) }
        },
        actions = actions,
        expandedHeight = 64.dp,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

@PreviewLightDark
@Composable
internal fun LarderGroupPreview() = LarderPreview {
    SectionHeader("Use first", first = true)
    LarderGroup {
        LarderListItem("Reminders", supporting = "Each item, 1 day before", onClick = {})
        LarderDivider()
        LarderListItem(
            title = "Remember last location",
            trailing = { LarderSwitch(true, {}) }
        )
        LarderDivider()
        LarderListItem(
            title = "Fridge",
            leading = { LarderIcon(LarderIcons.Refrigerator, null) },
            trailing = { LarderIcon(LarderIcons.ChevronRight, null) },
            onClick = {}
        )
    }
    SectionHeader("In a lazy list", trailing = { LarderButton("Clear", {}, style = LarderButtonStyle.Text) })
    Column {
        repeat(3) { index ->
            LarderListItem(
                title = "Row ${index + 1}",
                modifier = Modifier.larderGroupItem(LarderGroupPosition.of(index, 3))
            )
        }
    }
    Box(Modifier.height(LarderSpace.s1))
    LarderListItem("Only row", modifier = Modifier.larderGroupItem(LarderGroupPosition.Single))
}

@PreviewLightDark
@Composable
internal fun LarderCardPreview() = LarderPreview {
    LarderCard(Modifier.fillMaxWidth()) {
        Text("Shopping reminders", style = MaterialTheme.typography.titleLarge)
        Text(
            "Shows your list when you stop at a shop you saved.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = LarderSpace.s1)
        )
    }
    LarderCard(Modifier.fillMaxWidth(), elevated = true, onClick = {}) {
        Text("Elevated and tappable", style = MaterialTheme.typography.titleLarge)
    }
}

@PreviewLightDark
@Composable
internal fun LedeHeaderPreview() = LarderPreview {
    LedeHeader(
        lede = "Three things to use by tomorrow",
        brand = "BestBefore",
        actions = {
            LarderIconButton(LarderIcons.Search, "Search", {})
            LarderIconButton(LarderIcons.SlidersHorizontal, "Filters", {})
        },
        below = {
            Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2)) {
                LarderChip("All", selected = true, onClick = {}, count = "13")
                LarderChip("Soon", selected = false, onClick = {}, count = "4")
            }
        }
    )
    LedeHeader(lede = "Срокоед · Греческий йогурт", brand = "Срокоед")
    LedeHeader(lede = "Matvakt · Blåbær og rømme", brand = "Matvakt")
}

@PreviewLightDark
@Composable
internal fun LarderTopBarPreview() = LarderPreview {
    LarderTopBar(
        title = "Add item",
        onBack = {},
        actions = { LarderIconButton(LarderIcons.ScanBarcode, "Scan", {}) }
    )
}
