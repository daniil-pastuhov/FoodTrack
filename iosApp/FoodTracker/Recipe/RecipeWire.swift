import Foundation

// The paid route's wire format, as `functions/CONTRACT.md` states it, and the pure rules around
// it: cleaning names the server would refuse, reading its answers and its errors. Nothing here
// touches the network, so it can be read (and one day tested) on its own.

// MARK: Requests

enum RecipeKind: String, Codable, Sendable {
    case recipe, menu

    /// What the server takes for one; it decides, the app only says it.
    var cost: Int { self == .menu ? 3 : 1 }
}

struct WireRecipeConfig: Codable, Equatable, Sendable {
    var difficulty: String
    var detail: String
    var servings: Int
    var maxMinutes: Int?
    var mealType: String
    var diet: String
    var language: String

    init(_ config: RecipeConfig) {
        difficulty = config.difficulty.rawValue
        detail = config.detail.rawValue
        servings = config.servings
        maxMinutes = config.maxMinutes
        mealType = config.mealType.rawValue
        diet = config.diet.rawValue
        language = config.resolvedLanguage.rawValue
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
    var config: WireRecipeConfig
    var ingredients: [WireIngredient]
    var allergens: [String]
    var today: String
}

struct VerifyPurchaseRequest: Encodable, Sendable {
    let store = "appstore"
    var signedTransaction: String
}

struct MergeBalanceRequest: Encodable, Sendable {
    var sourceIdToken: String
}

struct DeleteAccountRequest: Encodable, Sendable {
    var appleAuthorizationCode: String?
}

struct EmptyRequest: Encodable, Sendable {}

// MARK: Responses

enum GenerateStatus: String, Decodable, Sendable {
    case done = "DONE"
    case inProgress = "IN_PROGRESS"
    case failed = "FAILED"
}

struct GenerateResponse: Decodable, Sendable {
    var status: GenerateStatus
    var recipe: Recipe?
    var menu: RecipeMenu?
    var balance: Int
    var charged: Bool
}

enum VerifyStatus: String, Decodable, Sendable {
    case granted = "GRANTED"
    case alreadyGranted = "ALREADY_GRANTED"
    case rejectedPermanent = "REJECTED_PERMANENT"
    case retry = "RETRY"

    /// The contract finishes the store transaction on every answer but RETRY.
    var finishesTransaction: Bool { self != .retry }
}

struct VerifyPurchaseResponse: Decodable, Sendable {
    var status: VerifyStatus
    var balance: Int
}

struct BalanceResponse: Decodable, Sendable {
    var balance: Int
}

struct MergeBalanceResponse: Decodable, Sendable {
    var balance: Int
    var moved: Int
}

struct DeleteAccountResponse: Decodable, Sendable {
    var deleted: Bool
}

// MARK: Errors

/// What went wrong on a server call, by `details.reason` where the server gave one.
enum RecipeServerError: Error, Equatable, Sendable {
    case insufficientCredits
    case busy
    case generationDisabled
    case accountClosed
    case rateLimited
    case dailyCap
    case recentSignInRequired
    case mergeNotAllowed
    case invalidRequest
    /// No connection, a timeout, or the server unreachable: safe to retry with the same id.
    case offline
    /// `internal` and anything unexpected: also safe to retry with the same id.
    case server
    /// No Auth user, or no valid App Check token: this build can't use the paid route.
    case unauthenticated

    /// `code` is the callable's error code name ("failed-precondition"), `reason` its
    /// `details.reason`.
    static func from(code: String, reason: String?) -> RecipeServerError {
        switch reason {
        case "INSUFFICIENT_CREDITS": return .insufficientCredits
        case "BUSY": return .busy
        case "GENERATION_DISABLED": return .generationDisabled
        case "ACCOUNT_CLOSED": return .accountClosed
        case "RATE_LIMITED": return .rateLimited
        case "DAILY_CAP": return .dailyCap
        case "RECENT_SIGN_IN_REQUIRED": return .recentSignInRequired
        case "MERGE_NOT_ALLOWED": return .mergeNotAllowed
        case "INVALID_REQUEST": return .invalidRequest
        default: break
        }
        switch code {
        case "unauthenticated": return .unauthenticated
        case "unavailable", "deadline-exceeded": return .offline
        case "invalid-argument": return .invalidRequest
        default: return .server
        }
    }

    /// Whether the same request may be sent again under the same id.
    var isRetryableWithSameID: Bool { self == .offline || self == .server }
}

// MARK: Pure rules

enum RecipeWire {
    static let maxIngredients = 30
    static let maxAllergens = 20
    static let nameMaxLength = 40

    /// A name the server accepts: letters in any script, combining marks, digits, spaces and
    /// hyphens, 1 to 40 characters. Anything else becomes a space, runs of spaces collapse, and
    /// a name with nothing left is dropped (nil).
    static func cleanName(_ raw: String) -> String? {
        let scalars = raw.precomposedStringWithCanonicalMapping.unicodeScalars.map { scalar -> Character in
            switch scalar.properties.generalCategory {
            // \p{L}, \p{M} and \p{N}, as the server's pattern.
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
                 .nonspacingMark, .spacingMark, .enclosingMark,
                 .decimalNumber, .letterNumber, .otherNumber:
                return Character(scalar)
            default:
                return scalar == "-" ? "-" : " "
            }
        }
        let collapsed = String(scalars).split(separator: " ", omittingEmptySubsequences: true).joined(separator: " ")
        guard !collapsed.isEmpty else { return nil }
        let cut = String(collapsed.prefix(nameMaxLength)).trimmingCharacters(in: .whitespaces)
        return cut.isEmpty ? nil : cut
    }

    /// `YYYY-MM-DD` in the phone's own calendar day, as the server's `today` and `expiresOn`.
    static func isoDay(_ date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 1970, parts.month ?? 1, parts.day ?? 1)
    }

    static func request(
        for request: RecipeRequest,
        kind: RecipeKind,
        courses: Int,
        requestID: UUID,
        today: Date = Date()
    ) -> GenerateRequest {
        var seen = Set<String>()
        let ingredients = request.items.compactMap { item -> WireIngredient? in
            guard let name = cleanName(item.name), seen.insert(name.lowercased()).inserted else { return nil }
            return WireIngredient(
                name: name,
                expiresOn: item.expiresOn.map { isoDay($0) },
                quantity: item.quantity.map { min(max($0, 0), 9999) }
            )
        }
        let allergens = Array(Set(request.allergens.compactMap(cleanName))).sorted().prefix(maxAllergens)
        return GenerateRequest(
            requestId: requestID.uuidString.lowercased(),
            kind: kind,
            courses: kind == .menu ? (courses == 3 ? 3 : 2) : nil,
            config: WireRecipeConfig(request.config),
            ingredients: Array(ingredients.prefix(maxIngredients)),
            allergens: Array(allergens),
            today: isoDay(today)
        )
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

    /// Server recipes are written by the server, whatever the JSON says.
    static func markedServer(_ recipe: Recipe) -> Recipe {
        var recipe = recipe
        recipe.source = .server
        return recipe
    }
}
