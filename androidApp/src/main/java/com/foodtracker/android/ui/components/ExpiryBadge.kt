package com.foodtracker.android.ui.components

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.ui.theme.ExpiredRed
import com.foodtracker.android.ui.theme.ExpiredRedBg
import com.foodtracker.android.ui.theme.ExpiringAmber
import com.foodtracker.android.ui.theme.ExpiringAmberBg
import com.foodtracker.android.ui.theme.FreshGreen
import com.foodtracker.android.ui.theme.FreshGreenBg
import com.foodtracker.android.util.daysRemainingLabel
import com.foodtracker.model.ExpiryStatus

@Composable
fun ExpiryBadge(
    daysRemaining: Int,
    status: ExpiryStatus,
    modifier: Modifier = Modifier
) {
    val (bg, textColor, icon) = when (status) {
        ExpiryStatus.EXPIRED -> Triple(ExpiredRedBg, ExpiredRed, Icons.Default.Error)
        ExpiryStatus.EXPIRING_TODAY,
        ExpiryStatus.EXPIRING_SOON -> Triple(ExpiringAmberBg, ExpiringAmber, Icons.Default.Warning)
        ExpiryStatus.FRESH -> Triple(FreshGreenBg, FreshGreen, Icons.Default.Schedule)
    }

    val label = daysRemainingLabel(daysRemaining)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
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
            fontWeight = FontWeight.SemiBold
        )
    }
}
