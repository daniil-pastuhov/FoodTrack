import CryptoKit
import Foundation

// The paid route's wire format, as `functions/CONTRACT.md` states it, and the pure rules around
// it: cleaning names the server would refuse, reading its answers and its errors. Nothing here
// touches the network, so it can be read (and one day tested) on its own.

// MARK: Requests

enum RecipeKind: String, Codable, Sendable {
    case recipe, menu
}

/// What the server takes for a recipe and for a menu. `menu2` / `menu3` are a 3-day menu of two
/// and three courses a day; `menuSets` (from a server that says `acceptsMenuDays`) is every menu
/// set it writes, days -> courses a day -> recipes, string keys as on the wire. The server
/// decides and sends it beside every `balance`; the app only says it, from the last value it
/// received, or from `fallback` (3-day only) before it has received one. Android: `RecipeCosts`.
struct RecipeCosts: Codable, Equatable, Sendable {
    var recipe: Int
    var menu2: Int
    var menu3: Int
    /// Nil from a server that predates menu sets, and in a table cached by an earlier version.
    var menuSets: [String: [String: Int]]?

    static let fallback = RecipeCosts(recipe: 1, menu2: 4, menu3: 5)
    /// A price above this is not believed (as Android's `MAX_COST`).
    static let maxCost = 100

    /// The cheapest and dearest menu set (`menuSets` with `menu2` / `menu3`), for the packs text;
    /// nil without a table, when the text says what a 3-day menu takes instead. Android:
    /// `RecipeCosts.menuSetRange`.
    var menuSetRange: ClosedRange<Int>? {
        guard let menuSets, !menuSets.isEmpty else { return nil }
        let prices = menuSets.values.flatMap(\.values) + [menu2, menu3]
        return prices.min()!...prices.max()!
    }

    init(recipe: Int, menu2: Int, menu3: Int, menuSets: [String: [String: Int]]? = nil) {
        self.recipe = recipe
        self.menu2 = menu2
        self.menu3 = menu3
        self.menuSets = menuSets
    }

    private enum CodingKeys: String, CodingKey { case recipe, menu2, menu3, menuSets }

    /// Leniently: a field that is missing, not a whole number, below 1 or above `maxCost` takes
    /// its fallback value; in `menuSets` such an entry (or a day or course count outside 1...4 / 1...3) is
    /// left out, and an unreadable `menuSets` reads as absent. Throws only when `costs` isn't an
    /// object at all.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        func read(_ key: CodingKeys, _ fallback: Int) -> Int {
            guard let value = (try? container.decodeIfPresent(Int.self, forKey: key)) ?? nil, (1...Self.maxCost).contains(value) else {
                return fallback
            }
            return value
        }
        recipe = read(.recipe, Self.fallback.recipe)
        menu2 = read(.menu2, Self.fallback.menu2)
        menu3 = read(.menu3, Self.fallback.menu3)
        let raw = (try? container.decodeIfPresent([String: [String: LenientInt]].self, forKey: .menuSets)) ?? nil
        menuSets = raw.map { rows in
            var sets: [String: [String: Int]] = [:]
            for (days, row) in rows {
                guard let d = Int(days), MenuSet.dayChoices.contains(d) else { continue }
                var prices: [String: Int] = [:]
                for (courses, price) in row {
                    guard let c = Int(courses), MenuSet.courseChoices.contains(c), let value = price.value, (1...Self.maxCost).contains(value) else { continue }
                    prices[String(c)] = value
                }
                if !prices.isEmpty { sets[String(d)] = prices }
            }
            return sets
        }
    }

    /// What a menu of `days` days and `courses` courses a day takes: from `menuSets`, or for the
    /// 3-day menu of two or three courses `menu2` / `menu3`; nil when the server gave no price for
    /// it (then that choice isn't offered).
    func menuPrice(days: Int, courses: Int) -> Int? {
        if let price = menuSets?[String(days)]?[String(courses)] { return price }
        guard days == MenuSet.defaultDays else { return nil }
        switch courses {
        case 2: return menu2
        case 3: return menu3
        default: return nil
        }
    }

    /// A recipe, or a menu of `courses` courses a day for `days` days (a combination without a
    /// price is never asked for; it reads as the 3-day one of as many courses).
    func cost(of kind: RecipeKind, courses: Int, days: Int = MenuSet.defaultDays) -> Int {
        switch kind {
        case .recipe: return recipe
        case .menu: return menuPrice(days: days, courses: courses) ?? (courses == 3 ? menu3 : menu2)
        }
    }

    /// What `request` takes, by its own kind, course count and days (absent: 3).
    func cost(of request: GenerateRequest) -> Int {
        cost(of: request.kind, courses: request.courses ?? 2, days: request.days ?? MenuSet.defaultDays)
    }
}

/// A whole number, or nil for anything else, without failing what it is inside.
struct LenientInt: Decodable, Sendable {
    let value: Int?

    init(from decoder: Decoder) throws {
        value = try? decoder.singleValueContainer().decode(Int.self)
    }
}

/// The menu set's two choices, days and courses a day, and what may be offered and sent. Until
/// a reply said `acceptsMenuDays` the menu is the 3-day one of two or three courses and Days isn't
/// shown; after it, every combination the server priced in `menuSets`. Android: `RecipeUiState.menuPair` and `menuOffered` in `RecipeStateHolder.kt`.
struct MenuSet: Equatable, Sendable {
    var days: Int
    var courses: Int

    static let defaultDays = 3
    static let dayChoices = [1, 2, 3, 4]
    static let courseChoices = [1, 2, 3]
    /// What a server without menu sets writes.
    static let legacyCourseChoices = [2, 3]

    /// The menu set the form asks for, from the stored choices: as chosen when it may be sent
    /// and has a price; otherwise the same courses for 3 days, otherwise 3 days of two courses.
    static func effective(days: Int, courses: Int, takesMenuDays: Bool, costs: RecipeCosts) -> MenuSet {
        guard takesMenuDays else {
            return MenuSet(days: defaultDays, courses: legacyCourseChoices.contains(courses) ? courses : 2)
        }
        if costs.menuPrice(days: days, courses: courses) != nil { return MenuSet(days: days, courses: courses) }
        if costs.menuPrice(days: defaultDays, courses: courses) != nil { return MenuSet(days: defaultDays, courses: courses) }
        return MenuSet(days: defaultDays, courses: 2)
    }

    /// The Days choices to show next to `current` (its own courses): empty until the server takes
    /// menu sets, so the choice isn't shown; then each day count priced for those courses.
    static func dayOptions(for current: MenuSet, takesMenuDays: Bool, costs: RecipeCosts) -> [Int] {
        guard takesMenuDays else { return [] }
        return dayChoices.filter { costs.menuPrice(days: $0, courses: current.courses) != nil }
    }

    /// The Courses choices next to `current` (its own days): two and three until the server takes
    /// menu sets; then each count priced for those days.
    static func courseOptions(for current: MenuSet, takesMenuDays: Bool, costs: RecipeCosts) -> [Int] {
        guard takesMenuDays else { return legacyCourseChoices }
        return courseChoices.filter { costs.menuPrice(days: current.days, courses: $0) != nil }
    }

    /// `days` as the request carries it: only when it isn't 3, and only to a server that takes
    /// menu sets; nil (left out: the 3-day menu) otherwise. Android: `RecipeUiState.menuDays`.
    static func wireDays(_ days: Int, takesMenuDays: Bool) -> Int? {
        guard takesMenuDays, days != defaultDays, dayChoices.contains(days) else { return nil }
        return days
    }

    /// `courses` as the request carries it: one only to a server that takes menu sets, otherwise
    /// two or three (anything else is two). Android: `RecipeUiState.menuCourses`.
    static func wireCourses(_ courses: Int, takesMenuDays: Bool) -> Int {
        if courses == 1 { return takesMenuDays ? 1 : 2 }
        return courses == 3 ? 3 : 2
    }

    /// Whether `request` asked for something only a server with menu sets reads, so its refusal
    /// as INVALID_REQUEST means the server was rolled back: then the flag is cleared.
    static func needsMenuDays(_ request: GenerateRequest) -> Bool {
        request.kind == .menu && (request.days != nil || request.courses == 1)
    }
}

/// The menu form's "few items" hint: `items` usable items for `dishes` dishes.
struct MenuItemsHint: Equatable, Sendable {
    let items: Int
    let dishes: Int
}

extension MenuSet {
    /// Whether this menu set, from `ticked` ticked items and `optional` other items that would go
    /// with it ("Send all products", as the request would carry them), is mostly dishes made up
    /// plus shopping: fewer usable items than half its dishes, rounded up, with at least one item
    /// ticked (nothing ticked has its own hint). A warning only: it never blocks the menu. Nil
    /// when there is nothing to say. One rule on both platforms; Android: `menuItemsHint` in
    /// `RecipeStateHolder.kt`.
    func itemsHint(ticked: Int, optional: Int) -> MenuItemsHint? {
        let dishes = days * courses
        let usable = ticked + optional
        return ticked > 0 && usable < (dishes + 1) / 2 ? MenuItemsHint(items: usable, dishes: dishes) : nil
    }
}

/// One pack as the store offers it, for the Buy sheet's saving badge: its recipes, the store's
/// own price and currency (nil when the store gave none).
struct PackOffer: Equatable, Sendable {
    let recipes: Int
    let price: Decimal?
    let currencyCode: String?
}

/// How much cheaper per recipe a larger pack is than the smallest one, worked out from the
/// store's prices only. The same rule as Android's `recipePackSavingPercents` in `shared/.../recipe/RecipePackSavings.kt`.
enum PackSavings {
    /// The smallest saving worth a badge.
    static let minimumPercent = 5

    /// Each pack's saving in whole percent (floored, exactly), in the order given; nil for no
    /// badge. The base is the pack with the fewest recipes among those with a positive price;
    /// a pack gets a saving only with more recipes than the base, the same currency and a
    /// positive price, and only when it is at least `minimumPercent`.
    static func percents(_ packs: [PackOffer]) -> [Int?] {
        let priced = packs.filter { ($0.price ?? 0) > 0 && $0.recipes > 0 }
        guard let base = priced.min(by: { $0.recipes < $1.recipes }),
              let basePrice = base.price, let baseCurrency = base.currencyCode,
              !baseCurrency.trimmingCharacters(in: .whitespaces).isEmpty else {
            return packs.map { _ in nil }
        }
        return packs.map { pack in
            guard pack.recipes > base.recipes, let price = pack.price, price > 0,
                  pack.currencyCode == baseCurrency else { return nil }
            // saving = 1 − (price / recipes) / (basePrice / baseRecipes)
            //        = (recipes·basePrice − price·baseRecipes) / (recipes·basePrice)
            let denominator = Decimal(pack.recipes) * basePrice
            let numerator = (denominator - price * Decimal(base.recipes)) * 100
            guard numerator > 0 else { return nil }
            let percent = floorQuotient(numerator, denominator)
            return percent >= minimumPercent ? percent : nil
        }
    }

    /// The badge: U+2212 minus, the number and `%`, the same in every language.
    static func badgeText(_ percent: Int) -> String { "\u{2212}\(percent)%" }

    /// ⌊n / d⌋ for positive `n` and `d`, exact: Decimal's division rounds at 38 digits, so the
    /// floored quotient is corrected against the exact products.
    private static func floorQuotient(_ n: Decimal, _ d: Decimal) -> Int {
        var quotient = n / d
        var floored = Decimal()
        NSDecimalRound(&floored, &quotient, 0, .down)
        var result = NSDecimalNumber(decimal: floored).intValue
        while Decimal(result) * d > n { result -= 1 }
        while Decimal(result + 1) * d <= n { result += 1 }
        return result
    }
}

/// Saved recipes and menus the person hasn't seen yet: written or recovered while the writing
/// screen wasn't on screen. Kept as ids beside the recipe options (`RecipeOptions.unreadSaved`);
/// the header's badge counts those still saved. Android: `RecipeUiState.unreadCount` / `isUnread`.
enum RecipeUnread {
    /// Just saved: unread unless its result was shown as it arrived.
    static func afterSave(_ unread: Set<String>, id: String, shown: Bool) -> Set<String> {
        shown ? unread.subtracting([id]) : unread.union([id])
    }

    /// Opened (from Saved recipes or anywhere), or deleted: no longer unread.
    static func afterOpen(_ unread: Set<String>, id: String) -> Set<String> {
        unread.subtracting([id])
    }

    /// The ids that still are saved recipes.
    static func pruned(_ unread: Set<String>, savedIDs: [String]) -> Set<String> {
        unread.intersection(savedIDs)
    }

    /// The badge: unread ids that are still saved.
    static func count(_ unread: Set<String>, savedIDs: [String]) -> Int {
        pruned(unread, savedIDs: savedIDs).count
    }
}

/// `costs` as a response carries it. Anything unreadable there reads as nil rather than failing
/// the call it came with.
struct WireCosts: Decodable, Sendable {
    let value: RecipeCosts?

    init(from decoder: Decoder) throws {
        value = try? RecipeCosts(from: decoder)
    }
}

/// `generateRecipe`'s `config` (CONTRACT.md "RecipeConfig"), in one of two shapes: legacy
/// (`maxMinutes` 15 | 30 | 60 | nil, one `mealType`, one `diet`), or multi-choice, only to a server
/// that says `acceptsMultiChoiceConfig` (`minMinutes`, `maxMinutes` on the slider's scale,
/// `mealTypes`, `diets`, and no `mealType` or `diet`, so a server from before them, which requires
/// both, refuses the request rather than reading only part of it). A request stored by an earlier
/// version decodes as the legacy shape and is sent again unchanged. Android: `WireConfig`.
struct WireRecipeConfig: Codable, Equatable, Sendable {
    var difficulty: String
    var detail: String
    var servings: Int
    var maxMinutes: Int?
    var mealType: String?
    var diet: String?
    var minMinutes: Int?
    var mealTypes: [String]?
    var diets: [String]?
    var language: String

    var isMultiChoice: Bool { mealTypes != nil || diets != nil }

    /// What to send for `config`: the legacy shape whenever it says exactly the same, the
    /// multi-choice shape when it doesn't and the server `takesMultiChoice`, otherwise the closest
    /// legacy values (`legacy(_:)`); nil when those can't keep every selected diet. Android:
    /// `wireConfigFor`.
    static func make(_ config: RecipeConfig, takesMultiChoice: Bool) -> WireRecipeConfig? {
        let c = config.normalized()
        let exact = c.minMinutes == nil && (c.maxMinutes.map { RecipeTime.legacyLimits.contains($0) } ?? true)
            && c.mealTypes.count <= 1 && c.diets.count <= 1
        if exact || !takesMultiChoice { return legacy(c) }
        return WireRecipeConfig(
            difficulty: c.difficulty.rawValue,
            detail: c.detail.rawValue,
            servings: c.servings,
            maxMinutes: c.maxMinutes,
            mealType: nil,
            diet: nil,
            minMinutes: c.minMinutes,
            mealTypes: RecipeChoices.orderedMeals(c.mealTypes).map(\.rawValue),
            diets: RecipeChoices.orderedDiets(c.diets).map(\.rawValue),
            language: c.resolvedLanguage.rawValue
        )
    }

    /// The closest legacy config: the lower time bound dropped, the upper the largest of 15, 30, 60
    /// not above it (10 becomes 15); one meal as it is, several "any"; one diet as it is, several
    /// the strictest selected one when it implies the rest, otherwise vegan (vegetarian or
    /// pescatarian with dairy-free); gluten-free with another diet can't be said: nil. Android:
    /// `legacyWireConfig`.
    static func legacy(_ config: RecipeConfig) -> WireRecipeConfig? {
        let c = config.normalized()
        let diets = RecipeChoices.promptDiets(c.diets)
        let diet: RecipeDiet
        if diets.isEmpty {
            diet = .none
        } else if diets.count == 1 {
            diet = diets[0]
        } else if diets.contains(.glutenFree) {
            return nil
        } else {
            diet = .vegan
        }
        let max = c.maxMinutes.map { limit in RecipeTime.legacyLimits.last { $0 <= limit } ?? RecipeTime.legacyLimits[0] }
        return WireRecipeConfig(
            difficulty: c.difficulty.rawValue,
            detail: c.detail.rawValue,
            servings: c.servings,
            maxMinutes: max,
            mealType: (c.mealTypes.count == 1 ? c.mealTypes.first! : .any).rawValue,
            diet: diet.rawValue,
            minMinutes: nil,
            mealTypes: nil,
            diets: nil,
            language: c.resolvedLanguage.rawValue
        )
    }
}

struct WireIngredient: Codable, Equatable, Sendable {
    var name: String
    var expiresOn: String?
    var quantity: Int?
}

/// One `generateRecipe` call. Stored as it was first sent, so a retry after a lost connection
/// sends the same thing under the same id.
struct GenerateRequest: Codable, Equatable, Sendable {
    var requestId: String
    var kind: RecipeKind
    var courses: Int?
    /// A menu's days, only when not 3 and only to a server that said `acceptsMenuDays`; nil (left
    /// out: 3 days) otherwise, and in a request stored by an older version.
    var days: Int?
    var config: WireRecipeConfig
    var ingredients: [WireIngredient]
    var allergens: [String]
    var today: String
    /// `true` with a non-empty `allergens`, sent only after the person agreed to send them
    /// (`AllergenConsent`); nil (left out of the JSON) otherwise. The server refuses allergens
    /// without it (`ALLERGEN_CONSENT_REQUIRED`). Nil in a request stored by an older version.
    var allergenConsent: Bool?
    /// The rest of the pantry, which the recipe may use as well ("Send all products"): only to a
    /// server that said `acceptsOptionalIngredients`, already capped by
    /// `RecipeWire.optionalIngredients`; nil (left out of the JSON) otherwise, and in a request
    /// stored by an older version. Part of the inputs a `requestId` is bound to.
    var optionalIngredients: [WireIngredient]?
    /// The taste profile's answers, step id to option ids (`TasteProfile.toSend`): only when
    /// there are any and the server said `acceptsTaste`; nil (left out of the JSON) otherwise, and in a request stored by an older
    /// version. Part of the inputs a `requestId` is bound to.
    var taste: [String: [String]]? = nil

    /// Everything the server binds a `requestId` to: the whole body but the id and the consent
    /// flag, which isn't an input.
    func hasSameInputs(as other: GenerateRequest) -> Bool {
        var other = other
        other.requestId = requestId
        other.allergenConsent = allergenConsent
        return self == other
    }
}

struct VerifyPurchaseRequest: Encodable, Sendable {
    let store = "appstore"
    var signedTransaction: String
    /// `true` when the person saw, before paying, that a refund request makes us tell Apple how
    /// many of the recipes were used; otherwise absent (nil is left out of the JSON). Never
    /// `false`: on a resubmission that would withdraw a consent given earlier (CONTRACT.md).
    var consumptionConsent: Bool?

    init(signedTransaction: String, consumptionConsent: Bool) {
        self.signedTransaction = signedTransaction
        self.consumptionConsent = RecipeWire.consentToSend(consumptionConsent)
    }
}

struct MergeBalanceRequest: Encodable, Sendable {
    var sourceIdToken: String
}

struct DeleteAccountRequest: Encodable, Sendable {
    var appleAuthorizationCode: String?
}

/// `registerAppleSignIn`: the authorization code of an Apple sign-in or link, which the server
/// exchanges for a refresh token it keeps (encrypted) to remove Apple's link when the account is
/// deleted, from this phone or from Android.
struct RegisterAppleSignInRequest: Encodable, Sendable {
    var appleAuthorizationCode: String
}

struct RegisterAppleSignInResponse: Decodable, Sendable {
    var registered: Bool?
}

struct EmptyRequest: Encodable, Sendable {}

/// `getTasteQuiz`: the quiz in the app's language.
struct TasteQuizRequest: Encodable, Sendable {
    var language: String
}

/// `getGeneration`: an existing generation's state, by its id. Never starts one.
struct GetGenerationRequest: Encodable, Sendable {
    var requestId: String
}

// MARK: Responses

/// `purchasesBlocked` as a response may carry it: true only when it says exactly `true`.
/// Anything unreadable there reads as false rather than failing the call.
struct WireFlag: Decodable, Sendable {
    let value: Bool

    init(from decoder: Decoder) throws {
        value = (try? decoder.singleValueContainer().decode(Bool.self)) ?? false
    }
}

/// A string a response may carry, such as `tasteQuizVersion`: nil when it isn't a string, rather
/// than failing the call.
struct WireText: Decodable, Sendable {
    let value: String?

    init(from decoder: Decoder) throws {
        value = try? decoder.singleValueContainer().decode(String.self)
    }
}

/// Every answer that carries `balance` also carries the costs and, for an account whose
/// earlier purchases were refunded after the recipes were used, `purchasesBlocked`; and, from a
/// server that reads `optionalIngredients`, `acceptsOptionalIngredients: true`.
protocol BalanceCarrying {
    var balance: Int { get }
    var costs: WireCosts? { get }
    var purchasesBlocked: WireFlag? { get }
    var acceptsOptionalIngredients: WireFlag? { get }
    /// From a server that reads the multi-choice config: `acceptsMultiChoiceConfig: true`.
    var acceptsMultiChoiceConfig: WireFlag? { get }
    /// From a server that writes recipes in Danish: `acceptsDanish: true`.
    var acceptsDanish: WireFlag? { get }
    /// From a server that writes menu sets (`days`, `courses: 1`, `costs.menuSets`):
    /// `acceptsMenuDays: true`.
    var acceptsMenuDays: WireFlag? { get }
    /// From a server that reads a taste profile (`taste`): `acceptsTaste: true`.
    var acceptsTaste: WireFlag? { get }
    /// The taste quiz's current `version` (as `getTasteQuiz` returns it), beside `acceptsTaste`.
    var tasteQuizVersion: WireText? { get }
}

enum GenerateStatus: String, Decodable, Sendable {
    case done = "DONE"
    case inProgress = "IN_PROGRESS"
    case failed = "FAILED"
}

struct GenerateResponse: Decodable, Sendable, BalanceCarrying {
    var status: GenerateStatus
    var recipe: Recipe?
    var menu: RecipeMenu?
    var balance: Int
    var charged: Bool
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
    var acceptsOptionalIngredients: WireFlag?
    var acceptsMultiChoiceConfig: WireFlag?
    var acceptsDanish: WireFlag?
    var acceptsMenuDays: WireFlag?
    var acceptsTaste: WireFlag?
    var tasteQuizVersion: WireText?
}

enum VerifyStatus: String, Decodable, Sendable {
    case granted = "GRANTED"
    case alreadyGranted = "ALREADY_GRANTED"
    case rejectedPermanent = "REJECTED_PERMANENT"
    case retry = "RETRY"

    /// The contract finishes the store transaction on every answer but RETRY.
    var finishesTransaction: Bool { self != .retry }
}

struct VerifyPurchaseResponse: Decodable, Sendable, BalanceCarrying {
    var status: VerifyStatus
    var balance: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
    var acceptsOptionalIngredients: WireFlag?
    var acceptsMultiChoiceConfig: WireFlag?
    var acceptsDanish: WireFlag?
    var acceptsMenuDays: WireFlag?
    var acceptsTaste: WireFlag?
    var tasteQuizVersion: WireText?
}

struct BalanceResponse: Decodable, Sendable, BalanceCarrying {
    var balance: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
    var acceptsOptionalIngredients: WireFlag?
    var acceptsMultiChoiceConfig: WireFlag?
    var acceptsDanish: WireFlag?
    var acceptsMenuDays: WireFlag?
    var acceptsTaste: WireFlag?
    var tasteQuizVersion: WireText?
}

/// `moved` can be negative: a store refund's debt moves with the recipes.
struct MergeBalanceResponse: Decodable, Sendable, BalanceCarrying {
    var balance: Int
    var moved: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
    var acceptsOptionalIngredients: WireFlag?
    var acceptsMultiChoiceConfig: WireFlag?
    var acceptsDanish: WireFlag?
    var acceptsMenuDays: WireFlag?
    var acceptsTaste: WireFlag?
    var tasteQuizVersion: WireText?
}

struct DeleteAccountResponse: Decodable, Sendable, Equatable {
    var deleted: Bool
    /// Present only for an account that carried Apple: false when the server could not remove
    /// Apple's link, so the person is told how to do it in Apple's settings. Anything but a
    /// boolean reads as absent.
    var appleLinkRemoved: Bool?

    init(deleted: Bool, appleLinkRemoved: Bool? = nil) {
        self.deleted = deleted
        self.appleLinkRemoved = appleLinkRemoved
    }

    enum CodingKeys: String, CodingKey { case deleted, appleLinkRemoved }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deleted = (try? container.decode(Bool.self, forKey: .deleted)) ?? true
        appleLinkRemoved = (try? container.decodeIfPresent(Bool.self, forKey: .appleLinkRemoved)) ?? nil
    }

    /// Whether the deletion left Apple's link in place: only when the server says so.
    var leftAppleLink: Bool { appleLinkRemoved == false }
}

// MARK: Errors

/// What went wrong on a server call, by `details.reason` where the server gave one.
enum RecipeServerError: Error, Equatable, Sendable {
    case insufficientCredits
    case busy
    case generationDisabled
    case accountClosed
    /// Too many calls in a minute (`RATE_LIMITED`).
    case rateLimited
    /// This account's generations, or its estimated usage, for the UTC day (`USER_DAILY_LIMIT`).
    case userDailyLimit
    /// Three refunded technical failures today on this account (`INFRA_FAILURE_LIMIT`).
    case infraFailureLimit
    /// The whole service's daily limit (`DAILY_CAP`).
    case dailyCap
    /// The id was first sent with other inputs (`REQUEST_ID_REUSED`). A bug on our side; nothing
    /// was charged, and a new attempt needs a new id.
    case requestIdReused
    /// The App Check token was already used (`APP_CHECK_REPLAYED`). Unlike `unauthenticated` it
    /// says nothing about whether this build can attest: the next call carries a new token.
    case appCheckReplayed
    /// `getGeneration` knows no generation with this id (`UNKNOWN_REQUEST`): it never reached
    /// the server, so it is sent again with `generateRecipe` under the same id.
    case unknownRequest
    /// The callable itself doesn't exist (`not-found` or `unimplemented` with no reason): a
    /// server from before `getGeneration`. `generateRecipe` does its work there.
    case callableMissing
    case recentSignInRequired
    case mergeNotAllowed
    case invalidRequest
    /// `INVALID_REQUEST` naming the input the server refused (`details.field`, such as
    /// "ingredients[3].name" or "allergens[0]"): a name its "Name rules" refuse. Nothing was
    /// charged; the request is never sent again unchanged.
    case refusedName(field: String)
    /// `verifyPurchase` from an app the server doesn't take this store's purchases from
    /// (`STORE_MISMATCH`). A build or App Check setup bug: permanent for that purchase, nothing
    /// was recorded, and buying is hidden.
    case storeMismatch
    /// The purchase's `appAccountToken` is another account's (`PURCHASE_ACCOUNT_MISMATCH`).
    /// Nothing was recorded: the transaction is kept for the account that bought it.
    case purchaseAccountMismatch
    /// Allergens sent without `allergenConsent: true` (`ALLERGEN_CONSENT_REQUIRED`): a request
    /// stored before the consent step existed. Nothing was charged; a new request carries it.
    case allergenConsentRequired
    /// The ticked items can't make what was asked once the allergens and diets are applied (for a
    /// menu, some day has nothing it may use): `NOT_ENOUGH_ITEMS`. Nothing was charged and no model
    /// was called; a changed request needs a new id.
    case notEnoughItems
    /// `resource-exhausted` without a reason: every server instance is busy (HTTP 429). Nothing
    /// was charged; the same request is sent again after a few seconds.
    case serversBusy
    /// No connection, a timeout, or the server unreachable: safe to retry with the same id.
    case offline
    /// `internal` and anything unexpected: also safe to retry with the same id.
    case server
    /// No Auth user, or no valid App Check token: this build can't use the paid route.
    case unauthenticated
    /// A `details.reason` this version doesn't know, such as a limit added on the server since.
    /// `charged` is `details.charged`, false when absent. A new attempt needs a new id.
    case unrecognised(charged: Bool)

    /// `code` is the callable's error code name ("failed-precondition"), `reason` its
    /// `details.reason` and `charged` its `details.charged`.
    static func from(code: String, reason: String?, charged: Bool? = nil, field: String? = nil) -> RecipeServerError {
        if reason == "INVALID_REQUEST", let field, !field.isEmpty { return .refusedName(field: field) }
        switch reason {
        case "INSUFFICIENT_CREDITS": return .insufficientCredits
        case "BUSY": return .busy
        case "GENERATION_DISABLED": return .generationDisabled
        case "ACCOUNT_CLOSED": return .accountClosed
        case "RATE_LIMITED": return .rateLimited
        case "USER_DAILY_LIMIT": return .userDailyLimit
        case "INFRA_FAILURE_LIMIT": return .infraFailureLimit
        case "DAILY_CAP": return .dailyCap
        case "REQUEST_ID_REUSED": return .requestIdReused
        case "APP_CHECK_REPLAYED": return .appCheckReplayed
        case "UNKNOWN_REQUEST": return .unknownRequest
        case "RECENT_SIGN_IN_REQUIRED": return .recentSignInRequired
        case "MERGE_NOT_ALLOWED": return .mergeNotAllowed
        case "INVALID_REQUEST": return .invalidRequest
        case "STORE_MISMATCH": return .storeMismatch
        case "PURCHASE_ACCOUNT_MISMATCH": return .purchaseAccountMismatch
        case "ALLERGEN_CONSENT_REQUIRED": return .allergenConsentRequired
        case "NOT_ENOUGH_ITEMS": return .notEnoughItems
        case nil: break
        default: return .unrecognised(charged: charged ?? false)
        }
        switch code {
        case "unauthenticated": return .unauthenticated
        case "unavailable", "deadline-exceeded": return .offline
        case "invalid-argument": return .invalidRequest
        case "not-found", "unimplemented": return .callableMissing
        case "resource-exhausted": return .serversBusy
        default: return .server
        }
    }

    /// Whether the same request may be sent again under the same id. A replayed App Check token
    /// is refused before anything is reserved, so the same id is still unused or still held.
    var isRetryableWithSameID: Bool {
        switch self {
        case .offline, .server, .appCheckReplayed, .unknownRequest, .callableMissing, .serversBusy: return true
        default: return false
        }
    }

    /// Whether a `getGeneration` that failed this way is answered by sending the request itself
    /// with `generateRecipe` (same id, same inputs, a fresh limited-use token): the id is unknown
    /// to the server, or the server predates `getGeneration`.
    var fallsBackToGenerate: Bool { self == .unknownRequest || self == .callableMissing }

    /// Whether a call that failed this way is sent once more at once. Only a replayed App Check
    /// token: the Functions SDK fetches a new limited-use token for every call.
    static func retriesImmediately(_ error: RecipeServerError, attempt: Int) -> Bool {
        error == .appCheckReplayed && attempt == 0
    }

    /// Whether asking after a request the server may already hold (a poll, a recovery at
    /// launch) keeps asking after this failure, until the wait runs out. None of these says
    /// anything about the request itself, which may still be written and charged: all instances
    /// busy, the per-minute throttle, `BUSY`.
    var keepsAskingAfterHeldRequest: Bool {
        switch self {
        case .serversBusy, .rateLimited, .busy: return true
        default: return false
        }
    }
}

// MARK: Pure rules

enum RecipeWire {
    static let maxIngredients = 30
    static let maxAllergens = 20
    static let nameMaxLength = 40

    static let maxWordsPerName = 8
    static let maxCharsPerWordPart = 28
    /// Combining marks kept on one letter; on a Latin, Cyrillic or Greek letter only one.
    static let maxMarksPerLetter = 4
    static let maxMarksPerCasedLetter = 1
    /// Raw input past this many UTF-16 units is cut before any work is done on it.
    static let rawInputLimit = 400

    /// A name cleaned to what the server's "Name rules" accept (CONTRACT.md; the server's own
    /// check is `functions/src/core/inputSafety.ts`), or nil when nothing is left. The same steps
    /// as Android's `cleanWireName` (shared/.../RecipeWireName.kt); both are checked against
    /// functions/test/fixtures/names.json. Lengths are code points (Unicode scalars), as on the
    /// server.
    ///
    /// - normalised as the server does: NFKC, apostrophe and dash look-alikes folded to `'` and
    ///   `-`, `⁄` to `/`, the Arabic percent sign and decimal separator to `%` and `.`, invisible
    ///   and bidirectional characters removed, every other space or control character a space;
    /// - letters and decimal digits of every script, space and hyphen kept; combining marks kept
    ///   on a letter, at most 4 (1 on a Latin, Cyrillic or Greek letter), never the same one twice
    ///   in a row; anything else (emoji, symbols, other punctuation) becomes a space;
    /// - `' % , . / &` kept only where the server allows them;
    /// - a word part mixing Latin, Cyrillic and Greek letters folded to its main script when the
    ///   others all have a look-alike there, or dropped;
    /// - runs of spaces and of hyphens collapsed, any hyphen part longer than 28 code points
    ///   dropped (except in a script written without spaces: Chinese, Japanese, Thai...), at most
    ///   8 words, at most 40 code points (cut at a word end when there is one);
    /// - nil when marks are more than 60% of the letters and marks, which the server refuses.
    ///
    /// The server's "suspicious" checks (encoded text, repetition, words about the model) aren't
    /// copied: an honest name that trips one is refused by field, and left out then.
    static func cleanName(_ raw: String) -> String? {
        let limited = String(decoding: Array(raw.utf16.prefix(rawInputLimit)), as: UTF16.self)
        var text = collapsed(keptAllowed(normalisedScalars(limited)))
        // Each step can change what the others see (a cut can leave a "1," at the end); a few
        // rounds reach a name none of them changes.
        for _ in 0..<8 {
            let next = cut(shapedWords(collapsed(fixedPunctuation(text))))
            if next == text { break }
            text = next
        }
        guard text.contains(where: { isLetter($0) || isDigit($0) }) else { return nil }
        let letters = text.filter(isLetter).count
        let marks = text.filter(isMark).count
        if marks * 5 > (letters + marks) * 3 { return nil }
        return String(String.UnicodeScalarView(text))
    }

    /// An allergen as sent, only when cleaning kept every letter and digit of it: nil when a word
    /// or a part was dropped (a part over 28 characters, a look-alike word that can't be folded,
    /// past 8 words or 40 characters, nothing left). Unlike a pantry item, an allergen must never
    /// reach the server shortened, since a recipe written without part of it could be unsafe.
    static func cleanAllergenName(_ raw: String) -> String? {
        guard raw.utf16.count <= rawInputLimit, let cleaned = cleanName(raw) else { return nil }
        func meaningful(_ text: String) -> Int { normalisedScalars(text).filter { isLetter($0) || isDigit($0) }.count }
        return meaningful(raw) == meaningful(cleaned) ? cleaned : nil
    }

    /// The server's normalisation: NFKC, look-alikes folded, invisible characters removed, spaces.
    private static func normalisedScalars(_ raw: String) -> [Unicode.Scalar] {
        // Folded before NFKC too: NFKC turns `´` into a space and a combining accent.
        let folded = String(String.UnicodeScalarView(raw.unicodeScalars.map(foldedLookAlike)))
        var visible = String.UnicodeScalarView()
        for scalar in folded.precomposedStringWithCompatibilityMapping.unicodeScalars {
            let s: Unicode.Scalar = scalar == "\u{2044}" ? "/" : scalar
            if isInvisible(s) { continue }
            visible.append(isSpaceOrControl(s) ? " " : s)
        }
        // Again: removing an invisible character can bring a letter and its accent together.
        return String(visible).precomposedStringWithCompatibilityMapping.unicodeScalars.map(foldedLookAlike)
    }

    private static let apostropheLookalikes: Set<UInt32> = [0x2018, 0x2019, 0x201B, 0x02BC, 0x02B9, 0x60, 0xB4, 0x2032]
    private static let namePunctuation: Set<Unicode.Scalar> = ["'", "%", ",", ".", "/", "&"]

    private static func foldedLookAlike(_ s: Unicode.Scalar) -> Unicode.Scalar {
        if apostropheLookalikes.contains(s.value) { return "'" }
        if (0x2010...0x2015).contains(s.value) || [0x2212, 0xFE58, 0xFE63, 0xFF0D].contains(s.value) { return "-" }
        if s.value == 0x066A { return "%" } // Arabic percent sign
        if s.value == 0x066B { return "." } // Arabic decimal separator
        return s
    }

    private static func isInvisible(_ s: Unicode.Scalar) -> Bool {
        let v = s.value
        return s.properties.generalCategory == .format
            || (0xFE00...0xFE0F).contains(v) || (0xE0100...0xE01EF).contains(v) || (0x180B...0x180F).contains(v)
            || [0x115F, 0x1160, 0x3164, 0xFFA0, 0x034F].contains(v)
    }

    private static func isSpaceOrControl(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .control, .spaceSeparator, .lineSeparator, .paragraphSeparator: return true
        default: return false
        }
    }

    private static func isLetter(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    private static func isMark(_ s: Unicode.Scalar) -> Bool {
        switch s.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: return true
        default: return false
        }
    }

    /// Decimal digits of every script (Nd), as the server's `\p{Nd}`.
    private static func isDigit(_ s: Unicode.Scalar) -> Bool { s.properties.generalCategory == .decimalNumber }

    /// A letter or a combining mark (which only ever follows one): what `'` `&` `.` may follow.
    private static func isWordy(_ s: Unicode.Scalar) -> Bool { isLetter(s) || isMark(s) }

    /// The scripts the rule tells apart. Swift's `Unicode.Scalar.Properties` has no Script
    /// property, so this asks ICU through `NSRegularExpression`'s `\p{Script=...}`, the same
    /// Unicode data the server's `\p{Script=...}` and Android's `Character.UnicodeScript` use.
    enum NameScript { case latin, cyrillic, greek, unspaced, other }

    private static let scriptPatterns: [(NameScript, NSRegularExpression)] = {
        let patterns: [(NameScript, String)] = [
            (.latin, "\\p{Script=Latin}"), (.cyrillic, "\\p{Script=Cyrillic}"), (.greek, "\\p{Script=Greek}"),
            (.unspaced, "[\\p{Script=Han}\\p{Script=Hiragana}\\p{Script=Katakana}\\p{Script=Thai}\\p{Script=Lao}\\p{Script=Khmer}\\p{Script=Myanmar}]"),
        ]
        // Fixed patterns, checked by NameFixtureCheck: they always compile.
        return patterns.map { ($0.0, try! NSRegularExpression(pattern: $0.1)) }
    }()

    static func script(of s: Unicode.Scalar) -> NameScript {
        let text = String(s)
        let range = NSRange(location: 0, length: text.utf16.count)
        return scriptPatterns.first { $0.1.firstMatch(in: text, range: range) != nil }?.0 ?? .other
    }

    /// 0 Latin, 1 Cyrillic, 2 Greek for a letter of those scripts, else nil.
    private static func confusable(_ s: Unicode.Scalar) -> Int? {
        guard isLetter(s) else { return nil }
        switch script(of: s) {
        case .latin: return 0
        case .cyrillic: return 1
        case .greek: return 2
        default: return nil
        }
    }

    /// Every character the server could accept kept, every other one a space; combining marks
    /// only on a letter, a few per letter, never the same one twice in a row.
    private static func keptAllowed(_ text: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var out: [Unicode.Scalar] = []
        var base: Unicode.Scalar?
        var run = 0
        for s in text {
            if isLetter(s) {
                out.append(s)
                base = s
                run = 0
            } else if isMark(s) {
                // A stray mark, one past the limit, or a repeat ("Zalgo" text) is dropped.
                guard let letter = base else { continue }
                let max = confusable(letter) != nil ? maxMarksPerCasedLetter : maxMarksPerLetter
                if run < max, out.last != s {
                    out.append(s)
                    run += 1
                }
            } else {
                base = nil
                run = 0
                let kept = isDigit(s) || s == " " || s == "-" || namePunctuation.contains(s)
                out.append(kept ? s : " ")
            }
        }
        return out
    }

    /// Runs of spaces and of hyphens collapsed, spaces trimmed.
    private static func collapsed(_ text: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var out: [Unicode.Scalar] = []
        for s in text {
            if s == " " || s == "-", out.last == s { continue }
            out.append(s)
        }
        while out.last == " " { out.removeLast() }
        guard let start = out.firstIndex(where: { $0 != " " }) else { return [] }
        return Array(out[start...])
    }

    /// The server's position rules for `' % , . / &`: one out of place becomes a space.
    private static func fixedPunctuation(_ text: [Unicode.Scalar]) -> [Unicode.Scalar] {
        var out = text
        for i in text.indices where namePunctuation.contains(text[i]) {
            let prev: Unicode.Scalar? = i > 0 ? text[i - 1] : nil
            let next: Unicode.Scalar? = i + 1 < text.count ? text[i + 1] : nil
            let beforePrev: Unicode.Scalar? = i > 1 ? text[i - 2] : nil
            if !punctuationAllowed(text[i], prev: prev, next: next, beforePrev: beforePrev) { out[i] = " " }
        }
        return out
    }

    /// The server's rules, character by character (CONTRACT.md, "Name rules").
    static func punctuationAllowed(_ c: Unicode.Scalar, prev: Unicode.Scalar?, next: Unicode.Scalar?, beforePrev: Unicode.Scalar?) -> Bool {
        let letter: (Unicode.Scalar?) -> Bool = { $0.map(isLetter) ?? false }
        let wordy: (Unicode.Scalar?) -> Bool = { $0.map(isWordy) ?? false }
        let digit: (Unicode.Scalar?) -> Bool = { $0.map(isDigit) ?? false }
        switch c {
        case "'": return wordy(prev) && letter(next)
        case "&": return prev != nil && next != nil && (wordy(prev) || prev == " ") && (letter(next) || next == " ")
        case ".": return (wordy(prev) && (next == " " || digit(next))) || (digit(prev) && digit(next))
        case "%": return digit(prev) || (prev == " " && digit(beforePrev))
        case ",", "/": return digit(prev) && digit(next)
        default: return false
        }
    }

    private static func split(_ text: [Unicode.Scalar], on separator: Unicode.Scalar) -> [[Unicode.Scalar]] {
        var out: [[Unicode.Scalar]] = [[]]
        for s in text {
            if s == separator { out.append([]) } else { out[out.count - 1].append(s) }
        }
        return out
    }

    private static func joined(_ parts: [[Unicode.Scalar]], with separator: Unicode.Scalar) -> [Unicode.Scalar] {
        var out: [Unicode.Scalar] = []
        for (i, part) in parts.enumerated() {
            if i > 0 { out.append(separator) }
            out.append(contentsOf: part)
        }
        return out
    }

    /// Mixed-script parts folded, long parts dropped (no limit for a part in a script written
    /// without spaces), at most 8 words.
    private static func shapedWords(_ text: [Unicode.Scalar]) -> [Unicode.Scalar] {
        let words = split(text, on: " ").compactMap { word -> [Unicode.Scalar]? in
            let parts = split(word, on: "-").compactMap { part -> [Unicode.Scalar]? in
                let unspaced = part.contains { isLetter($0) && script(of: $0) == .unspaced }
                guard part.count <= maxCharsPerWordPart || unspaced else { return nil }
                return singleScript(part)
            }
            let joinedParts = joined(parts, with: "-")
            return joinedParts.contains(where: { $0 != "-" }) ? joinedParts : nil
        }
        return joined(Array(words.prefix(maxWordsPerName)), with: " ")
    }

    /// A part with letters of more than one of Latin, Cyrillic and Greek (the server refuses it as
    /// MIXED_SCRIPT), folded to the script most of its letters are in (ties: Latin, Cyrillic,
    /// Greek) when every other one has a look-alike there, else to the next script that works;
    /// nil when none does.
    private static func singleScript(_ part: [Unicode.Scalar]) -> [Unicode.Scalar]? {
        var counts = [0, 0, 0]
        for s in part { if let c = confusable(s) { counts[c] += 1 } }
        guard counts.filter({ $0 > 0 }).count >= 2 else { return part }
        // Most letters first; ties keep Latin, Cyrillic, Greek (an explicit tie-break: Swift's
        // sort isn't guaranteed stable).
        let order = [0, 1, 2].filter { counts[$0] > 0 }.sorted { counts[$0] != counts[$1] ? counts[$0] > counts[$1] : $0 < $1 }
        for target in order {
            var folded: [Unicode.Scalar] = []
            var ok = true
            for s in part {
                guard let from = confusable(s), from != target else {
                    folded.append(s)
                    continue
                }
                guard let mapped = lookAlikes[from][target][s] else {
                    ok = false
                    break
                }
                folded.append(mapped)
            }
            if ok { return folded }
        }
        return nil
    }

    private static func table(_ pairs: [String]) -> [Unicode.Scalar: Unicode.Scalar] {
        var out: [Unicode.Scalar: Unicode.Scalar] = [:]
        for pair in pairs {
            let s = Array(pair.unicodeScalars)
            out[s[0]] = s[1]
        }
        return out
    }

    /// Look-alike letters, [from][to], both cases written out (no case mapping at run time, so
    /// both apps fold the same way). The same table as Android's `LOOK_ALIKES`.
    private static let lookAlikes: [[[Unicode.Scalar: Unicode.Scalar]]] = [
        [
            [:],
            table(["aа", "bв", "eе", "kк", "mм", "hн", "oо", "pр", "cс", "tт", "yу", "xх", "iі",
                   "AА", "BВ", "EЕ", "KК", "MМ", "HН", "OО", "PР", "CС", "TТ", "YУ", "XХ", "IІ"]),
            table(["aα", "eε", "iι", "kκ", "vν", "oο", "pρ", "tτ", "uυ", "xχ",
                   "AΑ", "BΒ", "EΕ", "ZΖ", "HΗ", "IΙ", "KΚ", "MΜ", "NΝ", "OΟ", "PΡ", "TΤ", "YΥ", "XΧ"]),
        ],
        [
            table(["аa", "вb", "еe", "кk", "мm", "нh", "оo", "рp", "сc", "тt", "уy", "хx", "іi", "јj", "ѕs", "ԁd", "ӏl", "ԛq", "ԝw", "үy", "һh",
                   "АA", "ВB", "ЕE", "КK", "МM", "НH", "ОO", "РP", "СC", "ТT", "УY", "ХX", "ІI", "ЈJ", "ЅS", "ԀD", "ӀI", "ԚQ", "ԜW", "ҮY", "ҺH"]),
            [:],
            table(["аα", "еε", "кκ", "оο", "рρ", "тτ", "хχ", "іι",
                   "АΑ", "ВΒ", "ЕΕ", "НΗ", "ІΙ", "КΚ", "МΜ", "ОΟ", "РΡ", "ТΤ", "ХΧ"]),
        ],
        [
            table(["αa", "εe", "ιi", "κk", "νv", "οo", "ρp", "τt", "υu", "χx",
                   "ΑA", "ΒB", "ΕE", "ΖZ", "ΗH", "ΙI", "ΚK", "ΜM", "ΝN", "ΟO", "ΡP", "ΤT", "ΥY", "ΧX"]),
            table(["αа", "εе", "κк", "οо", "ρр", "τт", "χх", "ιі",
                   "ΑА", "ΒВ", "ΕЕ", "ΗН", "ΙІ", "ΚК", "ΜМ", "ΟО", "ΡР", "ΤТ", "ΧХ"]),
            [:],
        ],
    ]

    /// At most 40 code points, cut at a word end when the name has one.
    private static func cut(_ text: [Unicode.Scalar]) -> [Unicode.Scalar] {
        guard text.count > nameMaxLength else { return text }
        let head = Array(text[..<nameMaxLength])
        let atWordEnd = text[nameMaxLength] == " "
        if !atWordEnd, let lastSpace = head.lastIndex(of: " ") { return collapsed(Array(head[..<lastSpace])) }
        return collapsed(head)
    }

    /// `YYYY-MM-DD` in the phone's own calendar day, as the server's `today` and `expiresOn`.
    static func isoDay(_ date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 1970, parts.month ?? 1, parts.day ?? 1)
    }

    /// The `items` (as sent, `expiresOn` `YYYY-MM-DD`) past their date on `today` (likewise), in
    /// the order given. An item without a date, or one that can't be read, is not. Android:
    /// `pastDateOf`.
    static func pastDate(of items: [WireIngredient], today: String) -> [PastDateItem] {
        guard let day = dayNumber(today) else { return [] }
        return items.compactMap { item in
            guard let expires = item.expiresOn.flatMap(dayNumber) else { return nil }
            let over = day - expires
            return over > 0 ? PastDateItem(name: item.name, daysOver: over) : nil
        }
    }

    /// The items past their date on `today` that this server dish uses, exactly, by its
    /// ingredients' indices into the request's `ticked` and `optional` items (in the request's
    /// order, each once; an index outside them is ignored). Android: `Recipe.pastDateByIndex`.
    static func pastDate(of recipe: Recipe, ticked: [WireIngredient], optional: [WireIngredient], today: String) -> [PastDateItem] {
        let tickedUsed = Set(recipe.ingredients.compactMap(\.pantryIndex))
        let optionalUsed = Set(recipe.ingredients.compactMap(\.optionalIndex))
        let used = ticked.enumerated().filter { tickedUsed.contains($0.offset) }.map(\.element)
            + optional.enumerated().filter { optionalUsed.contains($0.offset) }.map(\.element)
        var seen = Set<String>()
        return pastDate(of: used, today: today).filter { seen.insert($0.name.lowercased()).inserted }
    }

    /// Days since 1970-01-01 of a `YYYY-MM-DD` date, in the proleptic Gregorian calendar.
    private static func dayNumber(_ iso: String) -> Int? {
        let parts = iso.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        guard let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else { return nil }
        return Int((date.timeIntervalSince1970 / 86_400).rounded())
    }

    /// Names in one request with optional items, ticked and optional together, and their code
    /// points (CONTRACT.md "Optional ingredients"). As Android's `MAX_REQUEST_TOTAL_NAMES` and
    /// `MAX_REQUEST_OPTIONAL_NAME_CHARS`.
    static let maxTotalNames = 30
    static let maxOptionalNameChars = 1050

    /// The optional items to send beside `ticked`, from `others` (the rest of the pantry, soonest
    /// first, names already cleaned), as the server keeps them: a name already in the request
    /// (ignoring case) is left out, then items are taken in order while ticked and taken together
    /// stay within 30 names and 1,050 code points, an item that doesn't fit being skipped. Android:
    /// `optionalWireIngredients`.
    static func optionalIngredients(ticked: [WireIngredient], others: [WireIngredient]) -> [WireIngredient] {
        var seen = Set(ticked.map { $0.name.lowercased() })
        var names = ticked.count
        var chars = ticked.reduce(0) { $0 + $1.name.unicodeScalars.count }
        var kept: [WireIngredient] = []
        for item in others {
            let key = item.name.lowercased()
            let length = item.name.unicodeScalars.count
            guard !seen.contains(key), names + 1 <= maxTotalNames, chars + length <= maxOptionalNameChars else { continue }
            seen.insert(key)
            names += 1
            chars += length
            kept.append(item)
        }
        return kept
    }

    /// `withOptional`: "Send all products" is on and the server said it reads them; then the
    /// request's `otherItems` go as `optionalIngredients`. `multiChoice`: the server said
    /// `acceptsMultiChoiceConfig`. `menuDays`: the server said `acceptsMenuDays`, so a menu
    /// carries `days` when not 3 and may have one course a day (`MenuSet.wireDays` /
    /// `wireCourses`); otherwise it is the 3-day menu of two or three. `taste`: the answers as
    /// `TasteProfile.toSend` gave them (nil: none, or the server doesn't read them). Nil when the selected diets can't all be sent to this server
    /// (`WireRecipeConfig.make`): never sent with one left out.
    static func request(
        for request: RecipeRequest,
        kind: RecipeKind,
        courses: Int,
        days: Int = MenuSet.defaultDays,
        requestID: UUID,
        today: Date = Date(),
        excluding refused: Set<String> = [],
        withOptional: Bool = false,
        multiChoice: Bool = false,
        menuDays: Bool = false,
        taste: [String: [String]]? = nil
    ) -> GenerateRequest? {
        guard let config = WireRecipeConfig.make(request.config, takesMultiChoice: multiChoice) else { return nil }
        // Names the server refused earlier (lower-cased) stay out: sending one again would only
        // be refused again, and repeated refusals lock the account for the day.
        var seen = refused
        let ingredients = request.items.compactMap { item -> WireIngredient? in
            guard let name = cleanName(item.name), seen.insert(name.lowercased()).inserted else { return nil }
            return WireIngredient(
                name: name,
                expiresOn: item.expiresOn.map { isoDay($0) },
                quantity: item.quantity.map { min(max($0, 0), 9999) }
            )
        }
        // A name the server would refuse is simply left out there, so it never stops the request.
        let optional = withOptional
            ? optionalIngredients(
                ticked: Array(ingredients.prefix(maxIngredients)),
                others: request.otherItems.compactMap { item -> WireIngredient? in
                    guard let name = cleanName(item.name), !refused.contains(name.lowercased()) else { return nil }
                    return WireIngredient(
                        name: name,
                        expiresOn: item.expiresOn.map { isoDay($0) },
                        quantity: item.quantity.map { min(max($0, 0), 9999) }
                    )
                }
            )
            : []
        // One per name regardless of case, as on Android.
        var seenAllergens = Set<String>()
        let allergens = request.allergens.compactMap(cleanAllergenName).sorted()
            .filter { seenAllergens.insert($0.lowercased()).inserted }.prefix(maxAllergens)
        return GenerateRequest(
            requestId: requestID.uuidString.lowercased(),
            kind: kind,
            courses: kind == .menu ? MenuSet.wireCourses(courses, takesMenuDays: menuDays) : nil,
            days: kind == .menu ? MenuSet.wireDays(days, takesMenuDays: menuDays) : nil,
            config: config,
            ingredients: Array(ingredients.prefix(maxIngredients)),
            allergens: Array(allergens),
            today: isoDay(today),
            // Built only past the consent step (`writeOnServer`), so allergens here were agreed to.
            allergenConsent: AllergenConsent.flag(for: Array(allergens)),
            optionalIngredients: optional.isEmpty ? nil : optional,
            taste: taste
        )
    }

    /// What Generate sends while an interrupted request is still held: the held one, under its
    /// own id, when the form still asks for exactly the same thing (so a second tap fetches the
    /// first answer instead of paying again); otherwise the fresh one under its new id. An id is
    /// only ever stored and sent with the body it was made for, so the server never sees it
    /// with other inputs (REQUEST_ID_REUSED).
    static func requestToSend(fresh: GenerateRequest, held: GenerateRequest?) -> GenerateRequest {
        guard let held, held.hasSameInputs(as: fresh) else { return fresh }
        return held
    }

    /// What `consumptionConsent` carries: `true`, or nothing. iOS never withdraws a consent, so
    /// a purchase whose consent isn't known on this phone (an older build's record, a purchase
    /// made on another device) leaves whatever the server has untouched.
    static func consentToSend(_ consented: Bool) -> Bool? {
        consented ? true : nil
    }

    /// How long to wait before asking again after the n-th busy answer in a row (n from 0):
    /// 3, 6, 12, 24 s, then 30 s. Never under the 3 s polling floor (CONTRACT.md, "Calling").
    static func busyRetryDelay(attempt: Int) -> Duration {
        .seconds(min(3 * (1 << min(max(attempt, 0), 4)), 30))
    }

    /// Busy answers in a row after which a request stops being retried for now and is kept
    /// for "Try again": about 1 min 45 s of waiting.
    static let maxBusyRetries = 6

    /// The held request a new Generate leaves behind: the held one when the form now asks for
    /// something else. The server doesn't stop it (CONTRACT.md, "Abandoning a request does not
    /// cancel it"): it may still be written and charged, and is asked after (never re-sent)
    /// before the new one goes out, so its result is saved rather than lost.
    static func abandonedRequest(fresh: GenerateRequest, held: GenerateRequest?) -> GenerateRequest? {
        guard let held, !held.hasSameInputs(as: fresh) else { return nil }
        return held
    }

    /// The first allergen that can't be sent as the person wrote it: one cleaning would drop or
    /// shorten (`cleanAllergenName`), or one past the 20 the server accepts. An allergen is never
    /// left out silently: a recipe written without it could be unsafe, so the request isn't sent
    /// and the person is told.
    static func unsendableAllergen(_ allergens: [String]) -> String? {
        var seen = Set<String>()
        for raw in allergens.sorted() {
            guard let name = cleanAllergenName(raw) else { return raw }
            if seen.insert(name.lowercased()).inserted, seen.count > maxAllergens { return raw }
        }
        return nil
    }

    /// Which name an `INVALID_REQUEST`'s `details.field` points at in `request`: an ingredient
    /// ("ingredients[3].name", "ingredients[3]") or an allergen ("allergens[0]"), with the name
    /// as sent. Nil for any other field, or an index the request doesn't have.
    static func refusedName(field: String, in request: GenerateRequest) -> RefusedName? {
        func index(after prefix: String) -> Int? {
            guard field.hasPrefix(prefix + "["), let close = field.firstIndex(of: "]") else { return nil }
            let digits = field[field.index(field.startIndex, offsetBy: prefix.count + 1)..<close]
            return Int(digits)
        }
        if let i = index(after: "ingredients"), request.ingredients.indices.contains(i) {
            return .ingredient(index: i, name: request.ingredients[i].name)
        }
        if let i = index(after: "allergens"), request.allergens.indices.contains(i) {
            return .allergen(name: request.allergens[i])
        }
        return nil
    }

    enum RefusedName: Equatable {
        case ingredient(index: Int, name: String)
        case allergen(name: String)
    }

    /// `request` without the ingredient at `index`, under a new id (its inputs changed, so the
    /// old id must not be reused), or nil when nothing would be left to cook with.
    static func dropping(ingredientAt index: Int, from request: GenerateRequest, newID: UUID) -> GenerateRequest? {
        guard request.ingredients.indices.contains(index), request.ingredients.count > 1 else { return nil }
        var next = request
        next.ingredients.remove(at: index)
        next.requestId = newID.uuidString.lowercased()
        return next
    }

    /// Credits in a pack, from its product id; the server keeps its own table.
    static func credits(forProductID id: String) -> Int? {
        switch id {
        case "recipes_10": return 10
        case "recipes_50": return 50
        case "recipes_100": return 100
        default: return nil
        }
    }

    static let productIDs = ["recipes_10", "recipes_50", "recipes_100"]

    /// The namespace of `appAccountToken(forUID:)`. Must match the server's (CONTRACT.md); a
    /// purchase whose token doesn't match is still accepted, so a mismatch loses only the binding.
    static let appAccountTokenNamespace = UUID(uuidString: "6f2b3c1e-8d4a-4b7e-9c35-2a1f0e7d5b48")!

    /// The StoreKit `appAccountToken` that ties a purchase to the Firebase user making it: a
    /// name-based UUID, version 5 (RFC 9562: SHA-1 over the namespace's 16 bytes and the uid's
    /// UTF-8 bytes, first 16 bytes, version and variant bits set), so the server can work out the
    /// same value from the uid.
    static func appAccountToken(forUID uid: String) -> UUID {
        let namespace = appAccountTokenNamespace.uuid
        var data = withUnsafeBytes(of: namespace) { Data($0) }
        data.append(Data(uid.utf8))
        var bytes = Array(Insecure.SHA1.hash(data: data).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        return UUID(uuid: (
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        ))
    }

    /// Server recipes are written by the server, whatever the JSON says.
    static func markedServer(_ recipe: Recipe) -> Recipe {
        var recipe = recipe
        recipe.source = .server
        return recipe
    }
}

// MARK: Allergen consent

/// The person's answer to sending their allergen list to our server: agreed, or refused or
/// withdrawn, when, and to which wording.
struct AllergenConsentRecord: Codable, Equatable, Sendable {
    var agreed: Bool
    var at: Date
    var version: Int
}

/// An allergen list is data concerning health (GDPR Art. 9). It goes to our server, and on to
/// Google's model, only with the person's explicit consent: asked once, the first time a
/// server-written recipe or menu would carry it, recorded on this phone with the time and the
/// wording's version, and withdrawable in Recipe settings. Refused or withdrawn, the server route
/// is blocked while allergens are set; they are never left out instead, since a recipe written
/// without them could be unsafe. The phone's own model and the shared prompt send nothing to us.
/// The same rules as Android's `AllergenConsent.kt`.
enum AllergenConsent {
    /// The wording's version (`recipe_allergen_consent_*`); raising it asks everyone again.
    static let version = 3

    enum Step: Equatable {
        /// No allergens set: nothing health-related is sent, no flag.
        case notNeeded
        /// Agreed to the current wording: sent, with `allergenConsent: true`.
        case granted
        /// Never asked, or asked with another wording: ask before sending.
        case ask
        /// Refused or withdrawn: not sent; the person is told where to change it.
        case declined
    }

    static func step(hasAllergens: Bool, record: AllergenConsentRecord?, version: Int = version) -> Step {
        guard hasAllergens else { return .notNeeded }
        guard let record, record.version == version else { return .ask }
        return record.agreed ? .granted : .declined
    }

    /// The record once the allergen list is known to be (non-)empty: a refusal is forgotten when
    /// the list is emptied, so allergens set later are asked about again. A consent stays.
    static func afterListChange(_ record: AllergenConsentRecord?, hasAllergens: Bool) -> AllergenConsentRecord? {
        if !hasAllergens, record?.agreed == false { return nil }
        return record
    }

    /// The request's `allergenConsent`: true with allergens (the request is only built past the
    /// step), absent without.
    static func flag(for allergens: [String]) -> Bool? { allergens.isEmpty ? nil : true }

    /// Whether Recipe settings shows sending allergens as on.
    static func isGiven(_ record: AllergenConsentRecord?, version: Int = version) -> Bool {
        record.map { $0.agreed && $0.version == version } ?? false
    }

    /// Whether any allergen in Settings is set (blank ones don't count).
    static func hasAllergens(_ allergens: some Sequence<String>) -> Bool {
        allergens.contains { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
}

// MARK: Shopping list

/// One row of the "Add to shopping list" sheet. `key` is how the shopping list matches names
/// (trimmed, lower case). Android: `ShoppingCandidate`.
struct ShoppingCandidate: Equatable, Hashable, Sendable {
    var name: String
    var key: String
    /// On the shopping list already: shown unticked and disabled, never added or counted.
    var alreadyListed: Bool
    /// The server marked it a staple: listed last, ticked like the rest.
    var staple: Bool
}

/// "Add to shopping list" on a recipe or a menu: the ingredients the cook doesn't have, tidied
/// and merged for the sheet. Android: `RecipeShopping.kt`, ported by hand.
enum RecipeShopping {
    /// The non-pantry ingredients of `recipes`, in order of first appearance with staples last,
    /// tidied into one or more entries (`tidyNames`) and listed once (ignoring case), marked when `listedNames` has them already. Plain
    /// water is left out when the server marked it a staple; without that mark every non-pantry
    /// ingredient is listed.
    static func candidates(_ recipes: [Recipe], listedNames: [String]) -> [ShoppingCandidate] {
        let listed = Set(listedNames.map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        var order: [String] = []
        var rows: [String: ShoppingCandidate] = [:]
        for ingredient in recipes.flatMap(\.ingredients) where !ingredient.fromPantry {
            for name in tidyNames(ingredient.name) {
                if ingredient.staple && isPlainWater(name) { continue }
                let key = name.lowercased()
                if let known = rows[key] {
                    // A staple elsewhere in the menu stays one only if every mention is one.
                    if known.staple && !ingredient.staple { rows[key]?.staple = false }
                } else {
                    order.append(key)
                    rows[key] = ShoppingCandidate(name: name, key: key, alreadyListed: listed.contains(key), staple: ingredient.staple)
                }
            }
        }
        let all = order.compactMap { rows[$0] }
        return all.filter { !$0.staple } + all.filter(\.staple)
    }

    /// Whether "Add to shopping list" has anything to offer: a row not on the list already. With
    /// none (nothing to buy, or every row listed) the button is hidden. Android: `hasShoppingToAdd`.
    static func hasSomethingToAdd(_ candidates: [ShoppingCandidate]) -> Bool {
        candidates.contains { !$0.alreadyListed }
    }

    /// What the sheet ticks first: everything not on the list already (the owner's rule).
    static func defaultSelection(_ candidates: [ShoppingCandidate]) -> Set<String> {
        Set(candidates.filter { !$0.alreadyListed }.map(\.key))
    }

    /// An ingredient's name as shopping-list entries. The name is cut at its commas: the first
    /// part is always an entry; each later part is one too while it is a short item
    /// (`isShortItem`: "Salt, pepper, chilli flakes" is three entries), and the first part that is
    /// a preparation note (", thinly sliced", ", peeled and diced small", ", 2 cloves") ends the
    /// list. A trailing "to taste" / "по вкусу" / "etter smak" is dropped from every part. "X or Y"
    /// stays one entry. Each entry has its whitespace collapsed, edge punctuation trimmed and its
    /// first letter capitalised. Android: `tidyShoppingNames`, the same rules and word lists.
    static func tidyNames(_ raw: String) -> [String] {
        var out: [String] = []
        let parts = raw.split(omittingEmptySubsequences: false) { $0 == "," || $0 == "，" }
        for (i, part) in parts.enumerated() {
            var words = part.split(whereSeparator: \.isWhitespace).map(String.init)
            let tail = tasteTails.first { t in words.count >= t.count && words.suffix(t.count).map(noteWord) == t }
            if let tail { words.removeLast(tail.count) }
            if i > 0 && !isShortItem(words) {
                if tail != nil && words.isEmpty { continue }
                break
            }
            var text = words.joined(separator: " ")
            let trailing: Set<Character> = [".", ";", ":", "-", "–", "—", " "]
            let leading: Set<Character> = ["-", "–", "—", "•", "*", " "]
            while let last = text.last, trailing.contains(last) { text.removeLast() }
            while let first = text.first, leading.contains(first) { text.removeFirst() }
            guard let first = text.first else { continue }
            out.append(first.isLowercase ? first.uppercased() + text.dropFirst() : text)
        }
        return out
    }

    /// Whether a comma part after the first is an item of its own: one to three words besides an
    /// "or", no digit, no preparation or description word.
    private static func isShortItem(_ words: [String]) -> Bool {
        let counted = words.filter { !orWords.contains(noteWord($0)) }.count
        guard (1...3).contains(counted) else { return false }
        return !words.contains { w in
            // A decimal digit of any script (Kotlin's isDigit) or a vulgar fraction.
            if w.unicodeScalars.contains(where: { CharacterSet.decimalDigits.contains($0) || "½¼¾⅓⅔".unicodeScalars.contains($0) }) { return true }
            let n = noteWord(w)
            return noteWords.contains(n) || notePrefixes.contains { n.hasPrefix($0) }
        }
    }

    /// A word as the note lists hold it: lower case, edge punctuation off.
    private static func noteWord(_ w: String) -> String {
        let edge: Set<Character> = [".", ";", ":", "(", ")", "!", "?", "\"", "«", "»"]
        var s = Substring(w.lowercased())
        while let c = s.first, edge.contains(c) { s.removeFirst() }
        while let c = s.last, edge.contains(c) { s.removeLast() }
        return String(s)
    }

    private static let orWords: Set<String> = ["or", "или", "eller", "o", "u", "oder", "ou"]

    /// Endings dropped from a part: the cook adds these to taste.
    private static let tasteTails: [[String]] = [
        ["to", "taste"], ["as", "needed"], ["if", "needed"], ["optional"],
        ["по", "вкусу"], ["по", "желанию"], ["etter", "smak"], ["etter", "behov"], ["valgfritt"],
        ["al", "gusto"], ["a", "gusto"], ["opcional"], ["si", "es", "necesario"], ["nach", "geschmack"], ["nach", "belieben"], ["nach", "bedarf"], ["selon", "goût"], ["au", "goût"],
        ["à", "volonté"], ["facultatif"], ["si", "nécessaire"], ["efter", "smak"], ["efter", "behov"], ["valfritt"], ["efter", "smag"], ["valgfrit"],
    ]

    /// Words that make a comma part a preparation or description note, in en, ru, nb, es, de, fr, sv and da.
    private static let noteWords: Set<String> = [
        // en
        "sliced", "chopped", "minced", "diced", "peeled", "grated", "crushed", "cubed", "halved", "quartered",
        "drained", "rinsed", "washed", "trimmed", "beaten", "melted", "softened", "shredded", "julienned",
        "divided", "zest", "deseeded", "seeded", "pitted", "cored", "stemmed", "torn", "zested", "juiced", "squeezed",
        "thinly", "finely", "roughly", "coarsely", "cut", "into", "taste", "optional", "needed", "such", "like",
        "for", "to", "about", "plus", "at", "room", "temperature", "serving", "garnish", "preferably",
        "boneless", "skinless", "ripe", "large", "small", "medium", "thick", "thin", "whole", "cooked", "uncooked",
        // ru
        "по", "для", "без", "очень", "кубиками", "ломтиками", "кольцами", "соломкой", "полосками", "дольками",
        // nb
        "i", "til", "etter", "uten", "hakket", "finhakket", "grovhakket", "skivet", "skiver", "revet", "finrevet",
        "skrelt", "presset", "knust", "smeltet", "delt", "terninger", "strimler", "båter", "smak", "fint", "grovt",
        "tynt", "tynne", "store", "stor", "små", "liten", "moden", "modne", "romtemperert", "servering", "pynt",
        // es
        "en", "para", "sin", "al", "gusto", "rodajas", "dados", "tiras", "trozos", "cubos", "finamente",
        "fino", "fina", "grande", "grandes", "pequeño", "pequeña", "pequeños", "pequeñas", "mediano",
        "mediana", "maduro", "madura", "maduros", "maduras", "temperatura", "ambiente", "servir", "decorar",
        "aproximadamente", "opcional", "cocido", "cocida",
        // de
        "in", "für", "ohne", "zum", "zur", "fein", "grob", "scheiben", "würfel", "würfeln", "streifen",
        "stücke", "stücken", "ringe", "ringen", "geschmack", "belieben", "groß", "große", "großer", "klein",
        "kleine", "kleiner", "reif", "reife", "zimmertemperatur", "servieren", "garnieren", "etwa", "ca",
        "gekocht", "gekochte",
        // fr
        "pour", "sans", "dés", "tranches", "rondelles", "lamelles", "morceaux", "finement", "grossièrement",
        "goût", "volonté", "facultatif", "mûr",
        "mûre", "mûrs", "mûres", "température", "ambiante", "décorer", "environ", "cuit", "cuite",
        // sv
        "till", "efter", "utan", "hackad", "hackade", "skivad", "skivade", "skivor", "riven", "rivna",
        "skalad", "skalade", "pressad", "pressade", "krossad", "krossade", "smält", "smälta", "delad",
        "delade", "tärnad", "tärnade", "tärningar", "strimlor", "klyftor", "tunt", "tunna", "stora", "mogen",
        "mogna", "rumstempererad", "rumstempererat", "garnering", "valfritt", "kokt", "kokta",
        // da
        "uden", "finthakket", "groft", "skåret", "skrællet", "skrællede", "tern", "både", "smag", "tyndt",
        "tynde", "lille", "stuetemperatur", "valgfrit", "kogt", "kogte",
    ]

    /// Stems of preparation words with many endings (Russian, Norwegian, Spanish, German, French,
    /// Swedish, Danish).
    private static let notePrefixes: [String] = [
        "нарез", "мелк", "крупн", "тонк", "очищ", "натер", "натёр", "тёрт", "терт", "измельч", "рублен", "порублен",
        "нашинк", "размят", "растоп", "размягч", "охлажд", "комнатн", "вкус", "желани", "спел", "отвар",
        "finhakk", "grovhakk", "valgfri",
        "picad", "cortad", "rallad", "pelad", "trocead", "escurrid", "derretid", "machacad", "lavad",
        "deshuesad", "gehackt", "geschnitt", "gewürfelt", "gerieb", "geschält", "halbiert", "geviertelt",
        "zerdrück", "geschmolz", "gepresst", "abgetropf", "gewaschen", "entkernt", "zerlassen", "hach",
        "éminc", "coupé", "coupée", "râpé", "éplu", "pelé", "écras", "égoutt", "fondu", "ciselé", "épépin",
        "dénoyaut", "finhack", "grovhack", "finriv", "valfri", "finthakk", "grofthakk", "skåre",
    ]

    /// Water in the recipe languages (the server's staple words' first group).
    private static let waterWords: Set<String> = [
        "water", "vann", "vannet", "wasser", "agua", "eau", "acqua", "woda", "wody", "wodę", "vatten",
        "вода", "воды", "воду", "води", "кипяток", "окріп", "vand", "vandet",
    ]

    /// Whether a name the server marked a staple is water: nobody buys tap water for a recipe.
    static func isPlainWater(_ name: String) -> Bool {
        name.lowercased().split { $0.isWhitespace || $0 == "'" || $0 == "’" || $0 == "-" }.contains { waterWords.contains(String($0)) }
    }
}

