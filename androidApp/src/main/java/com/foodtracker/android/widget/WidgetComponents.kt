package com.foodtracker.android.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.foodtracker.android.R

/** Thin separator between rows in a widget's [androidx.glance.appwidget.lazy.LazyColumn],
 *  shared by every widget so the row rhythm stays identical across them. */
@Composable
internal fun WidgetRowDivider() {
    Spacer(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ColorProvider(R.color.widget_divider))
    )
}

/** Section header used inside multi-section widgets (e.g. [OverviewWidget]) to label a
 *  group of rows without repeating each single-purpose widget's own title styling. */
@Composable
internal fun WidgetSectionHeader(title: String, modifier: GlanceModifier = GlanceModifier) {
    Text(
        text = title,
        modifier = modifier.padding(top = 4.dp, bottom = 4.dp),
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ColorProvider(R.color.widget_on_background_muted))
    )
}
