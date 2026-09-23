import Foundation

/// One product line recovered from a till receipt.
///
/// Mirrors `shared`'s `com.foodtracker.parser.ReceiptLine`.
public struct ReceiptLine: Equatable {
    public let rawText: String
    public let name: String
    public let quantity: Int
    /// How sure the parser is that this is a product at all, not how sure it is of the name.
    public let confidence: Double
}

/// Pulls product lines out of the raw OCR text of a supermarket receipt.
///
/// Hand-ported from `shared`'s `ReceiptParser`; the two must be changed together, and the
/// Kotlin one carries the full reasoning. In short: the problem is subtraction, not
/// recognition — a receipt is mostly store header, address, date, price column, totals block,
/// payment method, VAT summary and loyalty footer. A product line is distinguished by carrying
/// a price, which holds across retailers in a way any keyword list does not.
public enum ReceiptParser {

    /// Below this a line isn't offered at all.
    public static let minConfidence = 0.5

    /// Longest plausible quantity on a grocery receipt line.
    private static let maxQuantity = 99

    private static let nonProductKeywords = [
        // Totals
        "total", "subtotal", "sum", "totalt", "delsum", "å betale", "a betale",
        "итого", "итог", "сумма", "к оплате",
        // Tax
        "vat", "mva", "tax", "ндс", "налог",
        // Payment and change
        "cash", "card", "visa", "mastercard", "debit", "credit", "contactless",
        "kontant", "kort", "bankaxept", "vipps",
        "наличные", "карта", "оплата", "сдача", "change", "veksel",
        // Receipt furniture
        "receipt", "kvittering", "чек", "кассир", "cashier", "terminal", "till",
        "thank you", "takk for", "спасибо", "welcome", "velkommen",
        "org.nr", "orgnr", "vat no", "инн", "ккт", "смена",
        "balance", "saldo", "bonus", "points", "poeng", "баллы",
        "discount", "rabatt", "скидка", "savings"
    ]

    private static let trailingPrice = regex(#"[*xX£€$₽]?\s*\d{1,4}[.,]\d{2}\s*[A-Za-zА-Яа-я]?\s*$"#)
    private static let leadingQuantity = regex(#"^(\d{1,2})\s*[x×@]\s*"#)
    private static let trailingQuantity = regex(#"\s*[x×]\s*(\d{1,2})\s*$"#)
    private static let continuationLine =
        regex(#"^\s*(\d+[.,]?\d*\s*(kg|g|l|ml|stk|pcs)\b\s*[@x×]|[@x×]\s*\d).*$"#)
    private static let dateOrCodeLine =
        regex(#"^\s*(\d{1,4}[-./]\d{1,2}[-./]\d{1,4}|\d{1,2}:\d{2}|[\d\s*#-]{6,})\s*$"#)
    private static let leadingSeparator = regex(#"^\s*[@*xX×-]\s*"#)
    private static let trailingUnitPrice = regex(#"\s*\d{1,4}[.,]\d{2}\s*(/\s*\w+)?\s*$"#)
    private static let whitespaceRun = regex(#"\s+"#)

    private static func regex(_ pattern: String) -> NSRegularExpression {
        // Every pattern here is a literal checked by the tests, so a failure would be a build-
        // time typo rather than anything a user could reach.
        try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    /// Parses `ocrLines` into the product lines worth offering, in the order they appeared.
    ///
    /// One line of lookahead: a product sold by weight prints its name on one line and its
    /// "0.412 kg @ 29.90/kg" on the next, so a name with no price of its own still counts when
    /// such a line follows it.
    public static func parseLines(_ ocrLines: [String]) -> [ReceiptLine] {
        ocrLines.indices.compactMap { index in
            parseLine(ocrLines[index], nextLine: index + 1 < ocrLines.count ? ocrLines[index + 1] : nil)
        }
        .filter { $0.confidence >= minConfidence }
    }

    static func parseLine(_ rawLine: String, nextLine: String? = nil) -> ReceiptLine? {
        let trimmed = rawLine.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return nil }
        if isNonProduct(trimmed) { return nil }

        let hasOwnPrice = matches(trailingPrice, trimmed, whole: false)
        let pricedByNextLine = nextLine.map {
            matches(continuationLine, $0.trimmingCharacters(in: .whitespaces), whole: true)
        } ?? false
        if !hasOwnPrice && !pricedByNextLine { return nil }

        var working = replacing(trailingPrice, in: trimmed, with: "")
            .trimmingCharacters(in: .whitespaces)

        var quantity = 1
        if let (value, range) = firstGroupInt(leadingQuantity, in: working), (1...maxQuantity).contains(value) {
            quantity = value
            working = working.replacingCharacters(in: range, with: "")
                .trimmingCharacters(in: .whitespaces)
        }
        if quantity == 1,
           let (value, range) = firstGroupInt(trailingQuantity, in: working), (1...maxQuantity).contains(value) {
            quantity = value
            working = working.replacingCharacters(in: range, with: "")
                .trimmingCharacters(in: .whitespaces)
        }

        working = replacing(leadingSeparator, in: working, with: "")
        working = replacing(trailingUnitPrice, in: working, with: "")
        working = working.trimmingCharacters(in: CharacterSet(charactersIn: "-*.,: "))

        let name = normalizeName(working)
        if name.isEmpty { return nil }

        return ReceiptLine(
            rawText: trimmed,
            name: name,
            quantity: quantity,
            confidence: confidence(for: name)
        )
    }

    private static func isNonProduct(_ line: String) -> Bool {
        let lowered = line.lowercased()
        if nonProductKeywords.contains(where: { lowered.contains($0) }) { return true }
        if matches(continuationLine, line, whole: true) { return true }
        if matches(dateOrCodeLine, line, whole: true) { return true }
        return false
    }

    /// Collapses whitespace runs and drops padding punctuation, while leaving the name
    /// otherwise as printed: `MLK SEMI 2PT` stays abbreviated rather than becoming a guess.
    private static func normalizeName(_ candidate: String) -> String {
        replacing(whitespaceRun, in: candidate, with: " ")
            .trimmingCharacters(in: .whitespaces)
            .trimmingCharacters(in: CharacterSet(charactersIn: ".,-*: "))
    }

    /// Letters are what distinguish a product from a code, so the score is driven by how much
    /// of the name is alphabetic and how much of it there is.
    private static func confidence(for name: String) -> Double {
        let letters = name.filter { $0.isLetter }.count
        if letters < 2 { return 0 }

        let letterFraction = Double(letters) / Double(name.count)
        var score = 0.45 + 0.45 * letterFraction
        if letters < 3 {
            score -= 0.25
        } else if name.count > 40 {
            score -= 0.20
        }
        return min(max(score, 0), 1)
    }

    // MARK: - NSRegularExpression helpers

    private static func matches(_ expression: NSRegularExpression, _ text: String, whole: Bool) -> Bool {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = expression.firstMatch(in: text, range: range) else { return false }
        return whole ? match.range == range : true
    }

    private static func replacing(_ expression: NSRegularExpression, in text: String, with template: String) -> String {
        expression.stringByReplacingMatches(
            in: text,
            range: NSRange(text.startIndex..., in: text),
            withTemplate: template
        )
    }

    /// The first capture group of `expression` as an Int, with the range of the whole match so
    /// the caller can cut it out.
    private static func firstGroupInt(
        _ expression: NSRegularExpression,
        in text: String
    ) -> (Int, Range<String.Index>)? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = expression.firstMatch(in: text, range: range),
              let groupRange = Range(match.range(at: 1), in: text),
              let wholeRange = Range(match.range, in: text),
              let value = Int(text[groupRange]) else {
            return nil
        }
        return (value, wholeRange)
    }
}
