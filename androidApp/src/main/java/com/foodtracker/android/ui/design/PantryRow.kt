package com.foodtracker.android.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSize
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.model.ExpiryStatus

private val CATEGORY_ICONS: List<Pair<List<String>, LarderIcons>> = listOf(
    listOf("berr", "ягод", "bær") to LarderIcons.Cherry,
    listOf("fruit", "фрукт", "frukt") to LarderIcons.Apple,
    listOf("carrot", "root", "морков", "gulrot") to LarderIcons.Carrot,
    listOf("vegetable", "salad", "овощ", "зелен", "grønn") to LarderIcons.Leaf,
    listOf("bread", "baker", "pastr", "хлеб", "выпеч", "brød", "bakst") to LarderIcons.Croissant,
    listOf("drink", "beverage", "juice", "soda", "напит", "сок", "drikke") to LarderIcons.CupSoda,
    listOf("dairy", "milk", "cheese", "молоч", "молок", "сыр", "meieri", "melk", "ost") to LarderIcons.Milk,
    listOf("egg", "яйц") to LarderIcons.Egg,
    listOf("fish", "seafood", "рыб", "fisk", "sjømat") to LarderIcons.Fish,
    listOf("meat", "мяс", "kjøtt") to LarderIcons.Beef
)

/** The stand-in for a product with no photo, chosen from its category in any of the app's
 *  languages. [LarderIcons.Package] covers everything else, including no category at all. */
fun categoryIcon(category: String?): LarderIcons {
    val name = category?.lowercase() ?: return LarderIcons.Package
    return CATEGORY_ICONS.firstOrNull { (keys, _) -> keys.any(name::contains) }?.second
        ?: LarderIcons.Package
}

/** A 44dp product thumbnail. The icon shows until the photo loads, and stays if it never does,
 *  so a failed image is never a broken-image mark. */
@Composable
fun LarderThumb(
    imageUrl: String?,
    fallbackIcon: LarderIcons,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surfaceContainer
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(LarderRadius.md))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        LarderIcon(
            fallbackIcon,
            contentDescription = null,
            size = LarderIconSize.Large,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * One product in a list: thumbnail, name, status, where it lives, quantity. At least 68dp tall.
 * Put these in a [LarderGroup], or give each `Modifier.larderGroupItem` in a lazy list. The name
 * stays on one line and truncates; a long [place] wraps under the status.
 *
 * - [statusText] comes from [expiryStatusLabel]; [statusDescription] is its spoken form.
 * - [place] is "Fridge · Dairy", already translated.
 * - [onUseOne] adds the minus button, with [useOneDescription] as its label ("Use one: Milk").
 * - [selecting] swaps that button for a leading tick showing [selected], and taps toggle. The
 *   caller flips [selected] in [onClick], so the row itself holds no state.
 * - [current] marks the row open in the right pane on tablets.
 * - [trailing] replaces the minus and quantity, for screens that end a row differently.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PantryRow(
    name: String,
    status: ExpiryStatus,
    statusText: String,
    place: String?,
    quantity: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    fallbackIcon: LarderIcons = LarderIcons.Package,
    statusDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    onUseOne: (() -> Unit)? = null,
    useOneDescription: String = stringResource(R.string.use_one_content_description),
    selecting: Boolean = false,
    selected: Boolean = false,
    current: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        selected -> scheme.primaryContainer
        current -> scheme.surfaceContainer
        else -> Color.Transparent
    }
    val muted = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = LarderSpace.s3, vertical = LarderSpace.s2),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selecting) {
            // The 48dp target overhangs its 32dp of layout, so the ring sits close to the thumb.
            Box(modifier = Modifier.width(LarderSpace.s8), contentAlignment = Alignment.Center) {
                LarderTick(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    contentDescription = name,
                    modifier = Modifier.requiredSize(LarderSize.touch)
                )
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LarderThumb(
                imageUrl = imageUrl,
                fallbackIcon = fallbackIcon,
                background = if (selected || current) scheme.surface else scheme.surfaceContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(status, statusText, contentDescription = statusDescription)
                    place?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            if (onUseOne != null && !selecting) {
                LarderIconButton(
                    icon = LarderIcons.CircleMinus,
                    contentDescription = useOneDescription,
                    onClick = onUseOne,
                    modifier = Modifier.offset(x = LarderSpace.s1)
                )
            }
            Text(
                text = stringResource(R.string.quantity_format, quantity),
                style = MaterialTheme.typography.labelLarge.merge(LarderTabularNumbers),
                color = muted
            )
        }
    }
}

@PreviewLightDark
@Composable
internal fun PantryRowPreview() = LarderPreview {
    LarderGroup {
        PantryRow(
            name = "Baby spinach",
            status = ExpiryStatus.EXPIRING_TODAY,
            statusText = "Today",
            place = "Fridge · Vegetables",
            quantity = 1,
            onClick = {},
            fallbackIcon = categoryIcon("Vegetables"),
            onUseOne = {}
        )
        LarderDivider()
        PantryRow(
            name = "Salmon fillet",
            status = ExpiryStatus.EXPIRING_SOON,
            statusText = "In 3 days",
            place = "Fridge · Meat & fish",
            quantity = 2,
            onClick = {},
            fallbackIcon = categoryIcon("Meat & fish"),
            selecting = true,
            selected = true
        )
        LarderDivider()
        PantryRow(
            name = "Semi-skimmed milk",
            status = ExpiryStatus.EXPIRING_SOON,
            statusText = "In 2 days",
            place = "Fridge · Dairy",
            quantity = 1,
            onClick = {},
            fallbackIcon = categoryIcon("Dairy"),
            selecting = true
        )
        LarderDivider()
        PantryRow(
            name = "Sourdough bread",
            status = ExpiryStatus.EXPIRED,
            statusText = "1 day over",
            place = "Pantry · Bakery",
            quantity = 1,
            onClick = {},
            fallbackIcon = categoryIcon("Bakery"),
            current = true,
            onUseOne = {}
        )
        LarderDivider()
        PantryRow(
            name = "Organic stone-ground wholemeal spelt sourdough loaf with seeds",
            status = ExpiryStatus.FRESH,
            statusText = "14 Mar 2027",
            place = "A very long place name indeed · Bakery and other long categories",
            quantity = 12,
            onClick = {},
            onUseOne = {}
        )
    }
}
