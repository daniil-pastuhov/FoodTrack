package com.foodtracker.android.widget

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.model.ExpiryStatus

/*
 * The widgets' own small set of components. Glance renders through RemoteViews, so the Compose
 * ones in ui/design can't be used here, and neither can Vollkorn: widgets use the system font.
 * Colours are the generated larder_* resources, which carry their dark values in values-night.
 * Shapes are drawables rather than cornerRadius, which only works from API 31.
 */

/** The type scale from tokens.json, in the three sizes a widget uses. */
private val TitleSize = 16.sp
private val BodySize = 14.sp
private val CaptionSize = 12.sp

private val onSurface = ColorProvider(R.color.larder_on_surface)
private val onSurfaceVariant = ColorProvider(R.color.larder_on_surface_variant)

/** A widget's card. [endPadding] is zero for a list of ticks: a tick's ring sits in the middle of
 *  a 48dp target, so the target runs out to the card's edge and the ring lines up with the text. */
@Composable
internal fun WidgetSurface(
    endPadding: Dp = LarderSpace.s3,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_background))
            .padding(start = LarderSpace.s3, top = LarderSpace.s3, end = endPadding, bottom = LarderSpace.s3),
        content = content
    )
}

/** The title that opens the app at this widget's subject. */
@Composable
internal fun WidgetTitle(text: String, onClick: Action) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(fontSize = TitleSize, fontWeight = FontWeight.Bold, color = onSurface),
        modifier = GlanceModifier.padding(bottom = LarderSpace.s1).clickable(onClick)
    )
}

/** The quiet line a list shows when it has nothing in it. */
@Composable
internal fun WidgetEmptyText(text: String, modifier: GlanceModifier = GlanceModifier) {
    Text(text = text, modifier = modifier, style = TextStyle(fontSize = BodySize, color = onSurfaceVariant))
}

/** A row's product name. */
@Composable
internal fun WidgetItemName(text: String, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = text,
        maxLines = 1,
        modifier = modifier,
        style = TextStyle(fontSize = BodySize, fontWeight = FontWeight.Medium, color = onSurface)
    )
}

/** The "×2" beside a shopping entry. */
@Composable
internal fun WidgetQuantity(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = BodySize, fontWeight = FontWeight.Medium, color = onSurfaceVariant)
    )
}

/** Thin separator between rows in a widget's [androidx.glance.appwidget.lazy.LazyColumn],
 *  shared by every widget so the row rhythm stays identical across them. */
@Composable
internal fun WidgetRowDivider(endPadding: Dp = 0.dp) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(end = endPadding)) {
        Spacer(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ColorProvider(R.color.larder_outline_variant))
        )
    }
}

/** Section header used inside multi-section widgets (e.g. [OverviewWidget]) to label a
 *  group of rows without repeating each single-purpose widget's own title styling. */
@Composable
internal fun WidgetSectionHeader(title: String, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = title,
        maxLines = 1,
        modifier = modifier.padding(top = LarderSpace.s1, bottom = 2.dp),
        style = TextStyle(fontSize = CaptionSize, fontWeight = FontWeight.Bold, color = onSurfaceVariant)
    )
}

/** A foldable section's header: chevron, name and count. */
@Composable
internal fun WidgetFoldHeader(label: String, count: Int, collapsed: Boolean, onClick: Action, endPadding: Dp) {
    val style = TextStyle(fontSize = CaptionSize, fontWeight = FontWeight.Bold, color = onSurfaceVariant)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(FoldHeaderHeight)
            .padding(end = endPadding)
            .clickable(onClick)
    ) {
        Image(
            provider = ImageProvider(if (collapsed) R.drawable.ic_widget_chevron_right else R.drawable.ic_widget_chevron_down),
            contentDescription = null,
            colorFilter = ColorFilter.tint(onSurfaceVariant),
            modifier = GlanceModifier.size(StatusIconSize)
        )
        Spacer(modifier = GlanceModifier.width(LarderSpace.s1))
        Text(text = label, maxLines = 1, modifier = GlanceModifier.defaultWeight(), style = style)
        Text(text = count.toString(), style = style)
    }
}

/** How a status looks: a tint only when food needs attention, always an icon and words. */
internal data class WidgetChipStyle(
    @param:DrawableRes val background: Int?,
    @param:ColorRes val content: Int,
    @param:DrawableRes val icon: Int
)

internal fun widgetChipStyle(status: ExpiryStatus): WidgetChipStyle = when (status) {
    ExpiryStatus.EXPIRED ->
        WidgetChipStyle(R.drawable.widget_chip_expired, R.color.larder_on_expired_container, R.drawable.ic_widget_status_past)
    ExpiryStatus.EXPIRING_TODAY ->
        WidgetChipStyle(R.drawable.widget_chip_today, R.color.larder_on_today_container, R.drawable.ic_widget_status_clock)
    ExpiryStatus.EXPIRING_SOON ->
        WidgetChipStyle(R.drawable.widget_chip_soon, R.color.larder_on_soon_container, R.drawable.ic_widget_status_clock)
    ExpiryStatus.FRESH ->
        WidgetChipStyle(null, R.color.larder_on_surface_variant, R.drawable.ic_widget_check)
}

/** The widget's `StatusChip`, with the same words and colours as the app's. */
@Composable
internal fun WidgetStatusChip(status: ExpiryStatus, text: String) {
    val style = widgetChipStyle(status)
    val content = ColorProvider(style.content)
    val shape = style.background?.let { GlanceModifier.background(ImageProvider(it)) } ?: GlanceModifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = shape.padding(start = LarderSpace.s1, end = LarderSpace.s2, top = 1.dp, bottom = 1.dp)
    ) {
        Image(
            provider = ImageProvider(style.icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(content),
            modifier = GlanceModifier.size(StatusIconSize)
        )
        Spacer(modifier = GlanceModifier.width(3.dp))
        Text(
            text = text,
            maxLines = 1,
            style = TextStyle(fontSize = CaptionSize, fontWeight = FontWeight.Medium, color = content)
        )
    }
}

/** The ring with a tick: a 24dp icon in a 48dp target. [description] names the product, because
 *  every row's tick would otherwise read the same. */
@Composable
internal fun WidgetTick(description: String, onClick: Action) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier.size(LarderSize.control).clickable(onClick)
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_widget_check),
            contentDescription = description,
            colorFilter = ColorFilter.tint(onSurfaceVariant),
            modifier = GlanceModifier.size(TickIconSize)
        )
    }
}

/** "Used one" on a wide row: a 32dp pill in a 48dp target. */
@Composable
internal fun WidgetUsedButton(label: String, description: String, onClick: Action) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = GlanceModifier
            .height(LarderSize.control)
            .clickable(onClick)
            .semantics { contentDescription = description }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = GlanceModifier
                .height(UsedButtonHeight)
                .background(ImageProvider(R.drawable.widget_button_used))
                .padding(horizontal = LarderSpace.s3)
        ) {
            Text(
                text = label,
                maxLines = 1,
                style = TextStyle(
                    fontSize = CaptionSize,
                    fontWeight = FontWeight.Medium,
                    color = ColorProvider(R.color.larder_on_primary_container)
                )
            )
        }
    }
}

private val FoldHeaderHeight = 28.dp
private val StatusIconSize = 14.dp
private val TickIconSize = 24.dp
private val UsedButtonHeight = 32.dp
