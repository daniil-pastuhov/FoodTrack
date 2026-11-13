import Foundation

/// A user-made group on the shopping list ("Dairy aisle", "Pharmacy", ...). Mirrors `shared`'s
/// `ShoppingSection` (iOS doesn't consume that module), including the JSON field names, which
/// backups share across platforms.
///
/// Everything lands in the default section (`ShoppingSection.defaultId`) unless the user has put
/// it somewhere else, and a product remembers its section for the next time it is added (see
/// `ProductNameRecord.shoppingSectionId`). The default section always exists, is always first and
/// has no stored name: its label is localized at display time. `collapsed` is persisted, so the
/// app and the widget agree on what is folded away.
public struct ShoppingSection: Identifiable, Codable, Equatable, Hashable {
    public static let defaultId = "default"
    /// The default section as a fresh install has it. Its order sits below anything a user
    /// section could be given, so plain sort-by-order keeps it first.
    public static let defaultSection = ShoppingSection(
        id: defaultId, name: "", sortOrder: -Double.greatestFiniteMagnitude
    )

    public let id: String
    public var name: String
    public var sortOrder: Double
    public var collapsed: Bool

    public init(id: String, name: String, sortOrder: Double = 0, collapsed: Bool = false) {
        self.id = id
        self.name = name
        self.sortOrder = sortOrder
        self.collapsed = collapsed
    }

    public var isDefault: Bool { id == Self.defaultId }

    private enum CodingKeys: String, CodingKey { case id, name, sortOrder, collapsed }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        sortOrder = try container.decodeIfPresent(Double.self, forKey: .sortOrder) ?? 0
        collapsed = try container.decodeIfPresent(Bool.self, forKey: .collapsed) ?? false
    }
}

/// One row of the sectioned shopping list: a section header or an entry. A collapsed section
/// contributes only its header. `item`'s section id is the *resolved* one (an entry pointing at
/// a deleted section shows in the default one).
public enum ShoppingRow: Identifiable, Equatable {
    case header(ShoppingSection, itemCount: Int)
    case item(ShoppingListEntry, sectionId: String)

    public var id: String {
        switch self {
        case .header(let section, _): return "header_\(section.id)"
        case .item(let entry, _): return entry.id
        }
    }
}

/// Grouping shared by the app's list, its share text and the widget. A port of `shared`'s
/// `PantryDerivation.buildShoppingRows` / `orderedShoppingSections` - keep the two in step.
public enum ShoppingSections {
    /// The section `sectionId` is shown in: itself if it still exists, else the default one.
    public static func resolve(_ sectionId: String?, in existingIds: Set<String>) -> String {
        if let sectionId, existingIds.contains(sectionId) { return sectionId }
        return ShoppingSection.defaultId
    }

    /// `sections` in display order, with the default section first (and present even if the
    /// store somehow lost it).
    public static func ordered(_ sections: [ShoppingSection]) -> [ShoppingSection] {
        let defaultSection = sections.first(where: { $0.isDefault }) ?? .defaultSection
        let others = sections.filter { !$0.isDefault }.sorted {
            if $0.sortOrder != $1.sortOrder { return $0.sortOrder < $1.sortOrder }
            return $0.name.lowercased() < $1.name.lowercased()
        }
        return [defaultSection] + others
    }

    /// `entries` (already in the user's order) grouped under every section's header. An empty
    /// section still gets a header, so it can be dropped onto.
    public static func rows(entries: [ShoppingListEntry], sections: [ShoppingSection]) -> [ShoppingRow] {
        let ordered = ordered(sections)
        let ids = Set(ordered.map(\.id))
        let grouped = Dictionary(grouping: entries) { resolve($0.sectionId, in: ids) }
        var rows: [ShoppingRow] = []
        for section in ordered {
            let group = grouped[section.id] ?? []
            rows.append(.header(section, itemCount: group.count))
            if !section.collapsed {
                rows.append(contentsOf: group.map { .item($0, sectionId: section.id) })
            }
        }
        return rows
    }
}
