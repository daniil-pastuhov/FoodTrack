package com.foodtracker.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.ui.theme.larderShadow

/** The window width where two-pane layouts start. */
val LarderExpandedWidth = 700.dp

/** Whether a window this wide counts as expanded. See [isExpanded]. */
fun isExpandedWidth(windowWidth: Dp): Boolean = windowWidth >= LarderExpandedWidth

/**
 * True when the window is at least 700dp wide. Read from the window, not the screen, so split
 * screen and a folded foldable both get the phone layout. Two-pane layouts use this; there is no
 * navigation rail on any device.
 */
@Composable
fun isExpanded(): Boolean {
    val widthPx = LocalWindowInfo.current.containerSize.width
    return isExpandedWidth(with(LocalDensity.current) { widthPx.toDp() })
}

/** The four destinations on the [FloatingNav]. */
enum class LarderTab { Home, Recipe, Shopping, Settings }

/** The gap between floating things: the pill and the window edge, Scan and the pill. */
private val ShellGap = LarderSpace.s3

/** Content's breathing room above whatever floats over it. */
private val ContentGap = LarderSpace.s5

/** Where the shell's floating parts sit, measured up from the window's bottom edge. */
data class ShellClearance(
    /** The bottom padding a tab's scrolling content needs, so its last row clears the overlay. */
    val content: Dp,
    /** The bottom of the Scan button. */
    val scan: Dp,
    /** The bottom of a snackbar. */
    val snackbar: Dp
)

/**
 * The shell's vertical layout. On phones Scan stacks above the pill and snackbars above both. On
 * expanded windows Scan sits level with the pill at the bottom right, so only the pill counts.
 * Takes measured heights rather than constants, because both grow with font scale.
 *
 * @param bottomInset the system navigation bar
 * @param scanHeight the Scan button's height, 0 where it isn't shown
 */
fun shellClearance(
    bottomInset: Dp,
    pillHeight: Dp,
    scanHeight: Dp,
    expanded: Boolean
): ShellClearance {
    val pillTop = bottomInset + ShellGap + pillHeight
    val stackedScan = !expanded && scanHeight > 0.dp
    val overlayTop = when {
        stackedScan -> pillTop + ShellGap + scanHeight
        // Level with the pill, and as tall as the taller of the two (a pinned action at a large
        // font scale can outgrow the pill).
        expanded -> bottomInset + ShellGap + maxOf(pillHeight, scanHeight)
        else -> pillTop
    }
    return ShellClearance(
        content = overlayTop + ContentGap,
        scan = if (expanded) bottomInset + ShellGap else pillTop + ShellGap,
        snackbar = overlayTop + ShellGap
    )
}

/** The gap between a pinned action and the window's end edge on expanded windows. */
val PinnedActionEndGap = LarderSpace.s6

/**
 * Whether a pinned action [actionWidth] wide (without [PinnedActionEndGap]) fits level with the
 * centred pill, to its right with [ShellGap] to spare. When it does, it sits there, as Scan does
 * on expanded Home, and content only has to clear the pill's height; when it doesn't (a narrow
 * expanded window), it stacks above the pill. On a tablet the stacked action used to cover the
 * end of a two-column form at rest ("Copy prompt" and the hint under it).
 */
fun pinnedActionFitsBesidePill(windowWidth: Dp, pillWidth: Dp, actionWidth: Dp): Boolean =
    actionWidth > 0.dp && actionWidth + PinnedActionEndGap + ShellGap <= (windowWidth - pillWidth) / 2

/**
 * Lets a tab screen ask for the shell's floating parts to step aside. Requests are counted, so
 * two screens animating past each other can't undo one another. Use [ShellChromeEffect].
 */
@Stable
class ShellChrome {
    private var navHolds by mutableIntStateOf(0)
    private var scanHolds by mutableIntStateOf(0)

    val navHidden: Boolean get() = navHolds > 0
    val scanHidden: Boolean get() = scanHolds > 0

    internal fun hold(nav: Boolean, scan: Boolean) {
        if (nav) navHolds++
        if (scan) scanHolds++
    }

    internal fun release(nav: Boolean, scan: Boolean) {
        if (nav) navHolds--
        if (scan) scanHolds--
    }
}

/** Provided by the root around the tabs. */
val LocalShellChrome = staticCompositionLocalOf { ShellChrome() }

/**
 * Hides the pill and/or Scan for as long as this is in composition with those flags set: Home's
 * multi-select hides both on phones, and its empty state hides Scan because the body offers it.
 */
@Composable
fun ShellChromeEffect(hideNav: Boolean = false, hideScan: Boolean = false) {
    val chrome = LocalShellChrome.current
    DisposableEffect(chrome, hideNav, hideScan) {
        chrome.hold(hideNav, hideScan)
        onDispose { chrome.release(hideNav, hideScan) }
    }
}

private data class NavEntry(val tab: LarderTab, val icon: LarderIcons, val label: String)

/**
 * The floating pill, on every device: sized to its tabs, content scrolling beneath it, the page
 * showing at its sides. [shoppingCount] is the number of shopping-list entries, shown as a badge
 * above zero. Place it with the [ShellClearance] gap; it doesn't pad for the navigation bar.
 */
@Composable
fun FloatingNav(
    selectedTab: LarderTab,
    onTabSelected: (LarderTab) -> Unit,
    modifier: Modifier = Modifier,
    shoppingCount: Int = 0
) {
    val entries = listOf(
        NavEntry(LarderTab.Home, LarderIcons.House, stringResource(R.string.nav_label_home)),
        NavEntry(LarderTab.Recipe, LarderIcons.ChefHat, stringResource(R.string.nav_label_recipe)),
        NavEntry(LarderTab.Shopping, LarderIcons.ShoppingBasket, stringResource(R.string.nav_label_shopping_list)),
        NavEntry(LarderTab.Settings, LarderIcons.Settings, stringResource(R.string.nav_label_settings))
    )
    val shape = RoundedCornerShape(LarderRadius.xl)
    val scheme = MaterialTheme.colorScheme
    NavLayout(
        modifier = modifier
            .larderShadow(LarderTheme.shadows.level2, shape)
            .background(scheme.surface, shape)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(horizontal = 6.dp, vertical = LarderSpace.s2)
            .selectableGroup()
    ) {
        entries.forEach { entry ->
            NavTab(
                entry = entry,
                selected = entry.tab == selectedTab,
                badgeCount = if (entry.tab == LarderTab.Shopping) shoppingCount else 0,
                onClick = { onTabSelected(entry.tab) }
            )
        }
    }
}

/**
 * A row sized to its tabs. When space runs short, as with long Russian labels at a big font
 * scale, the tabs give up their side padding first, and only then do labels ellipsize, evenly.
 */
@Composable
private fun NavLayout(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 2.dp.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val available = constraints.maxWidth - gap * (measurables.size - 1)
        val spare = (available - natural.sum()).coerceAtLeast(0)
        val sidePadding = (spare / (measurables.size * 2)).coerceAtMost(LarderSpace.s3.roundToPx())
        val scale = (available.toFloat() / natural.sum()).coerceAtMost(1f)
        val widths = natural.map { (it * scale).toInt() + sidePadding * 2 }
        val height = measurables.indices.maxOf { measurables[it].minIntrinsicHeight(widths[it]) }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixed(widths[index], height))
        }
        layout(widths.sum() + gap * (widths.size - 1), height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, 0)
                x += it.width + gap
            }
        }
    }
}

@Composable
private fun NavTab(entry: NavEntry, selected: Boolean, badgeCount: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(LarderRadius.lg))
            .background(if (selected) scheme.primaryContainer else scheme.surface)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = LarderSpace.s2),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            LarderIcon(entry.icon, contentDescription = null, tint = color)
            if (badgeCount > 0) {
                // Spoken as the real number, which the badge caps at 99+.
                val countDescription = pluralStringResource(R.plurals.nav_tab_item_count, badgeCount, badgeCount)
                LarderBadge(
                    count = badgeCount,
                    compact = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-6).dp)
                        .clearAndSetSemantics { contentDescription = countDescription }
                )
            }
        }
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The labelled Scan button, on Home only; Recipe pins its own primary action in the same place. See [shellClearance] for where it sits. */
@Composable
fun ScanFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.scan_content_description)
) {
    val shape = RoundedCornerShape(LarderRadius.lg)
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 56.dp)
            .larderShadow(LarderTheme.shadows.level3, shape),
        shape = shape,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderIcon(LarderIcons.ScanBarcode, contentDescription = null, size = LarderIconSize.Large)
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A tab's one primary action, pinned where Scan sits on Home, above the pill: the Recipe tab's
 * Generate or Share. Full width on phones; the caller sizes it on expanded windows. [cost] is the
 * small pill at the end that says what the action takes ("Free").
 */
@Composable
fun PinnedAction(
    text: String,
    icon: LarderIcons,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cost: String? = null,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(LarderRadius.lg)
    val scheme = MaterialTheme.colorScheme
    val container = if (enabled) scheme.primary else scheme.surfaceContainerHigh
    val content = if (enabled) scheme.onPrimary else scheme.onSurfaceVariant
    Row(
        modifier = modifier
            .then(if (enabled) Modifier.larderShadow(LarderTheme.shadows.level2, shape) else Modifier)
            .clip(shape)
            .background(container, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = LarderSpace.s5, vertical = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LarderIcon(icon, contentDescription = null, tint = content)
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = content
        )
        cost?.let {
            Text(
                text = it,
                modifier = Modifier
                    .border(1.dp, content, RoundedCornerShape(LarderRadius.xl))
                    .padding(horizontal = LarderSpace.s2, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1
            )
        }
    }
}

@PreviewLightDark
@Composable
internal fun PinnedActionPreview() = LarderPreview {
    PinnedAction("Generate on this device", LarderIcons.Smartphone, {}, Modifier.fillMaxWidth(), cost = "Free")
    PinnedAction("Share prompt", LarderIcons.Share2, {}, Modifier.fillMaxWidth())
    PinnedAction("Generate on this device", LarderIcons.Smartphone, {}, Modifier.fillMaxWidth(), cost = "Free", enabled = false)
}

@PreviewLightDark
@Composable
internal fun FloatingNavPreview() = LarderPreview {
    FloatingNav(LarderTab.Home, {}, shoppingCount = 5)
    FloatingNav(LarderTab.Shopping, {}, shoppingCount = 120)
    FloatingNav(LarderTab.Settings, {})
}

@PreviewLightDark
@Composable
internal fun ScanFabPreview() = LarderPreview {
    ScanFab({})
}
