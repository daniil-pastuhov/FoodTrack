package com.foodtracker.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.BrandDark
import com.foodtracker.android.ui.theme.BrandPrimary
import com.foodtracker.android.ui.theme.TextSecondary
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.android.util.categoryLabel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ItemCard(
    row: PantryRow,
    onDelete: () -> Unit,
    onDecrement: () -> Unit,
    onMoveToShoppingList: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {}
) {
    // Everything this card displays is derived once per data change in PantryStateHolder rather
    // than per card per recomposition here — days remaining, expiry status, and the resolved
    // category name, which used to cost a linear scan over every CategoryPreference.
    val item = row.item
    val daysRemaining = row.daysRemaining
    val status = row.status
    val displayCategory = row.categoryDisplayName
    val borderWidth by animateDpAsState(targetValue = if (selected) 2.dp else 0.dp, label = "itemCardBorderWidth")
    val borderColor by animateColorAsState(
        targetValue = if (selected) BrandPrimary else Color.Transparent,
        label = "itemCardBorderColor"
    )
    // Mirrors iOS's swipe-to-delete (left)/swipe-to-shopping-list (right) row actions. There's no
    // separate "confirm" step — the item leaving `state.filteredItems` once the swipe settles is
    // what makes the swipe-away feel permanent, and an undo (snackbar) brings a fresh card back.
    //
    // Deliberately a plain `remember` rather than `rememberSwipeToDismissBoxState`: that factory
    // is `rememberSaveable`-backed, and its saver persists `currentValue` under the LazyLayout
    // item key. Since a lazy item's saved state outlives the item leaving the list, an undo that
    // re-adds the same id used to restore the saved `EndToStart`/`StartToEnd` — the card came
    // back already dismissed, rendering as nothing but the swipe background, and because
    // SwipeToDismissBox disables gestures while `settledValue != Settled` it couldn't even be
    // swiped back. Composition-scoped state always starts `Settled` on a fresh card.
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionalThreshold
        )
    }
    // SwipeToDismissBox keys its dismissal effect on the `onDismiss` lambda, so this has to keep
    // a stable identity: a new instance per recomposition would re-run that effect — and re-fire
    // the delete — while the card is still settled in a dismissed direction.
    val currentOnDelete by rememberUpdatedState(onDelete)
    val currentOnMoveToShoppingList by rememberUpdatedState(onMoveToShoppingList)
    val onDismiss = remember {
        { value: SwipeToDismissBoxValue ->
            when (value) {
                SwipeToDismissBoxValue.EndToStart -> currentOnDelete()
                SwipeToDismissBoxValue.StartToEnd -> currentOnMoveToShoppingList()
                SwipeToDismissBoxValue.Settled -> {}
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        onDismiss = onDismiss,
        backgroundContent = { SwipeActionBackground(dismissState.dismissDirection) }
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                AnimatedVisibility(
                    visible = selectionMode,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) BrandPrimary else TextSecondary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }

                // Product image or placeholder
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (!item.imageUrl.isNullOrBlank()) {
                        // Remembered per URL: an unremembered builder allocates a fresh
                        // ImageRequest for every card on every recomposition, and a new request
                        // instance also defeats Coil's own equality check on the model.
                        val context = LocalContext.current
                        val imageRequest = remember(context, item.imageUrl) {
                            ImageRequest.Builder(context)
                                .data(item.imageUrl)
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Fastfood,
                            contentDescription = null,
                            tint = BrandPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Item Details
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!item.brand.isNullOrBlank()) {
                        Text(
                            text = item.brand!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ExpiryBadge(daysRemaining = daysRemaining, status = status)

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Kitchen,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = storageLocationLabel(item.storageLocation),
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!displayCategory.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = categoryLabel(displayCategory),
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = !selectionMode,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDecrement, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = stringResource(R.string.use_one_content_description),
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.quantity_format, item.quantity),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

/** The colored panel revealed behind an [ItemCard] as it's swiped: red + trash trailing
 *  (swipe left, matching iOS) for delete, blue + cart leading (swipe right) for "move to
 *  shopping list". Blank while [direction] is [SwipeToDismissBoxValue.Settled]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue) {
    val (color, alignment, icon) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(Color(0xFF1976D2), Alignment.CenterStart, Icons.Default.AddShoppingCart)
        SwipeToDismissBoxValue.EndToStart -> Triple(MaterialTheme.colorScheme.error, Alignment.CenterEnd, Icons.Default.DeleteOutline)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, Alignment.Center, null)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment
    ) {
        icon?.let {
            Icon(imageVector = it, contentDescription = null, tint = Color.White)
        }
    }
}
