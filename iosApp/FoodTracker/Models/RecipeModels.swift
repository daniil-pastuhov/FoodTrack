import Foundation

// The Recipe tab's domain. The shapes and raw values match Android's `shared/.../recipe/` and the
// server's JSON (camelCase), so a recipe or configuration means the same thing on every side.

public enum RecipeDifficulty: String, Codable, CaseIterable, Sendable {
    case easy, medium, ambitious
}

public enum RecipeDetail: String, Codable, CaseIterable, Sendable {
    case brief, standard, stepByStep
}

public enum RecipeMealType: String, Codable, CaseIterable, Sendable {
    case any, breakfast, lunch, dinner, snack, dessert
}

public enum RecipeDiet: String, Codable, CaseIterable, Sendable {
    case none, vegetarian, vegan, pescatarian, glutenFree, dairyFree
}

/// What a recipe is written in, when it isn't the app's own language.
public enum RecipeLanguage: String, Codable, CaseIterable, Sendable {
    case en, nb, ru, de, es, fr, it, pl, sv, uk

    /// The language's own name, as the picker lists it: "Deutsch", not "German".
    public var endonym: String {
        switch self {
        case .en: return "English"
        case .nb: return "Norsk bokmål"
        case .ru: return "Русский"
        case .de: return "Deutsch"
        case .es: return "Español"
        case .fr: return "Français"
        case .it: return "Italiano"
        case .pl: return "Polski"
        case .sv: return "Svenska"
        case .uk: return "Українська"
        }
    }

    /// The name in English, for instructions to the phone's own model.
    public var englishName: String {
        Locale(identifier: "en").localizedString(forLanguageCode: rawValue) ?? rawValue
    }

    /// The app's own language: the first of its localisations the system picked. The app ships
    /// in en, nb and ru, so this is one of those.
    public static var app: RecipeLanguage {
        app(preferredLocalizations: Bundle.main.preferredLocalizations)
    }

    static func app(preferredLocalizations: [String]) -> RecipeLanguage {
        for code in preferredLocalizations {
            let base = String(code.prefix { $0 != "-" && $0 != "_" }).lowercased()
            if base == "no" || base == "nn" { return .nb }
            if let language = RecipeLanguage(rawValue: base) { return language }
        }
        return .en
    }
}

/// How the person wants recipes written. Kept under its own key, outside `AppSettings`, so
/// backups and the widget's settings read are untouched.
public struct RecipeConfig: Codable, Equatable, Sendable {
    public static let servingsRange = 1...12
    /// The time limits the form offers; nil is "Any".
    public static let minuteOptions: [Int?] = [15, 30, 60, nil]

    public var difficulty: RecipeDifficulty = .easy
    public var detail: RecipeDetail = .standard
    public var servings: Int = 2 {
        didSet { servings = Self.clampServings(servings) }
    }
    public var maxMinutes: Int? = 30
    public var mealType: RecipeMealType = .any
    public var diet: RecipeDiet = .none
    /// Nil writes in the app's language.
    public var language: RecipeLanguage? = nil

    public init(
        difficulty: RecipeDifficulty = .easy,
        detail: RecipeDetail = .standard,
        servings: Int = 2,
        maxMinutes: Int? = 30,
        mealType: RecipeMealType = .any,
        diet: RecipeDiet = .none,
        language: RecipeLanguage? = nil
    ) {
        self.difficulty = difficulty
        self.detail = detail
        self.servings = Self.clampServings(servings)
        self.maxMinutes = Self.validMinutes(maxMinutes)
        self.mealType = mealType
        self.diet = diet
        self.language = language
    }

    /// The language a recipe is written in once "same as the app" is resolved.
    public var resolvedLanguage: RecipeLanguage { language ?? .app }

    private enum CodingKeys: String, CodingKey {
        case difficulty, detail, servings, maxMinutes, mealType, diet, language
    }

    /// Lenient: a missing key, or a value a later version added, falls back to the default
    /// rather than losing the whole configuration.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func value<T: RawRepresentable>(_ key: CodingKeys, _ fallback: T) -> T where T.RawValue == String {
            (try? c.decodeIfPresent(String.self, forKey: key)).flatMap { $0.flatMap(T.init(rawValue:)) } ?? fallback
        }
        difficulty = value(.difficulty, RecipeDifficulty.easy)
        detail = value(.detail, RecipeDetail.standard)
        servings = Self.clampServings((try? c.decodeIfPresent(Int.self, forKey: .servings)).flatMap { $0 } ?? 2)
        if c.contains(.maxMinutes) {
            maxMinutes = Self.validMinutes((try? c.decodeIfPresent(Int.self, forKey: .maxMinutes)).flatMap { $0 })
        } else {
            maxMinutes = 30
        }
        mealType = value(.mealType, RecipeMealType.any)
        diet = value(.diet, RecipeDiet.none)
        language = (try? c.decodeIfPresent(String.self, forKey: .language)).flatMap { $0.flatMap(RecipeLanguage.init(rawValue:)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(difficulty, forKey: .difficulty)
        try c.encode(detail, forKey: .detail)
        try c.encode(servings, forKey: .servings)
        // Written as null rather than left out, so "Any" can't read back as the default 30.
        try c.encode(maxMinutes, forKey: .maxMinutes)
        try c.encode(mealType, forKey: .mealType)
        try c.encode(diet, forKey: .diet)
        try c.encode(language, forKey: .language)
    }

    static func clampServings(_ value: Int) -> Int {
        min(max(value, servingsRange.lowerBound), servingsRange.upperBound)
    }

    /// Only the limits the form offers; anything else is "Any".
    static func validMinutes(_ value: Int?) -> Int? {
        guard let value, minuteOptions.contains(value) else { return nil }
        return value
    }
}

public struct RecipeIngredient: Codable, Equatable, Hashable, Sendable {
    /// "4 slices", "100 g", or empty for "to taste" lines.
    public var amount: String
    public var name: String
    public var fromPantry: Bool

    public init(amount: String, name: String, fromPantry: Bool) {
        self.amount = amount
        self.name = name
        self.fromPantry = fromPantry
    }
}

/// Where a recipe was written. Local to the apps: the server's JSON doesn't carry it.
public enum RecipeSource: String, Codable, Sendable {
    case server, device
}

public struct Recipe: Codable, Equatable, Sendable {
    public var title: String
    public var summary: String
    public var minutes: Int
    public var servings: Int
    public var difficulty: RecipeDifficulty
    public var ingredients: [RecipeIngredient]
    public var steps: [String]
    public var tip: String?
    public var source: RecipeSource

    public init(
        title: String,
        summary: String,
        minutes: Int,
        servings: Int,
        difficulty: RecipeDifficulty,
        ingredients: [RecipeIngredient],
        steps: [String],
        tip: String? = nil,
        source: RecipeSource
    ) {
        self.title = title
        self.summary = summary
        self.minutes = minutes
        self.servings = servings
        self.difficulty = difficulty
        self.ingredients = ingredients
        self.steps = steps
        self.tip = tip
        self.source = source
    }

    private enum CodingKeys: String, CodingKey {
        case title, summary, minutes, servings, difficulty, ingredients, steps, tip, source
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        title = try c.decode(String.self, forKey: .title)
        summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
        minutes = try c.decodeIfPresent(Int.self, forKey: .minutes) ?? 0
        servings = try c.decodeIfPresent(Int.self, forKey: .servings) ?? 0
        difficulty = (try? c.decodeIfPresent(RecipeDifficulty.self, forKey: .difficulty)).flatMap { $0 } ?? .easy
        ingredients = try c.decodeIfPresent([RecipeIngredient].self, forKey: .ingredients) ?? []
        steps = try c.decodeIfPresent([String].self, forKey: .steps) ?? []
        tip = try c.decodeIfPresent(String.self, forKey: .tip)
        // A server payload has no source of its own.
        source = (try? c.decodeIfPresent(RecipeSource.self, forKey: .source)).flatMap { $0 } ?? .server
    }
}

public enum RecipeCourse: String, Codable, Sendable {
    case starter, main, dessert
}

public struct RecipeMenuCourse: Codable, Equatable, Sendable {
    public var course: RecipeCourse
    public var recipe: Recipe
}

public struct RecipeMenuDay: Codable, Equatable, Sendable {
    /// ISO date, "2026-11-04".
    public var date: String
    /// The dated pantry items this day is planned around.
    public var uses: [String]
    public var courses: [RecipeMenuCourse]
}

/// Three days of two or three courses, written by the server only.
public struct RecipeMenu: Codable, Equatable, Sendable {
    public var days: [RecipeMenuDay]
}

public enum SavedRecipeKind: String, Codable, Sendable {
    case recipe, menu
}

/// One entry in Saved recipes.
public struct SavedRecipe: Identifiable, Equatable, Sendable {
    public enum Content: Equatable, Sendable {
        case recipe(Recipe)
        case menu(RecipeMenu)
    }

    public let id: String
    public var createdAt: Date
    public var content: Content

    public init(id: String = UUID().uuidString, createdAt: Date = Date(), content: Content) {
        self.id = id
        self.createdAt = createdAt
        self.content = content
    }

    public var kind: SavedRecipeKind {
        switch content {
        case .recipe: return .recipe
        case .menu: return .menu
        }
    }

    public var source: RecipeSource {
        switch content {
        case .recipe(let recipe): return recipe.source
        case .menu: return .server
        }
    }

    public var recipe: Recipe? {
        if case .recipe(let recipe) = content { return recipe }
        return nil
    }
}

/// One pantry item as a recipe route sees it: a name and how long it has left.
public struct RecipeIngredientInput: Equatable, Sendable {
    public var name: String
    public var daysRemaining: Int
    /// "Today", "In 2 days": the words the prompt quotes.
    public var expiryText: String
    /// The date itself and how many there are, which the server takes.
    public var expiresOn: Date?
    public var quantity: Int?

    public init(name: String, daysRemaining: Int, expiryText: String, expiresOn: Date? = nil, quantity: Int? = nil) {
        self.name = name
        self.daysRemaining = daysRemaining
        self.expiryText = expiryText
        self.expiresOn = expiresOn
        self.quantity = quantity
    }

    public init(_ item: PantryItem) {
        self.init(
            name: item.name,
            daysRemaining: item.daysRemaining,
            expiryText: item.daysRemainingFormatted,
            expiresOn: item.expiryDate,
            quantity: item.quantity
        )
    }
}

/// Everything a route needs to write one recipe.
public struct RecipeRequest: Equatable, Sendable {
    public var items: [RecipeIngredientInput]
    public var config: RecipeConfig
    /// From app Settings, always applied.
    public var allergens: [String]

    public init(items: [RecipeIngredientInput], config: RecipeConfig, allergens: [String]) {
        // Soonest first, as every route lists them.
        self.items = items.sorted { $0.daysRemaining < $1.daysRemaining }
        self.config = config
        self.allergens = allergens.sorted()
    }
}
