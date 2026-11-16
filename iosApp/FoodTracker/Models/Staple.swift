import Foundation

// Staples: names the person always has (salt, oil, eggs). No date, no quantity, no status; they
// never notify and are never counted anywhere the pantry is. Kept apart from the pantry in their
// own SwiftData model (`StapleRecord`). A hand-kept twin of Android's `shared/.../model/Staple.kt`
// and `shared/.../staples/` (`StaplePresets`, `StapleRules.kt`): the same table, ids, order and rules.
//
// Foundation only, and compiled into the widget extension too (it builds `NativePantryViewModel`),
// so nothing here reaches the Recipe domain except behind `#if !WIDGET_EXTENSION`.

/// One staple. A preset stores its id and its name in the app's language when it was ticked; it is
/// shown and sent in the app's current language (`Staples.displayName`). An own entry has no
/// `presetId`. A `presetId` this version doesn't know (a later preset, from a backup) is shown by
/// `name` and ordered as an own entry.
public struct Staple: Codable, Equatable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var presetId: String?
    public var addedAtEpochMillis: Int64

    public init(id: String = UUID().uuidString, name: String, presetId: String? = nil, addedAtEpochMillis: Int64 = 0) {
        self.id = id
        self.name = name
        self.presetId = presetId
        self.addedAtEpochMillis = addedAtEpochMillis
    }

    private enum CodingKeys: String, CodingKey { case id, name, presetId, addedAtEpochMillis }

    /// Lenient as Android's defaults: `presetId` absent or null is none, `addedAtEpochMillis`
    /// absent is 0.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        presetId = (try? c.decodeIfPresent(String.self, forKey: .presetId)).flatMap { $0 }
        addedAtEpochMillis = (try? c.decodeIfPresent(Int64.self, forKey: .addedAtEpochMillis)).flatMap { $0 } ?? 0
    }

    /// `presetId` written as null when none, as Android's backup writes it.
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encode(presetId, forKey: .presetId)
        try c.encode(addedAtEpochMillis, forKey: .addedAtEpochMillis)
    }
}

/// A preset staple: its id and its name in each of the app's eight languages.
public struct StaplePreset: Equatable, Sendable {
    public let id: String
    public let names: [String: String]

    /// The name in `language` (one of `Staples.languages`), English otherwise.
    public func name(_ language: String) -> String { names[language] ?? names["en"] ?? id }
}

/// The presets, in table order, which is also the order they are shown and sent in. Code rather
/// than strings files, because matching needs every language at once. Android: `StaplePresets`.
public enum StaplePresets {
    /// Columns: en, ru, nb, es, de, fr, sv, da.
    private static func preset(_ id: String, _ n: [String]) -> StaplePreset {
        StaplePreset(id: id, names: Dictionary(uniqueKeysWithValues: zip(Staples.languages, n)))
    }

    public static let all: [StaplePreset] = [
        preset("salt", ["Salt", "Соль", "Salt", "Sal", "Salz", "Sel", "Salt", "Salt"]),
        preset("black_pepper", ["Black pepper", "Чёрный перец", "Sort pepper", "Pimienta negra", "Schwarzer Pfeffer", "Poivre noir", "Svartpeppar", "Sort peber"]),
        preset("sugar", ["Sugar", "Сахар", "Sukker", "Azúcar", "Zucker", "Sucre", "Socker", "Sukker"]),
        preset("flour", ["Flour", "Мука", "Hvetemel", "Harina", "Mehl", "Farine", "Vetemjöl", "Hvedemel"]),
        preset("cooking_oil", ["Cooking oil", "Растительное масло", "Matolje", "Aceite vegetal", "Speiseöl", "Huile végétale", "Matolja", "Madolie"]),
        preset("olive_oil", ["Olive oil", "Оливковое масло", "Olivenolje", "Aceite de oliva", "Olivenöl", "Huile d'olive", "Olivolja", "Olivenolie"]),
        preset("butter", ["Butter", "Сливочное масло", "Smør", "Mantequilla", "Butter", "Beurre", "Smör", "Smør"]),
        preset("eggs", ["Eggs", "Яйца", "Egg", "Huevos", "Eier", "Œufs", "Ägg", "Æg"]),
        preset("milk", ["Milk", "Молоко", "Melk", "Leche", "Milch", "Lait", "Mjölk", "Mælk"]),
        preset("rice", ["Rice", "Рис", "Ris", "Arroz", "Reis", "Riz", "Ris", "Ris"]),
        preset("pasta", ["Pasta", "Макароны", "Pasta", "Pasta", "Nudeln", "Pâtes", "Pasta", "Pasta"]),
        preset("garlic", ["Garlic", "Чеснок", "Hvitløk", "Ajo", "Knoblauch", "Ail", "Vitlök", "Hvidløg"]),
        preset("onions", ["Onions", "Лук", "Løk", "Cebollas", "Zwiebeln", "Oignons", "Lök", "Løg"]),
        preset("vinegar", ["Vinegar", "Уксус", "Eddik", "Vinagre", "Essig", "Vinaigre", "Vinäger", "Eddike"]),
    ]

    public static func byId(_ id: String) -> StaplePreset? { all.first { $0.id == id } }
}

/// One row of the Staples screen, and what the recipe routes read: the staple, the name shown
/// (and sent), and whether it is "out" (on the shopping list).
public struct StapleRow: Equatable, Sendable {
    public var staple: Staple
    public var name: String
    public var out: Bool
}

/// What adding a typed name comes to (`Staples.addCheck`).
public enum StapleAddCheck: Equatable, Sendable {
    case empty
    /// Already a staple, under this shown name.
    case duplicate(String)
    /// `Staples.maxCount` already.
    case full
    /// The name of a preset not ticked yet: tick that one instead.
    case preset(String)
    /// A new own entry, trimmed.
    case own(String)
}

/// The pure rules. Android: `StapleRules.kt`.
public enum Staples {
    /// Staples in all. Android: `MAX_STAPLES`.
    public static let maxCount = 20
    /// What the add field takes.
    public static let maxNameLength = 40
    /// The app's languages, in the presets' column order.
    public static let languages = ["en", "ru", "nb", "es", "de", "fr", "sv", "da"]
    /// The recipe languages `RecipeLanguage.app` reads the app's language among.
    private static let recipeLanguages: Set<String> = ["en", "nb", "ru", "de", "es", "fr", "it", "pl", "sv", "uk", "da"]

    /// The presets' language for a language code: its lower-cased base, `no` / `nn` as `nb`, and
    /// anything outside the eight app languages as `en`. Android: `stapleLanguage`.
    public static func language(_ code: String) -> String {
        var base = String(code.prefix { $0 != "-" && $0 != "_" }).lowercased()
        if base == "no" || base == "nn" { base = "nb" }
        return languages.contains(base) ? base : "en"
    }

    /// The app's language as `RecipeLanguage.app(preferredLocalizations:)` reads it, then
    /// `language(_:)`.
    public static func language(preferredLocalizations: [String]) -> String {
        for code in preferredLocalizations {
            var base = String(code.prefix { $0 != "-" && $0 != "_" }).lowercased()
            if base == "no" || base == "nn" { base = "nb" }
            if recipeLanguages.contains(base) { return language(base) }
        }
        return "en"
    }

    /// The app's language now.
    public static var appLanguage: String { language(preferredLocalizations: Bundle.main.preferredLocalizations) }

    /// A name's key: trimmed and lower-cased, the shopping list's own key.
    public static func key(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// The name shown and sent: a known preset's name in `language`, otherwise the stored name.
    public static func displayName(_ staple: Staple, language: String) -> String {
        if let id = staple.presetId, let preset = StaplePresets.byId(id) { return preset.name(language) }
        return staple.name
    }

    /// Every key the staple is known by: its stored name, its preset's name in every language,
    /// and each of those as the recipe request would clean it.
    public static func matchKeys(_ staple: Staple) -> Set<String> {
        var names = [staple.name]
        if let id = staple.presetId, let preset = StaplePresets.byId(id) { names += languages.map(preset.name) }
        var keys = Set<String>()
        for name in names {
            keys.insert(key(name))
            #if !WIDGET_EXTENSION
            if let cleaned = RecipeWire.cleanName(name) { keys.insert(key(cleaned)) }
            #endif
        }
        keys.remove("")
        return keys
    }

    /// Known presets in the table's order, then the rest (own entries, unknown presets) by when
    /// they were added, then id.
    public static func ordered(_ staples: [Staple]) -> [Staple] {
        func presetIndex(_ s: Staple) -> Int? {
            s.presetId.flatMap { id in StaplePresets.all.firstIndex { $0.id == id } }
        }
        return staples.sorted { a, b in
            switch (presetIndex(a), presetIndex(b)) {
            case let (x?, y?): return x != y ? x < y : (a.addedAtEpochMillis, a.id) < (b.addedAtEpochMillis, b.id)
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return (a.addedAtEpochMillis, a.id) < (b.addedAtEpochMillis, b.id)
            }
        }
    }

    /// Out while the shopping list has an entry under one of its keys: "Ran out" puts it there,
    /// and it is back as soon as the entry leaves the list by any path.
    public static func isOut(_ staple: Staple, shoppingNames: [String]) -> Bool {
        let keys = matchKeys(staple)
        return shoppingNames.contains { keys.contains(key($0)) }
    }

    public static func rows(_ staples: [Staple], shoppingNames: [String], language: String) -> [StapleRow] {
        ordered(staples).map { StapleRow(staple: $0, name: displayName($0, language: language), out: isOut($0, shoppingNames: shoppingNames)) }
    }

    /// The names the recipe routes get: every row not out, in order.
    public static func namesToSend(_ rows: [StapleRow]) -> [String] {
        rows.filter { !$0.out }.map(\.name)
    }

    /// The keys of every staple not out: what "Add to shopping list" leaves out. Android:
    /// `currentStapleKeys`.
    public static func currentKeys(_ rows: [StapleRow]) -> Set<String> {
        rows.filter { !$0.out }.reduce(into: Set<String>()) { $0.formUnion(matchKeys($1.staple)) }
    }

    /// What adding `typed` comes to, in this order: empty, already a staple, full, a preset not
    /// ticked yet, else a new own entry.
    public static func addCheck(_ staples: [Staple], typed: String, language: String) -> StapleAddCheck {
        let trimmed = typed.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return .empty }
        let k = key(trimmed)
        if let existing = ordered(staples).first(where: { matchKeys($0).contains(k) }) {
            return .duplicate(displayName(existing, language: language))
        }
        if staples.count >= maxCount { return .full }
        let ticked = Set(staples.compactMap(\.presetId))
        if let preset = StaplePresets.all.first(where: { p in !ticked.contains(p.id) && p.names.values.contains { key($0) == k } }) {
            return .preset(preset.id)
        }
        return .own(trimmed)
    }

    /// The staple a preset's tick stores: its name in the app's language now.
    public static func ticking(_ preset: StaplePreset, language: String, id: String = UUID().uuidString, now: Int64) -> Staple {
        Staple(id: id, name: preset.name(language), presetId: preset.id, addedAtEpochMillis: now)
    }

    /// The staples a route lists (`RecipeRequest.staples`): the shown names of the rows not out,
    /// in order, less any staple one of whose keys (`matchKeys`, every language) is the key of a
    /// name the route already lists (`ticked`, and `others` with "Send all products"), so a
    /// German "Eier" isn't sent beside a pantry item called "Eggs"; each name once. At most 20 by
    /// construction. Android: `promptStaples`.
    public static func promptStaples(_ rows: [StapleRow], ticked: [String], others: [String] = []) -> [String] {
        let listed = Set((ticked + others).map(key))
        var seen = Set<String>()
        return rows.filter { row in
            guard !row.out, matchKeys(row.staple).isDisjoint(with: listed) else { return false }
            let k = key(row.name)
            return !k.isEmpty && seen.insert(k).inserted
        }.map(\.name)
    }

    /// Undo of a removal: only while no staple shares one of its keys (it may have been ticked
    /// again meanwhile) and there are fewer than `maxCount`; otherwise nothing. Android: the same
    /// rule in `restoreStaple`.
    public static func mayRestore(_ staple: Staple, into existing: [Staple]) -> Bool {
        guard existing.count < maxCount, !existing.contains(where: { $0.id == staple.id }) else { return false }
        let keys = matchKeys(staple)
        return !existing.contains { !matchKeys($0).isDisjoint(with: keys) }
    }

    /// What the add field keeps of `text`: at most `maxNameLength` Unicode code points, never cut
    /// inside one.
    public static func limitedName(_ text: String) -> String {
        guard text.unicodeScalars.count > maxNameLength else { return text }
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: text.unicodeScalars.prefix(maxNameLength))
        return String(scalars)
    }

    // MARK: Onboarding

    /// What onboarding's Staples step changes: presets to add (ids) and staples to remove, each in
    /// table order.
    public struct PresetChanges: Equatable, Sendable {
        public var add: [String]
        public var remove: [Staple]
        public var isEmpty: Bool { add.isEmpty && remove.isEmpty }
    }

    /// The step's first ticks: the presets the person already has.
    public static func presetTicks(_ staples: [Staple]) -> Set<String> {
        Set(staples.compactMap(\.presetId).filter { StaplePresets.byId($0) != nil })
    }

    /// How many staples there would be with these ticks: what the step's 20 cap is checked against.
    public static func count(_ staples: [Staple], ticked: Set<String>) -> Int {
        let presets = presetTicks(staples)
        return staples.count - presets.subtracting(ticked).count + ticked.subtracting(presets).count
    }

    /// Continue: remove the presets unticked that were there, add the ticked ones that weren't,
    /// never past `maxCount`. Nothing changed is nothing to do (the same as Skip).
    public static func presetChanges(_ staples: [Staple], ticked: Set<String>) -> PresetChanges {
        let had = presetTicks(staples)
        let remove = ordered(staples).filter { s in s.presetId.map { had.contains($0) && !ticked.contains($0) } ?? false }
        var room = maxCount - (staples.count - remove.count)
        var add: [String] = []
        for preset in StaplePresets.all where ticked.contains(preset.id) && !had.contains(preset.id) && room > 0 {
            add.append(preset.id)
            room -= 1
        }
        return PresetChanges(add: add, remove: remove)
    }

    // MARK: Backup

    /// The backup's `staples`: absent (an older file) reads as none.
    public static func decodeBackup<K: CodingKey>(_ container: KeyedDecodingContainer<K>, forKey key: K) throws -> [Staple] {
        try container.decodeIfPresent([Staple].self, forKey: key) ?? []
    }

    /// Replace: the backup's staples, in order, one whose keys meet an earlier one's left out, at
    /// most `maxCount`.
    public static func toRestore(replacing incoming: [Staple]) -> [Staple] {
        toRestore(merging: incoming, into: [])
    }

    /// Merge: each backup staple none of whose keys an existing (or earlier added) one has, while
    /// there are fewer than `maxCount` in all.
    public static func toRestore(merging incoming: [Staple], into existing: [Staple]) -> [Staple] {
        var keys = existing.reduce(into: Set<String>()) { $0.formUnion(matchKeys($1)) }
        var ids = Set(existing.map(\.id))
        var count = existing.count
        var added: [Staple] = []
        for staple in ordered(incoming) {
            guard count < maxCount else { break }
            let own = matchKeys(staple)
            guard own.isDisjoint(with: keys), !ids.contains(staple.id) else { continue }
            keys.formUnion(own)
            ids.insert(staple.id)
            added.append(staple)
            count += 1
        }
        return added
    }
}
