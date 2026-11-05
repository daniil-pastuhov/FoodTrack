package com.foodtracker.android.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foodtracker.android.R
import com.foodtracker.android.ui.design.LarderButton
import com.foodtracker.android.ui.design.LarderButtonStyle
import com.foodtracker.android.ui.design.LarderCallout
import com.foodtracker.android.ui.design.LarderCalloutKind
import com.foodtracker.android.ui.design.LarderDivider
import com.foodtracker.android.ui.design.LarderIconButton
import com.foodtracker.android.ui.design.LarderIcons
import com.foodtracker.android.ui.design.LarderSheet
import com.foodtracker.android.ui.design.LarderThumb
import com.foodtracker.android.ui.design.StatusChip
import com.foodtracker.android.ui.design.categoryIcon
import com.foodtracker.android.ui.theme.LarderRadius
import com.foodtracker.android.ui.theme.LarderSpace
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.android.util.categoryLabel
import com.foodtracker.android.util.storageLocationLabel
import com.foodtracker.model.ExpiryStatus
import com.foodtracker.model.PantryItem
import com.foodtracker.model.ProductInfo
import com.foodtracker.model.ProductNutrition
import com.foodtracker.viewmodel.PantryRow
import com.foodtracker.viewmodel.PantryStateHolder
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.time.format.DateTimeFormatter

/** What can be done to the product on show. */
class ProductDetailActions(
    val onUseOne: (PantryItem) -> Unit,
    val onEdit: (PantryItem) -> Unit,
    val onMoveToShoppingList: (String) -> Unit,
    val onDelete: (String) -> Unit
)

/**
 * The product detail route as a sheet, for phones and for anything opened outside Home. Every
 * action closes the sheet first, because the result shows on the screen underneath.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailRoute(
    stateHolder: PantryStateHolder,
    itemId: String,
    actions: ProductDetailActions,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val closeThen: (() -> Unit) -> Unit = { action ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }
    ProductDetailData(stateHolder, itemId, onGone = onDismiss) { row, nutrition, warnings, today ->
        LarderSheet(onDismissRequest = onDismiss, sheetState = sheetState, scrollable = false) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = LarderSpace.s4)
            ) {
                ProductDetailBody(
                    row = row,
                    nutrition = nutrition,
                    warnings = warnings,
                    today = today,
                    thumbBackground = MaterialTheme.colorScheme.surfaceContainer,
                    onClose = { closeThen {} }
                )
            }
            LarderDivider(Modifier.fullBleed(LarderSpace.s4))
            ProductDetailButtons(
                modifier = Modifier.padding(top = LarderSpace.s3),
                onUseOne = { closeThen { actions.onUseOne(row.item) } },
                onEdit = { closeThen { actions.onEdit(row.item) } },
                onMoveToShoppingList = { closeThen { actions.onMoveToShoppingList(row.id) } },
                onDelete = { closeThen { actions.onDelete(row.id) } }
            )
        }
    }
}

/** The same content as the right-hand pane on expanded Home, where it stays open after an action.
 *  [bottomClearance] keeps the buttons above the floating pill and Scan. */
@Composable
fun ProductDetailPane(
    stateHolder: PantryStateHolder,
    itemId: String,
    actions: ProductDetailActions,
    bottomClearance: Dp,
    modifier: Modifier = Modifier
) {
    ProductDetailData(stateHolder, itemId, onGone = {}) { row, nutrition, warnings, today ->
        Column(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(LarderSpace.s4)
            ) {
                ProductDetailBody(
                    row = row,
                    nutrition = nutrition,
                    warnings = warnings,
                    today = today,
                    thumbBackground = MaterialTheme.colorScheme.surface,
                    onClose = null
                )
            }
            LarderDivider()
            ProductDetailButtons(
                modifier = Modifier.padding(
                    start = LarderSpace.s4,
                    end = LarderSpace.s4,
                    top = LarderSpace.s3,
                    bottom = bottomClearance
                ),
                onUseOne = { actions.onUseOne(row.item) },
                onEdit = { actions.onEdit(row.item) },
                onMoveToShoppingList = { actions.onMoveToShoppingList(row.id) },
                onDelete = { actions.onDelete(row.id) }
            )
        }
    }
}

/**
 * Nutrition alone, for a product that isn't in the pantry yet: the Add item screen's scanned
 * product. Everything here is often missing, because Open Food Facts is crowd-sourced, so each
 * section is left out rather than shown empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailSheet(
    productName: String,
    nutrition: ProductNutrition,
    /** The person's own allergens this product matched, shown first. */
    warnings: List<String>,
    onDismiss: () -> Unit
) {
    LarderSheet(onDismissRequest = onDismiss, title = productName) {
        Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)) {
            AllergenCallout(warnings)
            NutritionSections(nutrition, warnings)
        }
    }
}

/** Looks up the live row and its cached nutrition, so the quantity on show follows Use one. */
@Composable
private fun ProductDetailData(
    stateHolder: PantryStateHolder,
    itemId: String,
    onGone: () -> Unit,
    content: @Composable (PantryRow, ProductNutrition, List<String>, LocalDate) -> Unit
) {
    val state by stateHolder.uiState.collectAsStateWithLifecycle()
    // Kept so the sheet can finish sliding away after its item has gone.
    var lastRow by remember(itemId) { mutableStateOf<PantryRow?>(null) }
    val found = state.allRows.find { it.id == itemId }
    if (found != null) lastRow = found
    val row = found ?: lastRow
    if (row == null) {
        if (!state.isLoading) LaunchedEffect(itemId) { onGone() }
        return
    }
    val barcode = row.item.barcode
    val product by produceState<ProductInfo?>(null, barcode) {
        value = barcode?.let { stateHolder.cachedProduct(it) }
    }
    val nutrition = product?.nutrition ?: ProductNutrition()
    content(row, nutrition, nutrition.matchedAllergens(state.settings.allergens), state.today)
}

@Composable
private fun ProductDetailBody(
    row: PantryRow,
    nutrition: ProductNutrition,
    warnings: List<String>,
    today: LocalDate,
    thumbBackground: Color,
    onClose: (() -> Unit)?
) {
    val item = row.item
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s3)) {
            LarderThumb(
                imageUrl = item.imageUrl?.takeIf { it.isNotBlank() },
                fallbackIcon = categoryIcon(row.categoryDisplayName ?: item.category),
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(LarderRadius.lg)),
                background = thumbBackground
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                item.brand?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            onClose?.let {
                LarderIconButton(LarderIcons.X, stringResource(R.string.detail_close), it)
            }
        }
        AllergenCallout(warnings)
        ProductFacts(row, today)
        NutritionSections(nutrition, warnings)
        item.notes?.takeIf { it.isNotBlank() }?.let {
            DetailSection(stringResource(R.string.home_notes_label)) { BodyText(it) }
        }
        if (!nutrition.isEmpty) {
            Text(
                text = stringResource(R.string.detail_credit),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** T4, for as long as the match holds, so it can't be dismissed. */
@Composable
private fun AllergenCallout(warnings: List<String>) {
    if (warnings.isEmpty()) return
    LarderCallout(
        kind = LarderCalloutKind.Error,
        icon = LarderIcons.TriangleAlert,
        title = stringResource(R.string.allergen_warning_format, warnings.joinToString(", ")),
        message = stringResource(R.string.detail_allergen_note)
    )
}

@Composable
private fun ProductFacts(row: PantryRow, today: LocalDate) {
    val item = row.item
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(item.expiryDate, locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMMy"), locale)
            .format(item.expiryDate.toJavaLocalDate())
    }
    val shape = RoundedCornerShape(LarderRadius.lg)
    val quantity = stringResource(R.string.quantity_format, item.quantity)
    val category = row.categoryDisplayName?.takeIf { it.isNotBlank() }?.let { categoryLabel(it) }
    val facts = listOfNotNull<@Composable RowScope.() -> Unit>(
        {
            Fact(stringResource(R.string.home_best_before_label), date) {
                StatusChip(row.status, row.daysRemaining, item.expiryDate, today, Modifier.padding(top = LarderSpace.s1))
            }
        },
        { Fact(stringResource(R.string.home_location_label), storageLocationLabel(item.storageLocation)) },
        category?.let { name -> { Fact(stringResource(R.string.home_category_label), name) } },
        { Fact(stringResource(R.string.quantity_label), quantity) }
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = LarderSpace.s4, vertical = LarderSpace.s3),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s3)
    ) {
        facts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s4)) {
                pair.forEach { it() }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowScope.Fact(label: String, value: String, extra: (@Composable ColumnScope.() -> Unit)? = null) {
    Column(modifier = Modifier.weight(1f)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        extra?.invoke(this)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NutritionSections(nutrition: ProductNutrition, warnings: List<String>) {
    val grade = nutrition.nutriScore?.uppercase()?.takeIf { it in NutriGrades }
    val nova = nutrition.novaGroup?.takeIf { it in 1..4 }
    if (grade != null || nova != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(LarderSpace.s4)) {
            Box(Modifier.weight(1f)) { grade?.let { NutriScoreScale(it) } ?: nova?.let { NovaScale(it) } }
            Box(Modifier.weight(1f)) { if (grade != null) nova?.let { NovaScale(it) } }
        }
    }
    if (nutrition.allergens.isNotEmpty()) {
        DetailSection(stringResource(R.string.product_detail_allergens)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
                verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
            ) {
                nutrition.allergens.forEach { AllergenTag(it, matched = it in warnings) }
            }
        }
    }
    // Never cut short: someone with an allergy needs all of it.
    nutrition.ingredients?.takeIf { it.isNotBlank() }?.let {
        DetailSection(stringResource(R.string.product_detail_ingredients)) { BodyText(it) }
    }
}

@Composable
private fun AllergenTag(name: String, matched: Boolean) {
    val shape = RoundedCornerShape(LarderRadius.sm)
    val scheme = MaterialTheme.colorScheme
    Text(
        text = name.replaceFirstChar { it.uppercase() },
        modifier = Modifier
            .then(
                if (matched) {
                    Modifier.background(scheme.errorContainer, shape)
                } else {
                    Modifier.background(scheme.surface, shape).border(1.dp, scheme.outlineVariant, shape)
                }
            )
            .padding(horizontal = LarderSpace.s2, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (matched) scheme.onErrorContainer else scheme.onSurface
    )
}

private val NutriGrades = listOf("A", "B", "C", "D", "E")

/** The official colours, on a dark plate in both themes so that all five reach 3:1. The grade is
 *  also the larger, ringed tile and the caption, so it doesn't rest on colour. */
@Composable
private fun NutriScoreScale(grade: String) {
    val colors = LarderTheme.colors
    val caption = stringResource(R.string.detail_nutri_caption, grade)
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = caption },
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.nutriPlate, RoundedCornerShape(LarderRadius.md))
                .padding(horizontal = LarderSpace.s2, vertical = LarderSpace.s1),
            horizontalArrangement = Arrangement.spacedBy(LarderSpace.s1),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NutriGrades.forEach { letter ->
                val (fill, ink) = when (letter) {
                    "A" -> colors.nutriA to colors.onNutriA
                    "B" -> colors.nutriB to colors.onNutriB
                    "C" -> colors.nutriC to colors.onNutriC
                    "D" -> colors.nutriD to colors.onNutriD
                    else -> colors.nutriE to colors.onNutriE
                }
                val on = letter == grade
                val shape = RoundedCornerShape(LarderRadius.sm)
                ScaleTile(
                    text = letter,
                    ink = ink,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (on) 36.dp else 28.dp)
                        .then(if (on) Modifier.border(2.dp, colors.onNutriPlate, shape) else Modifier)
                        .background(fill, shape)
                )
            }
        }
        ScaleCaption(caption)
    }
}

/** One marker on a labelled scale: processing isn't a traffic light. */
@Composable
private fun NovaScale(group: Int) {
    val word = stringResource(
        when (group) {
            1 -> R.string.detail_nova_1
            2 -> R.string.detail_nova_2
            3 -> R.string.detail_nova_3
            else -> R.string.detail_nova_4
        }
    )
    val spoken = stringResource(R.string.detail_nova_description, group, word)
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(LarderRadius.sm)
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (1..4).forEach { number ->
                val on = number == group
                ScaleTile(
                    text = number.toString(),
                    ink = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .then(
                            if (on) {
                                Modifier.background(scheme.primary, shape)
                            } else {
                                Modifier.background(scheme.surface, shape).border(1.dp, scheme.outlineVariant, shape)
                            }
                        )
                )
            }
        }
        ScaleCaption(stringResource(R.string.detail_nova_caption, group, word))
    }
}

@Composable
private fun ScaleTile(text: String, ink: Color, modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

@Composable
private fun ScaleCaption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LarderSpace.s1)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** Use one leads, and the rest wrap under it when the labels run long, as Russian ones do. The
 *  intrinsic width stops FlowRow fitting each button to its longest word before it grows them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProductDetailButtons(
    modifier: Modifier,
    onUseOne: () -> Unit,
    onEdit: () -> Unit,
    onMoveToShoppingList: () -> Unit,
    onDelete: () -> Unit
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LarderSpace.s2),
        verticalArrangement = Arrangement.spacedBy(LarderSpace.s2)
    ) {
        LarderButton(
            text = stringResource(R.string.use_one_content_description),
            onClick = onUseOne,
            modifier = Modifier.weight(2f).width(IntrinsicSize.Max),
            leadingIcon = LarderIcons.CircleMinus
        )
        LarderButton(
            text = stringResource(R.string.detail_edit),
            onClick = onEdit,
            modifier = Modifier.weight(1f).width(IntrinsicSize.Max),
            style = LarderButtonStyle.Outline,
            leadingIcon = LarderIcons.Pencil
        )
        LarderButton(
            text = stringResource(R.string.detail_to_shopping_list),
            onClick = onMoveToShoppingList,
            modifier = Modifier.weight(1f).width(IntrinsicSize.Max),
            style = LarderButtonStyle.Outline,
            leadingIcon = LarderIcons.ShoppingBasket
        )
        LarderButton(
            text = stringResource(R.string.delete),
            onClick = onDelete,
            modifier = Modifier.weight(1f).width(IntrinsicSize.Max),
            style = LarderButtonStyle.DangerText,
            leadingIcon = LarderIcons.Trash2
        )
    }
}

/** Runs past a parent's side padding by [inset], for a line or row that goes edge to edge. */
internal fun Modifier.fullBleed(inset: Dp): Modifier = layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra)
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

private val previewRow = PantryRow(
    item = PantryItem(
        id = "1",
        name = "Greek yoghurt",
        brand = "Fage",
        expiryDate = LocalDate(2026, 11, 5),
        addedDate = LocalDate(2026, 11, 1),
        storageLocation = "Fridge",
        quantity = 2,
        notes = "Opened on Sunday. Good with the strawberries."
    ),
    daysRemaining = 1,
    status = ExpiryStatus.EXPIRING_SOON,
    categoryDisplayName = "Dairy",
    sortName = "greek yoghurt"
)

@Composable
private fun PreviewBody(row: PantryRow, nutrition: ProductNutrition, warnings: List<String>) = LarderTheme {
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(LarderSpace.s4)) {
        ProductDetailBody(row, nutrition, warnings, LocalDate(2026, 11, 4), MaterialTheme.colorScheme.surfaceContainer) {}
    }
}

@PreviewLightDark
@Composable
private fun ProductDetailFullPreview() = PreviewBody(
    previewRow,
    ProductNutrition("b", 1, listOf("milk"), "Pasteurised whole milk, live yoghurt cultures."),
    emptyList()
)

@PreviewLightDark
@Composable
private fun ProductDetailPartialPreview() = PreviewBody(
    previewRow.copy(item = previewRow.item.copy(brand = null, notes = null), categoryDisplayName = null),
    ProductNutrition(),
    emptyList()
)

@PreviewLightDark
@Composable
private fun ProductDetailAllergenPreview() = PreviewBody(
    previewRow,
    ProductNutrition("d", 4, listOf("milk", "soybeans"), "Pasteurised whole milk."),
    listOf("milk")
)
