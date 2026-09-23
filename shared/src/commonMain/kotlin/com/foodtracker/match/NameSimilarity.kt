package com.foodtracker.match

/**
 * Fuzzy comparison of two grocery product names.
 *
 * Extracted from [ShoppingListMatcher] (which still uses it, unchanged) so receipt scanning can
 * match an OCR'd line against the products the app already knows without a second, subtly
 * different implementation of the same idea.
 */
object NameSimilarity {

    /**
     * Similarity score in 0.0-1.0: exact match after normalization wins outright, a substring
     * containment (common for "Milk" vs "Organic Whole Milk") scores just below that, otherwise
     * falls back to Jaccard overlap of whitespace-split tokens — grocery names typically differ
     * by extra brand/pack-size words rather than typos, which token overlap rewards better than
     * edit-distance would.
     */
    fun score(a: String, b: String): Double {
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

    /** Case-folded, with every run of non-alphanumerics collapsed to a single space. */
    fun normalize(s: String): String =
        s.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** The best-scoring entry of [candidates], or null when nothing reaches [threshold]. */
    fun bestMatch(query: String, candidates: List<String>, threshold: Double): String? =
        candidates
            .map { it to score(query, it) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first

    private fun tokenize(normalized: String): Set<String> =
        normalized.split(' ').filter { it.isNotBlank() }.toSet()
}
