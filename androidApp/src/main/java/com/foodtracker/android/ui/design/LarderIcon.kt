package com.foodtracker.android.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The icon sizes the design uses. One size per context, so screens don't pick their own. */
object LarderIconSize {
    /** Buttons, nav, rows. */
    val Default = 22.dp

    /** Scan and product thumbnails. */
    val Large = 24.dp

    /** The large product thumbnail at the head of the detail sheet and Add item. */
    val Hero = 32.dp

    /** Inline with caption text: status chips and field messages. */
    val Small = 14.dp
}

/** A Lucide icon. Pass a [contentDescription] unless text beside it already says the same. */
@Composable
fun LarderIcon(
    icon: LarderIcons,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = LarderIconSize.Default,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painterResource(icon.res),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}

/** An empty-state illustration, 160 by 120, drawn in the current theme's colours. Decorative. */
@Composable
fun LarderIllustration(art: LarderArt, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primaryContainer
    val soft = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val vector = remember(art, accent, soft, line) {
        val builder = ImageVector.Builder(
            defaultWidth = 160.dp,
            defaultHeight = 120.dp,
            viewportWidth = 160f,
            viewportHeight = 120f
        )
        art.layers.forEach { layer ->
            val nodes = PathParser().parsePathString(layer.pathData).toNodes()
            when (layer.paint) {
                LarderArtPaint.Accent -> builder.addPath(nodes, fill = SolidColor(accent))
                LarderArtPaint.Soft -> builder.addPath(nodes, fill = SolidColor(soft))
                LarderArtPaint.Line -> builder.addPath(
                    nodes,
                    stroke = SolidColor(line),
                    strokeLineWidth = 2.5f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }
        builder.build()
    }
    Image(painter = rememberVectorPainter(vector), contentDescription = null, modifier = modifier)
}

@PreviewLightDark
@Composable
internal fun LarderIconsPreview() = LarderPreview {
    FlowRow {
        LarderIcons.entries.forEach { LarderIcon(it, contentDescription = it.name, size = LarderIconSize.Large) }
    }
}

@PreviewLightDark
@Composable
internal fun LarderIllustrationsPreview() = LarderPreview {
    Row { LarderArt.entries.forEach { LarderIllustration(it, Modifier.size(106.dp, 80.dp)) } }
}
