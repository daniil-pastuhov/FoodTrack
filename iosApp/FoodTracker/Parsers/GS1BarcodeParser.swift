import Foundation

/// Swift port of `shared`'s `GS1BarcodeParser.kt`. iOS does not consume the shared KMP module
/// (see root CLAUDE.md), so this reimplements the same GS1 Application Identifier parsing by
/// hand to give iOS the same "read the expiry date straight off the barcode" fast path Android
/// gets via the shared parser, instead of always falling back to OCR or manual entry.
public enum GS1BarcodeParser {

    /// Extracts an expiration date or best-before date from a GS1 barcode if present.
    /// Application Identifiers:
    /// - (17) / 17: Expiration date in format YYMMDD
    /// - (15) / 15: Best before date in format YYMMDD
    public static func extractExpiryDate(from rawBarcode: String) -> Date? {
        let clean = rawBarcode.trimmingCharacters(in: .whitespaces)

        // 1. Bracketed format: (17)YYMMDD or (15)YYMMDD
        if let match = firstCapture(pattern: "\\((?:17|15)\\)\\s*(\\d{6})", in: clean) {
            return parseYYMMDD(match)
        }

        // 2. Unbracketed format with AI 17 or 15, usually preceded by (01) GTIN 14 digits,
        // e.g. 010123456789012817YYMMDD
        if let match = firstCapture(pattern: "(?:01\\d{14}|01\\(\\d{14}\\))(?:17|15)(\\d{6})", in: clean) {
            return parseYYMMDD(match)
        }

        // 3. Simple prefix starting with 17 or 15 followed by 6 digits
        if let match = firstCapture(pattern: "^(?:17|15)(\\d{6})", in: clean) {
            return parseYYMMDD(match)
        }

        return nil
    }

    /// Returns the substring matched by the pattern's one capturing group, from the first match.
    private static func firstCapture(pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let nsText = text as NSString
        guard let match = regex.firstMatch(in: text, range: NSRange(location: 0, length: nsText.length)),
              match.numberOfRanges > 1,
              match.range(at: 1).location != NSNotFound else { return nil }
        return nsText.substring(with: match.range(at: 1))
    }

    private static func parseYYMMDD(_ yymmdd: String) -> Date? {
        guard yymmdd.count == 6 else { return nil }
        let chars = Array(yymmdd)
        guard let yy = Int(String(chars[0...1])), let mm = Int(String(chars[2...3])) else { return nil }
        guard (1...12).contains(mm) else { return nil }
        var dd = Int(String(chars[4...5])) ?? -1

        // Century rule: YY from 00 to 50 is 2000-2050, 51-99 is 1951-1999
        let year = yy < 50 ? 2000 + yy : 1900 + yy
        let maxDay = daysInMonth(year: year, month: mm)

        // GS1 standard: if DD is 00, it represents the last day of the specified month
        if dd == 0 {
            dd = maxDay
        } else if !(1...maxDay).contains(dd) {
            return nil
        }

        var components = DateComponents()
        components.year = year
        components.month = mm
        components.day = dd
        return Calendar(identifier: .gregorian).date(from: components)
    }

    private static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        case 2: return isLeapYear(year) ? 29 : 28
        default: return 30
        }
    }

    private static func isLeapYear(_ year: Int) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }
}
