import Foundation

/// One entry as it comes back from an import. Deliberately not a `ShoppingListEntry`: an
/// imported row has no id, no added date and no sort order of its own — those are assigned by
/// the receiving device when the entry is actually added, so an import lands in that device's
/// own ordering rather than carrying a foreign one.
public struct ImportedShoppingEntry: Equatable {
    public let productName: String
    public let quantity: Int
    public let category: String?
    /// The sender's section name, or nil for their default section. Matched by name on the
    /// receiving device, since section ids are local to each device.
    public let section: String?

    public init(productName: String, quantity: Int = 1, category: String? = nil, section: String? = nil) {
        self.productName = productName
        self.quantity = quantity
        self.category = category
        self.section = section
    }
}

/// Exports and imports the shopping list as text or as a QR payload.
///
/// A hand-written port of `shared`'s `com.foodtracker.share.ShoppingListShare` - nothing under
/// `iosApp/` consumes that module. The payload format is a cross-platform contract: a change to
/// `payloadPrefix` or the JSON field names breaks scanning between an Android and an iOS device
/// and has to be made on both sides together.
public enum ShoppingListShare {

    /// Marks a QR code as one of ours. The scanner feeds every QR it sees through this before
    /// treating the contents as a product barcode, so without a prefix a shared list would be
    /// looked up against Open Food Facts as if it were an EAN.
    public static let payloadPrefix = "foodtracker:sl:v1:"

    /// Guard against pathological input - a pasted novel, or a QR payload built to blow up the
    /// receiving device. Well past any realistic shopping list.
    private static let maxImportedEntries = 500
    private static let maxQuantity = 999

    // Short field names: a QR code's capacity is the binding constraint here, and "productName"
    // repeated 30 times is a meaningful share of it.
    private struct PayloadEntry: Codable {
        let n: String
        let q: Int?
        let c: String?
        let s: String?
    }

    private struct Payload: Codable {
        let v: Int?
        let e: [PayloadEntry]?
    }

    /// The list as human-readable text, under `title`, grouped the way the list itself is: the
    /// default section's entries first without a heading, then each of `sections` that has
    /// entries under its name, in order. `entries` are expected in display order already.
    ///
    /// `sectionLabel` names a user section (the caller translates built-in names the same way
    /// the screen does). Quantities above one are appended as "(2)", which is also what
    /// `parseText` reads back.
    public static func toText(
        entries: [ShoppingListEntry],
        title: String,
        sections: [ShoppingSection] = [],
        sectionLabel: (ShoppingSection) -> String = { $0.name }
    ) -> String {
        guard !entries.isEmpty else { return title }

        let ordered = ShoppingSections.ordered(sections)
        let ids = Set(ordered.map(\.id))
        let grouped = Dictionary(grouping: entries) { ShoppingSections.resolve($0.sectionId, in: ids) }
        var out = title
        for section in ordered {
            guard let group = grouped[section.id], !group.isEmpty else { continue }
            out += "\n"
            if !section.isDefault { out += "\n" + sectionLabel(section) }
            for entry in group {
                out += "\n- " + entry.productName
                if entry.quantity > 1 { out += " (\(entry.quantity))" }
            }
        }
        return out
    }

    /// The list as a QR payload: `payloadPrefix` followed by compact JSON. Each entry carries its
    /// section's name (none for the default section) so the receiver can file it the same way.
    public static func toPayload(entries: [ShoppingListEntry], sections: [ShoppingSection] = []) -> String {
        let names = Dictionary(
            sections.filter { !$0.isDefault }.map { ($0.id, $0.name) },
            uniquingKeysWith: { first, _ in first }
        )
        let payload = Payload(
            v: 1,
            e: entries.prefix(maxImportedEntries).map {
                PayloadEntry(n: $0.productName, q: $0.quantity, c: $0.category, s: $0.sectionId.flatMap { names[$0] })
            }
        )
        let encoder = JSONEncoder()
        guard let data = try? encoder.encode(payload),
              let json = String(data: data, encoding: .utf8) else {
            return payloadPrefix
        }
        return payloadPrefix + json
    }

    /// True when `raw` looks like one of our QR payloads, so a scanner can route it here rather
    /// than treating it as a product barcode.
    public static func isPayload(_ raw: String) -> Bool {
        trimmingLeadingWhitespace(raw).hasPrefix(payloadPrefix)
    }

    /// Parses `raw` as either a QR payload or pasted text, whichever it looks like. Returns an
    /// empty array when nothing usable was found - callers show "nothing to import" rather than
    /// distinguishing malformed from empty.
    public static func parse(_ raw: String) -> [ImportedShoppingEntry] {
        isPayload(raw) ? parsePayload(raw) : parseText(raw)
    }

    /// Parses a QR payload produced by `toPayload`. A payload whose prefix is present but whose
    /// JSON is malformed yields an empty array rather than throwing - it reached us off a
    /// camera, so it may well be a partial read.
    public static func parsePayload(_ raw: String) -> [ImportedShoppingEntry] {
        let body = String(trimmingLeadingWhitespace(raw).dropFirst(payloadPrefix.count))
        guard let data = body.data(using: .utf8),
              let payload = try? JSONDecoder().decode(Payload.self, from: data) else {
            return []
        }
        return (payload.e ?? []).compactMap { entry -> ImportedShoppingEntry? in
            let name = entry.n.trimmingCharacters(in: .whitespaces)
            guard !name.isEmpty else { return nil }
            let category = entry.c?.trimmingCharacters(in: .whitespaces)
            let section = entry.s?.trimmingCharacters(in: .whitespaces)
            return ImportedShoppingEntry(
                productName: name,
                quantity: min(max(entry.q ?? 1, 1), maxQuantity),
                category: (category?.isEmpty ?? true) ? nil : category,
                section: (section?.isEmpty ?? true) ? nil : section
            )
        }
        .prefixArray(maxImportedEntries)
    }

    /// Parses pasted text.
    ///
    /// Handles both text this app exported and a list someone typed by hand. The two need
    /// different rules: an export has category headers, which are plain lines and must not
    /// become items, while a hand-typed list is usually all plain lines and all of them are
    /// items. So if *any* line is bulleted, only bulleted lines count; otherwise every
    /// non-blank line does. A trailing "(2)" is read back as a quantity either way.
    ///
    /// Categories are deliberately not recovered from headers: the receiving device resolves a
    /// category from the product itself, and importing a foreign display name would create a
    /// duplicate category that happens to read the same.
    public static func parseText(_ raw: String) -> [ImportedShoppingEntry] {
        let lines = raw.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let bulleted = lines.filter { isBulleted($0) }
        let candidates = bulleted.isEmpty ? lines : bulleted
        return candidates
            .map { stripBullet($0) }
            .compactMap { parseLine($0) }
            .prefixArray(maxImportedEntries)
    }

    private static func isBulleted(_ line: String) -> Bool {
        line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("• ")
    }

    private static func stripBullet(_ line: String) -> String {
        for prefix in ["- ", "* ", "• "] where line.hasPrefix(prefix) {
            return String(line.dropFirst(prefix.count)).trimmingCharacters(in: .whitespaces)
        }
        return line
    }

    /// Splits a trailing "(2)" off the name and reads it as a quantity. A parenthesised suffix
    /// that isn't a plain number is left as part of the name - "Milk (organic)" is a product,
    /// not a quantity.
    private static func parseLine(_ line: String) -> ImportedShoppingEntry? {
        guard !line.isEmpty else { return nil }
        if let match = line.range(of: #"\s*\((\d{1,3})\)\s*$"#, options: .regularExpression) {
            let name = String(line[line.startIndex..<match.lowerBound]).trimmingCharacters(in: .whitespaces)
            let digits = line[match].filter { $0.isNumber }
            if !name.isEmpty, let quantity = Int(digits) {
                return ImportedShoppingEntry(productName: name, quantity: min(max(quantity, 1), maxQuantity))
            }
        }
        return ImportedShoppingEntry(productName: line)
    }

    private static func trimmingLeadingWhitespace(_ raw: String) -> String {
        guard let index = raw.firstIndex(where: { !$0.isWhitespace }) else { return "" }
        return String(raw[index...])
    }
}

private extension Array {
    func prefixArray(_ maxLength: Int) -> [Element] { Array(prefix(maxLength)) }
}
