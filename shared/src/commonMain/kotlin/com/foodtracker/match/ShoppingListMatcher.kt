package com.foodtracker.match

import com.foodtracker.model.ShoppingListEntry

/** Below this, a shopping list entry isn't shown as a "did you mean" suggestion at all. */
const val SHOPPING_MATCH_SUGGESTION_THRESHOLD = 0.3
private const val MAX_SUGGESTIONS = 3

/** Outcome of matching a scanned product against the current shopping list. */
sealed class ShoppingListMatchResult {
    /** The scanned barcode exactly matches a stored entry — treated as certain, no confirmation needed. */
    data class ExactBarcodeMatch(val entry: ShoppingListEntry) : ShoppingListMatchResult()

    /** No exact barcode match, but 1-3 entries scored above [SHOPPING_MATCH_SUGGESTION_THRESHOLD]
     *  by name similarity, sorted highest score first. */
    data class Suggestions(val candidates: List<ShoppingListEntry>) : ShoppingListMatchResult()

    /** No barcode match and nothing similar enough by name. */
    data object NoMatch : ShoppingListMatchResult()
}

object ShoppingListMatcher {

    fun match(
        scannedName: String?,
        scannedBarcode: String?,
        shoppingList: List<ShoppingListEntry>
    ): ShoppingListMatchResult {
        if (scannedBarcode != null) {
            shoppingList.find { it.barcode == scannedBarcode }?.let {
                return ShoppingListMatchResult.ExactBarcodeMatch(it)
            }
        }
        if (scannedName.isNullOrBlank()) return ShoppingListMatchResult.NoMatch

        val ranked = shoppingList
            .map { it to similarity(scannedName, it.productName) }
            .filter { it.second >= SHOPPING_MATCH_SUGGESTION_THRESHOLD }
            .sortedByDescending { it.second }
            .take(MAX_SUGGESTIONS)
            .map { it.first }

        return if (ranked.isEmpty()) ShoppingListMatchResult.NoMatch
        else ShoppingListMatchResult.Suggestions(ranked)
    }

    /** Similarity score in 0.0-1.0: exact match after normalization wins outright, a substring
     *  containment (common for "Milk" vs "Organic Whole Milk") scores just below that, otherwise
     *  falls back to Jaccard overlap of whitespace-split tokens — grocery names typically differ
     *  by extra brand/pack-size words rather than typos, which token overlap rewards better than
     *  edit-distance would. */
    internal fun similarity(a: String, b: String): Double {
        val normA = normalize(a)
        val normB = normalize(b)
        if (normA.isEmpty() || normB.isEmpty()) return 0.0
        if (normA == normB) return 1.0
        if (normA.contains(normB) || normB.contains(normA)) return 0.9

        val tokensA = tokenize(normA)
        val tokensB = tokenize(normB)
        val union = tokensA union tokensB
        if (union.isEmpty()) return 0.0
        val intersection = tokensA intersect tokensB
        return intersection.size.toDouble() / union.size
    }

    internal fun normalize(s: String): String =
        s.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun tokenize(normalized: String): Set<String> =
        normalized.split(' ').filter { it.isNotBlank() }.toSet()
}
