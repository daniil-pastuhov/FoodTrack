package com.foodtracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderGroupPosition
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSwipeAction
import com.foodtracker.android.ui.design.LarderSwipeRow
import com.foodtracker.android.ui.design.LarderSwipeStyle
import com.foodtracker.android.ui.design.PantryRow
import com.foodtracker.android.ui.design.categoryIcon
import com.foodtracker.android.ui.design.expiryStatusLabel
import com.foodtracker.android.ui.design.larderGroupItem
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import kotlinx.datetime.LocalDate
import com.foodtracker.viewmodel.PantryRow as PantryRowData

/**
 * One pantry item on Home: a [PantryRow] with swipe right to move it to the shopping list and
 * swipe left to delete it. Both are also on the product detail, so nothing depends on the gesture.
 *
 * Everything shown is derived once per data change in PantryStateHolder rather than per row here.
 */
@Composable
fun ItemCard(
    row: PantryRowData,
    today: LocalDate,
    position: LarderGroupPosition,
    onDelete: () -> Unit,
    onUseOne: () -> Unit,
    onMoveToShoppingList: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    current: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    val item = row.item
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val statusText = remember(resources, locale, row.status, row.daysRemaining, item.expiryDate, today) {
        expiryStatusLabel(resources, locale, row.status, row.daysRemaining, item.expiryDate, today)
    }
    val location = storageLocationLabel(item.storageLocation)
    val place = row.categoryDisplayName?.takeIf { it.isNotBlank() }
        ?.let { "$location · ${categoryLabel(it)}" }
        ?: location
    val toList = stringResource(R.string.home_swipe_to_list)
    val delete = stringResource(R.string.delete)

    LarderSwipeRow(
        startAction = LarderSwipeAction(LarderIcons.ShoppingBasket, toList, onSwipe = onMoveToShoppingList),
        endAction = LarderSwipeAction(LarderIcons.Trash2, delete, LarderSwipeStyle.Danger, onSwipe = onDelete),
        modifier = modifier.larderGroupItem(position),
        enabled = !selectionMode
    ) {
        PantryRow(
            name = item.name,
            status = row.status,
            statusText = statusText,
            place = place,
            quantity = item.quantity,
            onClick = onClick,
            // The swipe slides this over its reveal, so it can't be see-through.
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            imageUrl = item.imageUrl?.takeIf { it.isNotBlank() },
            fallbackIcon = categoryIcon(row.categoryDisplayName ?: item.category),
            onLongClick = onLongClick,
            onUseOne = onUseOne,
            useOneDescription = stringResource(R.string.home_use_one_item, item.name),
            selecting = selectionMode,
            selected = selected,
            current = current
        )
    }
}

@PreviewLightDark
@Composable
private fun ItemCardPreview() = LarderTheme {
    val today = LocalDate(2026, 11, 4)
    ItemCard(
        row = PantryRowData(
            item = PantryItem(id = "1", name = "Greek yoghurt", expiryDate = LocalDate(2026, 11, 5), addedDate = today, quantity = 2),
            daysRemaining = 1,
            status = ExpiryStatus.EXPIRING_SOON,
            categoryDisplayName = "Dairy",
            sortName = "greek yoghurt"
        ),
        today = today,
        position = LarderGroupPosition.Single,
        onDelete = {},
        onUseOne = {},
        onMoveToShoppingList = {},
        onClick = {}
    )
}
