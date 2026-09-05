import Foundation

/// Below this, a shopping list entry isn't shown as a "did you mean" suggestion at all.
/// Kept in sync by hand with `shared`'s `ShoppingListMatcher.kt` (iOS doesn't consume `shared`).
public let shoppingMatchSuggestionThreshold = 0.3
private let maxSuggestions = 3

/// Outcome of matching a scanned product against the current shopping list.
public enum ShoppingListMatchResult {
    /// The scanned barcode exactly matches a stored entry — treated as certain, no confirmation needed.
    case exactBarcodeMatch(ShoppingListEntry)
    /// No exact barcode match, but 1-3 entries scored above `shoppingMatchSuggestionThreshold`
    /// by name similarity, sorted highest score first.
    case suggestions([ShoppingListEntry])
    /// No barcode match and nothing similar enough by name.
    case noMatch
}

public enum ShoppingListMatcher {

    public static func match(
        scannedName: String?,
        scannedBarcode: String?,
        shoppingList: [ShoppingListEntry]
    ) -> ShoppingListMatchResult {
        if let scannedBarcode, let exact = shoppingList.first(where: { $0.barcode == scannedBarcode }) {
            return .exactBarcodeMatch(exact)
        }
        guard let scannedName, !scannedName.trimmingCharacters(in: .whitespaces).isEmpty else {
            return .noMatch
        }

        // Decorate-sort-undecorate on (score, originalIndex): Swift's `sorted(by:)` stability
        // isn't contractually guaranteed the way Kotlin's `sortedByDescending` is, so tie-break
        // on original list order explicitly to keep both platforms' ranking identical.
        var scored: [(entry: ShoppingListEntry, score: Double, index: Int)] = []
        for (index, entry) in shoppingList.enumerated() {
            let score: Double = similarity(entry.productName, scannedName)
            if score >= shoppingMatchSuggestionThreshold {
                scored.append((entry: entry, score: score, index: index))
            }
        }
        scored.sort { lhs, rhs in
            if lhs.score != rhs.score { return lhs.score > rhs.score }
            return lhs.index < rhs.index
        }
        let ranked: [ShoppingListEntry] = scored.prefix(maxSuggestions).map { $0.entry }

        return ranked.isEmpty ? .noMatch : .suggestions(ranked)
    }

    /// Similarity score in 0.0-1.0: exact match after normalization wins outright, a substring
    /// containment (common for "Milk" vs "Organic Whole Milk") scores just below that, otherwise
    /// falls back to Jaccard overlap of whitespace-split tokens — grocery names typically differ
    /// by extra brand/pack-size words rather than typos, which token overlap rewards better than
    /// edit-distance would.
    static func similarity(_ a: String, _ b: String) -> Double {
        let normA = normalize(a)
        let normB = normalize(b)
        if normA.isEmpty || normB.isEmpty { return 0.0 }
        if normA == normB { return 1.0 }
        if normA.contains(normB) || normB.contains(normA) { return 0.9 }

        let tokensA = tokenize(normA)
        let tokensB = tokenize(normB)
        let union = tokensA.union(tokensB)
        if union.isEmpty { return 0.0 }
        let intersection = tokensA.intersection(tokensB)
        return Double(intersection.count) / Double(union.count)
    }

    static func normalize(_ s: String) -> String {
        let collapsed = s.trimmingCharacters(in: .whitespaces)
            .lowercased()
            .replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression)
        return collapsed.trimmingCharacters(in: .whitespaces)
    }

    private static func tokenize(_ normalized: String) -> Set<String> {
        Set(normalized.split(separator: " ").map(String.init))
    }
}
