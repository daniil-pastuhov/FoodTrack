package com.foodtracker.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary

/** The four primary destinations reachable from the floating bottom pill nav bar. Scanning and
 *  adding a new item are deliberately not tabs here — they're the two floating round buttons
 *  ([ScanAndAddFabs]) that float above the pill instead, so they stay reachable from every tab. */
enum class BottomNavTab { HOME, CALENDAR, SHOPPING_LIST, SETTINGS }

private data class BottomNavItem(
    val tab: BottomNavTab,
    val icon: ImageVector,
    val label: String
)

/** Floating, fully-rounded pill nav bar. Unlike Material3's edge-to-edge [androidx.compose.material3.NavigationBar],
 *  this is sized to its content and meant to be placed with margin around it (see its usage as a
 *  Scaffold `bottomBar` in `MainActivity`), so the background shows through the gaps at its sides. */
@Composable
fun AppBottomBar(
    selectedTab: BottomNavTab,
    onTabSelected: (BottomNavTab) -> Unit,
    shoppingListCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        BottomNavItem(BottomNavTab.HOME, Icons.Default.Home, stringResource(R.string.nav_label_home)),
        BottomNavItem(BottomNavTab.CALENDAR, Icons.Default.CalendarMonth, stringResource(R.string.nav_label_calendar)),
        BottomNavItem(BottomNavTab.SHOPPING_LIST, Icons.Default.ShoppingCart, stringResource(R.string.nav_label_shopping_list)),
        BottomNavItem(BottomNavTab.SETTINGS, Icons.Default.Settings, stringResource(R.string.nav_label_settings))
    )
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items.forEach { item ->
                BottomNavItemView(
                    icon = item.icon,
                    label = item.label,
                    selected = item.tab == selectedTab,
                    // Count of distinct products on the shopping list, not total quantity —
                    // each ShoppingListEntry already represents one product (see its `quantity`
                    // field), so entries.size is the right count.
                    badgeCount = if (item.tab == BottomNavTab.SHOPPING_LIST) shoppingListCount else 0,
                    onClick = { onTabSelected(item.tab) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItemView(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) BrandPrimary else TextSecondary,
        label = "bottomNavItemColor"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) BrandPrimary.copy(alpha = 0.12f) else Color.Transparent,
        label = "bottomNavItemBackground"
    )
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        BadgedBox(
            badge = {
                if (badgeCount > 0) {
                    Badge(containerColor = BrandPrimary) {
                        Text(badgeCount.toString())
                    }
                }
            }
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = contentColor, modifier = Modifier.height(22.dp))
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = contentColor,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 11.sp
        )
    }
}

/** The two floating round buttons that hover above [AppBottomBar], reachable from every tab:
 *  a secondary "scan a barcode" button and the primary "add an item" button. */
@Composable
fun ScanAndAddFabs(
    onScan: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Scan is the more important, more frequently used action, so it gets the larger primary
    // button closest to the nav bar (easiest thumb reach); Add is the secondary button above it.
    // No navigationBarsPadding() here: the caller (MainActivity) already lifts this stack clear
    // of the system nav bar via its bottom padding, which is derived from the pill nav bar's
    // height and already bakes in that inset — adding it again here would double-count it and
    // inflate the measured stack height fed back into the tabs' bottom content clearance.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        SmallFloatingActionButton(
            onClick = onAdd,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = CircleShape
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_item_title))
        }
        FloatingActionButton(
            onClick = onScan,
            containerColor = BrandPrimary,
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_content_description))
        }
    }
}
