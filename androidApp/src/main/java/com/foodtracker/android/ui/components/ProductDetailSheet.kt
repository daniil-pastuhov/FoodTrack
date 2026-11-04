package com.foodtracker.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foodtracker.android.R
import com.foodtracker.android.ui.theme.LarderTheme
import com.foodtracker.model.ProductNutrition

/**
 * Nutritional and dietary detail for a scanned product: Nutri-Score, NOVA group, allergens and
 * ingredients.
 *
 * Open Food Facts has returned all of this along with the name and image since the app first
 * called it; the response was simply being discarded. For someone with an allergy the allergen
 * list is the reason to scan at all, so this is closer to a missing feature than an extra.
 *
 * Everything here is frequently absent — OFF is crowd-sourced, and a product having a name says
 * nothing about whether anyone filled in its ingredients — so each section is omitted rather
 * than shown empty, and the sheet is only offered at all when something is known.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductDetailSheet(
    productName: String,
    nutrition: ProductNutrition,
    /** The user's own allergens that this product matched, surfaced at the top rather than left
     *  for them to spot in the list. */
    warnings: List<String>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = productName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (warnings.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.allergen_warning_format, warnings.joinToString(", ")),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                nutrition.nutriScore?.let { NutriScoreBadge(it) }
                nutrition.novaGroup?.let { NovaBadge(it) }
            }

            if (nutrition.allergens.isNotEmpty()) {
                DetailSection(title = stringResource(R.string.product_detail_allergens)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        nutrition.allergens.forEach { allergen ->
                            SuggestionChip(onClick = {}, label = { Text(allergen) })
                        }
                    }
                }
            }

            nutrition.ingredients?.takeIf { it.isNotBlank() }?.let { ingredients ->
                DetailSection(title = stringResource(R.string.product_detail_ingredients)) {
                    Text(text = ingredients, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}

/** Nutri-Score A..E, coloured green-to-red the way the official scale is — the letter alone
 *  means nothing to someone who hasn't seen the scale before. */
@Composable
private fun NutriScoreBadge(grade: String) {
    val colors = LarderTheme.colors
    val (color, onColor) = when (grade.lowercase()) {
        "a" -> colors.nutriA to colors.onNutriA
        "b" -> colors.nutriB to colors.onNutriB
        "c" -> colors.nutriC to colors.onNutriC
        "d" -> colors.nutriD to colors.onNutriD
        else -> colors.nutriE to colors.onNutriE
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(color)
        ) {
            Text(
                text = grade.uppercase(),
                color = onColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.product_detail_nutriscore),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** NOVA 1..4, least to most processed. */
@Composable
private fun NovaBadge(group: Int) {
    val colors = LarderTheme.colors
    val (color, onColor) = when (group) {
        1 -> colors.nutriA to colors.onNutriA
        2 -> colors.nutriB to colors.onNutriB
        3 -> colors.nutriD to colors.onNutriD
        else -> colors.nutriE to colors.onNutriE
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(44.dp).clip(CircleShape).background(color)
        ) {
            Text(
                text = group.toString(),
                color = onColor,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.product_detail_nova),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
