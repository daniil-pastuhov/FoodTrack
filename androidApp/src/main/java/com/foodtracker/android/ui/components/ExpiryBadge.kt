package com.foodtracker.android.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.ui.theme.expiredStatusColors
import com.foodtracker.android.ui.theme.expiringStatusColors
import com.foodtracker.android.ui.theme.freshStatusColors
import com.foodtracker.android.util.daysRemainingLabel
import com.foodtracker.model.ExpiryStatus

@Composable
fun ExpiryBadge(
    daysRemaining: Int,
    status: ExpiryStatus,
    modifier: Modifier = Modifier
) {
    val (colors, icon) = when (status) {
        ExpiryStatus.EXPIRED -> expiredStatusColors() to Icons.Default.Error
        ExpiryStatus.EXPIRING_TODAY,
        ExpiryStatus.EXPIRING_SOON -> expiringStatusColors() to Icons.Default.Warning
        ExpiryStatus.FRESH -> freshStatusColors() to Icons.Default.Schedule
    }
    val bg = colors.background
    val textColor = colors.foreground

    val label = daysRemainingLabel(daysRemaining)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .animateContentSize()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
