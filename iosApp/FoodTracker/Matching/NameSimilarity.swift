import Foundation

/// Fuzzy comparison of two grocery product names.
///
/// Hand-ported from `shared`'s `com.foodtracker.match.NameSimilarity`, which
/// `ShoppingListMatcher` also scores with on both platforms. Change the two together.
public enum NameSimilarity {

    /// Similarity score in 0.0-1.0: exact match after normalization wins outright, a substring
    /// containment (common for "Milk" vs "Organic Whole Milk") scores just below that,
    /// otherwise Jaccard overlap of whitespace-split tokens — grocery names typically differ by
    /// extra brand/pack-size words rather than typos, which token overlap rewards better than
    /// edit distance would.
    public static func score(_ a: String, _ b: String) -> Double {
        let normA = normalize(a)
        let normB = normalize(b)
        if normA.isEmpty || normB.isEmpty { return 0 }
        if normA == normB { return 1 }
        if normA.contains(normB) || normB.contains(normA) { return 0.9 }

        let tokensA = tokenize(normA)
        let tokensB = tokenize(normB)
        let union = tokensA.union(tokensB)
        if union.isEmpty { return 0 }
        return Double(tokensA.intersection(tokensB).count) / Double(union.count)
    }

    /// Case-folded, with every run of non-alphanumerics collapsed to a single space.
    public static func normalize(_ s: String) -> String {
        let folded = s.lowercased().map { character -> Character in
            character.isLetter || character.isNumber ? character : " "
        }
        return String(folded)
            .split(separator: " ", omittingEmptySubsequences: true)
            .joined(separator: " ")
    }

    /// The best-scoring entry of `candidates`, or nil when nothing reaches `threshold`.
    public static func bestMatch(_ query: String, candidates: [String], threshold: Double) -> String? {
        candidates
            .map { ($0, score(query, $0)) }
            .filter { $0.1 >= threshold }
            .max { $0.1 < $1.1 }?
            .0
    }

    private static func tokenize(_ normalized: String) -> Set<String> {
        Set(normalized.split(separator: " ").map(String.init))
    }
}
