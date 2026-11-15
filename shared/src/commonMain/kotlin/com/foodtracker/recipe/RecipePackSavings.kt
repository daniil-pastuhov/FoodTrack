package com.foodtracker.recipe

/** Below this a larger pack shows no saving: a 4 % difference isn't worth a badge. */
const val RECIPE_PACK_SAVING_MIN_PERCENT = 5

/**
 * How much less a recipe costs in each larger pack than in the smallest one, in whole percent,
 * from the prices the store returned (never a hard-coded price): product id to percent, only for
 * the packs that get a badge. The iOS Buy sheet keeps a hand twin of this rule.
 *
 * The base is the pack with the fewest recipes among those with a positive price. Another pack
 * counts only with more recipes than the base, a positive price and the base's currency code; its
 * saving is 1 − (price / recipes) / (basePrice / baseRecipes), floored to a whole percent in
 * integer arithmetic on micros (44.9 % is 44), and shown only from [RECIPE_PACK_SAVING_MIN_PERCENT].
 */
fun recipePackSavingPercents(packs: List<StorePack>): Map<String, Int> {
    val base = packs
        .filter { it.recipes > 0 && it.priceMicros > 0 }
        .minByOrNull { it.recipes } ?: return emptyMap()
    // A base without a currency compares with nothing; it is not replaced by the next pack.
    if (base.currencyCode.isBlank()) return emptyMap()
    val savings = mutableMapOf<String, Int>()
    for (pack in packs) {
        if (pack.recipes <= base.recipes || pack.priceMicros <= 0 || pack.currencyCode != base.currencyCode) continue
        val percent = savingPercent(base.priceMicros, base.recipes, pack.priceMicros, pack.recipes) ?: continue
        if (percent >= RECIPE_PACK_SAVING_MIN_PERCENT) savings[pack.productId] = percent
    }
    return savings
}

/**
 * floor(100 × (basePrice × recipes − price × baseRecipes) / (basePrice × recipes)), the per-recipe
 * saving with both sides scaled by baseRecipes × recipes so nothing is divided before the end.
 * Null when the pack is not cheaper per recipe or a product would overflow.
 */
private fun savingPercent(basePrice: Long, baseRecipes: Int, price: Long, recipes: Int): Int? {
    val baseScaled = multiplyOrNull(basePrice, recipes.toLong()) ?: return null
    val scaled = multiplyOrNull(price, baseRecipes.toLong()) ?: return null
    val cheaper = baseScaled - scaled
    if (cheaper <= 0) return null
    val hundredfold = multiplyOrNull(cheaper, 100) ?: return null
    // Both positive, so integer division is the floor.
    return (hundredfold / baseScaled).toInt()
}

/** a × b for positive a and b, or null when it doesn't fit a Long. */
private fun multiplyOrNull(a: Long, b: Long): Long? = if (b != 0L && a > Long.MAX_VALUE / b) null else a * b

/** The badge's words, the same in every language: a minus sign (U+2212), the number, "%". */
fun recipePackSavingBadge(percent: Int): String = "−$percent%"
