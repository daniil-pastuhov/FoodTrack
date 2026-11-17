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
    case en, nb, ru, de, es, fr, it, pl, sv, uk, da

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
        case .da: return "Dansk"
        }
    }

    /// The name in English, for instructions to the phone's own model.
    public var englishName: String {
        Locale(identifier: "en").localizedString(forLanguageCode: rawValue) ?? rawValue
    }

    /// The app's own language: the first of its localisations the system picked. The app ships
    /// in en, ru, nb, es, de, fr, sv and da, so this is one of those.
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

/// Whether the phone's own model can write a recipe in a given language right now.
enum OnDeviceRecipeAvailability: Equatable, Sendable {
    case available
    /// The phone has a model, still downloading. The design's "Your phone is getting ready".
    case preparing
    /// The phone has a model, but not for the language recipes are set to be written in: the
    /// route is hidden (also while the model is still downloading, as nothing would come of it)
    /// and Recipe settings says why under the language.
    case unsupportedLanguage
    /// No model on this phone: too old, Apple Intelligence off, or iOS before 26.
    case unsupported

    /// What the phone's model is doing, whatever the language.
    enum ModelState: Sendable { case none, preparing, ready }

    /// The one rule, as Android's `RecipeUiState.onDeviceRoute` / `onDeviceLanguageUnsupported`:
    /// no model hides the route; a model that doesn't write the chosen recipe language hides it
    /// too, ready or not; otherwise ready or preparing.
    static func of(_ model: ModelState, writesLanguage: Bool) -> OnDeviceRecipeAvailability {
        switch model {
        case .none: return .unsupported
        case _ where !writesLanguage: return .unsupportedLanguage
        case .preparing: return .preparing
        case .ready: return .available
        }
    }
}

/// The time slider's scale, in minutes: 10 to 120 in steps of 5. A handle at its end is no bound
/// on that side (both at the ends: "Any"). Android: `RECIPE_MINUTES_FLOOR` and friends; the server
/// checks the same numbers (`MINUTES_RANGE`).
public enum RecipeTime {
    public static let floor = 10
    public static let ceiling = 120
    public static let step = 5
    /// The legacy `maxMinutes` values, for a server that doesn't say `acceptsMultiChoiceConfig`.
    public static let legacyLimits = [15, 30, 60]

    /// `minutes` on the scale: the nearest step, within the range.
    public static func snap(_ minutes: Int) -> Int {
        let clamped = min(max(minutes, floor), ceiling)
        return floor + ((clamped - floor + step / 2) / step) * step
    }
}

/// The multi-choice rules of the meal and diet chips. Android: `toggledChoice`, `promptDiets`.
public enum RecipeChoices {
    /// The meals and diets the chips offer after "Any" / "None", in the form's order.
    public static let meals: [RecipeMealType] = RecipeMealType.allCases.filter { $0 != .any }
    public static let diets: [RecipeDiet] = RecipeDiet.allCases.filter { $0 != .none }

    /// A chip tapped in a row whose first chip ("Any", "None": `option` nil) is the empty set:
    /// that chip clears the others; another toggles itself, and the last one off is the empty set.
    public static func toggled<T: Hashable>(_ selection: Set<T>, _ option: T?) -> Set<T> {
        guard let option else { return [] }
        var next = selection
        if next.contains(option) { next.remove(option) } else { next.insert(option) }
        return next
    }

    /// The selected diets less those another selected one implies (vegan implies vegetarian,
    /// pescatarian and dairy-free; vegetarian implies pescatarian): what the prompts say. Every
    /// selected diet is still sent and checked.
    public static func promptDiets(_ diets: Set<RecipeDiet>) -> [RecipeDiet] {
        Self.diets.filter { diet in
            guard diets.contains(diet) else { return false }
            switch diet {
            case .vegetarian, .dairyFree: return !diets.contains(.vegan)
            case .pescatarian: return !diets.contains(.vegan) && !diets.contains(.vegetarian)
            default: return true
            }
        }
    }

    public static func orderedMeals(_ meals: Set<RecipeMealType>) -> [RecipeMealType] {
        Self.meals.filter(meals.contains)
    }

    public static func orderedDiets(_ diets: Set<RecipeDiet>) -> [RecipeDiet] {
        Self.diets.filter(diets.contains)
    }
}

/// How the person wants recipes written. Kept under its own key, outside `AppSettings`, so
/// backups and the widget's settings read are untouched. A configuration stored by an earlier
/// version (one `mealType`, one `diet`, `maxMinutes` 15, 30 or 60) reads into this one.
public struct RecipeConfig: Codable, Equatable, Sendable {
    public static let servingsRange = 1...12

    public var difficulty: RecipeDifficulty = .easy
    public var detail: RecipeDetail = .standard
    public var servings: Int = 2 {
        didSet { servings = Self.clampServings(servings) }
    }
    /// The slider's lower handle: a guide only. Nil with it at `RecipeTime.floor`.
    public var minMinutes: Int? = nil
    /// The upper handle: a limit. Nil with it at `RecipeTime.ceiling`.
    public var maxMinutes: Int? = 30
    /// The dish suits any one of these; empty is "Any".
    public var mealTypes: Set<RecipeMealType> = []
    /// Every one of these holds at once; empty is "None".
    public var diets: Set<RecipeDiet> = []
    /// Nil writes in the app's language.
    public var language: RecipeLanguage? = nil

    public init(
        difficulty: RecipeDifficulty = .easy,
        detail: RecipeDetail = .standard,
        servings: Int = 2,
        minMinutes: Int? = nil,
        maxMinutes: Int? = 30,
        mealTypes: Set<RecipeMealType> = [],
        diets: Set<RecipeDiet> = [],
        language: RecipeLanguage? = nil
    ) {
        self.difficulty = difficulty
        self.detail = detail
        self.servings = Self.clampServings(servings)
        self.minMinutes = minMinutes
        self.maxMinutes = maxMinutes
        self.mealTypes = mealTypes
        self.diets = diets
        self.language = language
        normalize()
    }

    /// The language a recipe is written in once "same as the app" is resolved.
    public var resolvedLanguage: RecipeLanguage { language ?? .app }

    /// Minutes on the slider's scale (a bound at its end is none), the lower bound no higher
    /// than the upper, "Any" and "None" out of the sets. Android: `RecipeConfig.normalized`.
    public mutating func normalize() {
        let max = maxMinutes.map(RecipeTime.snap).flatMap { $0 < RecipeTime.ceiling ? $0 : nil }
        var low = minMinutes.map(RecipeTime.snap).flatMap { $0 > RecipeTime.floor ? $0 : nil }
        if let l = low, let max { low = Swift.min(l, max) }
        maxMinutes = max
        minMinutes = low
        mealTypes.remove(.any)
        diets.remove(.none)
    }

    public func normalized() -> RecipeConfig {
        var copy = self
        copy.normalize()
        return copy
    }

    private enum CodingKeys: String, CodingKey {
        case difficulty, detail, servings, minMinutes, maxMinutes, mealTypes, diets, language
        /// Written by earlier versions: one value each.
        case mealType, diet
    }

    /// Lenient: a missing key, or a value a later version added, falls back to the default
    /// rather than losing the whole configuration.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func value<T: RawRepresentable>(_ key: CodingKeys, _ fallback: T) -> T where T.RawValue == String {
            (try? c.decodeIfPresent(String.self, forKey: key)).flatMap { $0.flatMap(T.init(rawValue:)) } ?? fallback
        }
        func set<T: RawRepresentable & Hashable>(_ key: CodingKeys, legacy: CodingKeys, _: T.Type) -> Set<T> where T.RawValue == String {
            if c.contains(key) {
                let raw = (try? c.decodeIfPresent([String].self, forKey: key)).flatMap { $0 } ?? []
                return Set(raw.compactMap(T.init(rawValue:)))
            }
            let old = (try? c.decodeIfPresent(String.self, forKey: legacy)).flatMap { $0.flatMap(T.init(rawValue:)) }
            return old.map { [$0] } ?? []
        }
        difficulty = value(.difficulty, RecipeDifficulty.easy)
        detail = value(.detail, RecipeDetail.standard)
        servings = Self.clampServings((try? c.decodeIfPresent(Int.self, forKey: .servings)).flatMap { $0 } ?? 2)
        minMinutes = (try? c.decodeIfPresent(Int.self, forKey: .minMinutes)).flatMap { $0 }
        if c.contains(.maxMinutes) {
            maxMinutes = (try? c.decodeIfPresent(Int.self, forKey: .maxMinutes)).flatMap { $0 }
        } else {
            maxMinutes = 30
        }
        mealTypes = set(.mealTypes, legacy: .mealType, RecipeMealType.self)
        diets = set(.diets, legacy: .diet, RecipeDiet.self)
        language = (try? c.decodeIfPresent(String.self, forKey: .language)).flatMap { $0.flatMap(RecipeLanguage.init(rawValue:)) }
        normalize()
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(difficulty, forKey: .difficulty)
        try c.encode(detail, forKey: .detail)
        try c.encode(servings, forKey: .servings)
        try c.encode(minMinutes, forKey: .minMinutes)
        // Written as null rather than left out, so "no limit" can't read back as the default 30.
        try c.encode(maxMinutes, forKey: .maxMinutes)
        try c.encode(RecipeChoices.orderedMeals(mealTypes), forKey: .mealTypes)
        try c.encode(RecipeChoices.orderedDiets(diets), forKey: .diets)
        try c.encode(language, forKey: .language)
    }

    static func clampServings(_ value: Int) -> Int {
        min(max(value, servingsRange.lowerBound), servingsRange.upperBound)
    }
}

public struct RecipeIngredient: Codable, Equatable, Hashable, Sendable {
    /// "4 slices", "100 g", or empty for "to taste" lines.
    public var amount: String
    public var name: String
    public var fromPantry: Bool
    /// Server recipes: this ingredient's item in the request's `ingredients` / `optionalIngredients`
    /// (as sent), when it is one. Nil from older servers, in older saved rows and on-device recipes.
    public var pantryIndex: Int?
    public var optionalIndex: Int?
    /// Server recipes: the server found this non-pantry ingredient to be only a staple.
    public var staple: Bool
    /// Server recipes: this ingredient is the request's `staples` entry at this index (as sent).
    /// Nil from servers before it, in older saved rows and on-device recipes.
    public var stapleIndex: Int?
    /// The person's staple this ingredient is ("Your staple" chip; left out of "Add to shopping
    /// list" while it is a current staple). Set by the app, never taken from the server: on arrival
    /// from `stapleIndex` (`Recipe.withStapleNames`, which overwrites whatever came), for the
    /// phone's model by name (`RecipeShopping.stapleName(of:staples:)`). Kept in the saved payload.
    public var stapleName: String?

    public init(amount: String, name: String, fromPantry: Bool, pantryIndex: Int? = nil, optionalIndex: Int? = nil, staple: Bool = false, stapleIndex: Int? = nil, stapleName: String? = nil) {
        self.amount = amount
        self.name = name
        self.fromPantry = fromPantry
        self.pantryIndex = pantryIndex
        self.optionalIndex = optionalIndex
        self.staple = staple
        self.stapleIndex = stapleIndex
        self.stapleName = stapleName
    }

    private enum CodingKeys: String, CodingKey { case amount, name, fromPantry, pantryIndex, optionalIndex, staple, stapleIndex, stapleName }

    /// Lenient about the optional fields: absent or unreadable reads as none.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        amount = (try? c.decodeIfPresent(String.self, forKey: .amount)).flatMap { $0 } ?? ""
        name = try c.decode(String.self, forKey: .name)
        fromPantry = (try? c.decodeIfPresent(Bool.self, forKey: .fromPantry)).flatMap { $0 } ?? false
        pantryIndex = (try? c.decodeIfPresent(Int.self, forKey: .pantryIndex)).flatMap { $0 }
        optionalIndex = (try? c.decodeIfPresent(Int.self, forKey: .optionalIndex)).flatMap { $0 }
        staple = (try? c.decodeIfPresent(Bool.self, forKey: .staple)).flatMap { $0 } ?? false
        stapleIndex = (try? c.decodeIfPresent(Int.self, forKey: .stapleIndex)).flatMap { $0 }
        stapleName = (try? c.decodeIfPresent(String.self, forKey: .stapleName)).flatMap { $0 }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(amount, forKey: .amount)
        try c.encode(name, forKey: .name)
        try c.encode(fromPantry, forKey: .fromPantry)
        try c.encodeIfPresent(pantryIndex, forKey: .pantryIndex)
        try c.encodeIfPresent(optionalIndex, forKey: .optionalIndex)
        if staple { try c.encode(true, forKey: .staple) }
        try c.encodeIfPresent(stapleIndex, forKey: .stapleIndex)
        try c.encodeIfPresent(stapleName, forKey: .stapleName)
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
    /// The items this dish was written from that were past their date when it was written, and
    /// by how many days: the red warning above the ingredients and in its shared text. Local to
    /// the apps, worked out from the request (`RecipeWire.pastDate`), never from the model's text;
    /// a payload saved before it existed reads as none. Android: `Recipe.pastDate`.
    public var pastDate: [PastDateItem] = []
    /// Local only: the generation's request id (server recipes saved since pictures and reports
    /// came, 2026-11): what "Report this recipe" names. Nil in rows saved before it, which then
    /// offer no report, and in recipes the phone wrote. Set by the app, never read from a reply
    /// (`withoutLocalFields`). Android: `Recipe.requestId`.
    public var requestId: String? = nil
    /// Local only: the dish's picture kept with the saved recipe (its bytes are in
    /// `SavedRecipeRecord`), by its size; nil without one. Android: `Recipe.picture`.
    public var picture: RecipePictureSize? = nil
    /// Local only: the step pictures kept with it, in step order. Android: `Recipe.stepPictures`.
    public var stepPictures: [RecipeStepPictureSize] = []

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
        case title, summary, minutes, servings, difficulty, ingredients, steps, tip, source, pastDate
        case requestId, picture, stepPictures
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
        pastDate = (try? c.decodeIfPresent([PastDateItem].self, forKey: .pastDate)).flatMap { $0 } ?? []
        requestId = (try? c.decodeIfPresent(String.self, forKey: .requestId)).flatMap { $0 }
        picture = (try? c.decodeIfPresent(RecipePictureSize.self, forKey: .picture)).flatMap { $0 }
        stepPictures = (try? c.decodeIfPresent([RecipeStepPictureSize].self, forKey: .stepPictures)).flatMap { $0 } ?? []
    }

    /// The same recipe with nothing local a reply or a model's text could have carried: no request
    /// id and no pictures. Applied to everything that arrives before the app sets its own. Android:
    /// `Recipe.withoutLocalFields`.
    func withoutLocalFields() -> Recipe {
        var recipe = self
        recipe.requestId = nil
        recipe.picture = nil
        recipe.stepPictures = []
        return recipe
    }

    /// Whether it has any picture kept with it: the report sheet's picture reason.
    var hasPictures: Bool { picture != nil || !stepPictures.isEmpty }
}

/// A picture kept on this device with a saved recipe, by its size in pixels as the server said.
public struct RecipePictureSize: Codable, Equatable, Hashable, Sendable {
    public var width: Int
    public var height: Int

    public init(width: Int, height: Int) {
        self.width = width
        self.height = height
    }
}

/// A step's picture: `step` is the 0-based index into `Recipe.steps`.
public struct RecipeStepPictureSize: Codable, Equatable, Hashable, Sendable {
    public var step: Int
    public var width: Int
    public var height: Int

    public init(step: Int, width: Int, height: Int) {
        self.step = step
        self.width = width
        self.height = height
    }
}

/// An item past its date, as it was when a recipe was written: `daysOver` days after its date.
public struct PastDateItem: Codable, Equatable, Hashable, Sendable {
    public var name: String
    public var daysOver: Int

    public init(name: String, daysOver: Int) {
        self.name = name
        self.daysOver = daysOver
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
    /// Every item past its date the menu was written from (see `Recipe.pastDate`): the warning
    /// under the menu's title. Each course's own recipe carries its day's share.
    public var pastDate: [PastDateItem] = []
    /// Local only: the generation's request id, as for `Recipe.requestId` (a dish opened from
    /// the menu reports with it too). Android: `Menu.requestId`.
    public var requestId: String? = nil
}

extension RecipeMenu {
    private enum CodingKeys: String, CodingKey { case days, pastDate, requestId }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        days = try c.decode([RecipeMenuDay].self, forKey: .days)
        pastDate = (try? c.decodeIfPresent([PastDateItem].self, forKey: .pastDate)).flatMap { $0 } ?? []
        requestId = (try? c.decodeIfPresent(String.self, forKey: .requestId)).flatMap { $0 }
    }

    /// With its past-date warnings: each day's courses get the past-date items among that day's
    /// `uses` (the server's reading of which items the day uses, by the names sent), and the menu
    /// all of `ticked` plus the `optional` ones a day uses. Android: `Menu.withPastDate`.
    /// For a menu whose dishes carry item indices: each course gets the past-date items it uses
    /// itself, and the menu every one any course uses. Nil when no dish carries indices (an older
    /// server); then `withPastDate` works it out from the days' `uses`. Android:
    /// `Menu.withPastDateByIndex`.
    func withPastDateByIndex(ticked: [WireIngredient], optional: [WireIngredient], today: String) -> RecipeMenu? {
        guard days.contains(where: { $0.courses.contains { $0.recipe.hasItemIndices } }) else { return nil }
        var menu = self
        for day in menu.days.indices {
            for course in menu.days[day].courses.indices {
                let recipe = menu.days[day].courses[course].recipe
                menu.days[day].courses[course].recipe.pastDate = RecipeWire.pastDate(of: recipe, ticked: ticked, optional: optional, today: today)
            }
        }
        var names = Set<String>()
        menu.pastDate = menu.days.flatMap { $0.courses.flatMap(\.recipe.pastDate) }.filter { names.insert($0.name.lowercased()).inserted }
        return menu
    }

    func withPastDate(ticked: [PastDateItem], optional: [PastDateItem]) -> RecipeMenu {
        var seen = Set<String>()
        let all = (ticked + optional).filter { seen.insert($0.name.lowercased()).inserted }
        var menu = self
        menu.days = days.map { day in
            let used = Set(day.uses.map { $0.lowercased() })
            let items = all.filter { used.contains($0.name.lowercased()) }
            var day = day
            day.courses = day.courses.map { course in
                var course = course
                course.recipe.pastDate = items
                return course
            }
            return day
        }
        var names = Set<String>()
        menu.pastDate = (ticked + menu.days.flatMap { $0.courses.flatMap(\.recipe.pastDate) })
            .filter { names.insert($0.name.lowercased()).inserted }
        return menu
    }
}

extension Recipe {
    /// Whether this dish says which request item each pantry ingredient is: answers from servers
    /// before `pantryIndex` / `optionalIndex` don't.
    var hasItemIndices: Bool { ingredients.contains { $0.pantryIndex != nil || $0.optionalIndex != nil } }

    /// With each ingredient's `stapleName` set from its `stapleIndex` into `staples` (the
    /// request's `staples` as sent), nil where there is none or it is out of range: whatever
    /// arrived is overwritten. Android: `Recipe.withStapleNames`.
    func withStapleNames(_ staples: [String]) -> Recipe {
        var recipe = self
        recipe.ingredients = ingredients.map { ingredient in
            var ingredient = ingredient
            ingredient.stapleName = ingredient.stapleIndex.flatMap { staples.indices.contains($0) ? staples[$0] : nil }
            return ingredient
        }
        return recipe
    }
}

extension RecipeMenu {
    /// `Recipe.withStapleNames` on every dish.
    func withStapleNames(_ staples: [String]) -> RecipeMenu {
        var menu = self
        for day in menu.days.indices {
            for course in menu.days[day].courses.indices {
                menu.days[day].courses[course].recipe = menu.days[day].courses[course].recipe.withStapleNames(staples)
            }
        }
        return menu
    }
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
    /// The dish picture cropped small, for the Saved row's thumbnail; nil without a picture.
    public var thumbnail: Data? = nil
    /// The pictures' bytes, only where they are in hand (just arrived, or read back for Undo):
    /// saving an entry with nil keeps what the store already holds for its id.
    public var pictures: SavedRecipePictures? = nil

    public init(id: String = UUID().uuidString, createdAt: Date = Date(), content: Content, thumbnail: Data? = nil, pictures: SavedRecipePictures? = nil) {
        self.id = id
        self.createdAt = createdAt
        self.content = content
        self.thumbnail = thumbnail
        self.pictures = pictures
    }

    /// The generation's request id, for a result our server wrote and saved since it was kept.
    public var requestId: String? {
        switch content {
        case .recipe(let recipe): return recipe.requestId
        case .menu(let menu): return menu.requestId
        }
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

/// A saved recipe's picture bytes (JPEG): the dish's, and each step's by its index. Kept in
/// `SavedRecipeRecord` (external storage), never in the payload, a backup or a log.
public struct SavedRecipePictures: Equatable, Sendable, CustomStringConvertible {
    public var dish: Data?
    public var steps: [Int: Data]

    public init(dish: Data? = nil, steps: [Int: Data] = [:]) {
        self.dish = dish
        self.steps = steps
    }

    public var isEmpty: Bool { dish == nil && steps.isEmpty }
    public var description: String { "SavedRecipePictures(dish: \(dish != nil), steps: \(steps.keys.sorted()))" }
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
    /// "Send all products": the rest of the pantry, soonest first, as items the recipe may use as
    /// well. Empty with the setting off.
    public var otherItems: [RecipeIngredientInput]
    /// The person's staples not on the shopping list, less those the route already lists
    /// (`Staples.promptStaples`): basics every route may use, whatever "Send all products" says.
    public var staples: [String] = []

    public init(items: [RecipeIngredientInput], config: RecipeConfig, allergens: [String], otherItems: [RecipeIngredientInput] = [], staples: [String] = []) {
        // Soonest first, as every route lists them.
        self.items = items.sorted { $0.daysRemaining < $1.daysRemaining }
        self.config = config
        self.allergens = allergens.sorted()
        self.otherItems = otherItems.sorted { $0.daysRemaining < $1.daysRemaining }
        self.staples = staples
    }
}
