import CryptoKit
import Foundation

// The paid route's wire format, as `functions/CONTRACT.md` states it, and the pure rules around
// it: cleaning names the server would refuse, reading its answers and its errors. Nothing here
// touches the network, so it can be read (and one day tested) on its own.

// MARK: Requests

enum RecipeKind: String, Codable, Sendable {
    case recipe, menu
}

/// What the server takes for a recipe and for a menu of two or three courses a day. The server
/// decides and sends it beside every `balance`; the app only says it, from the last value it
/// received, or from `fallback` before it has received one.
struct RecipeCosts: Codable, Equatable, Sendable {
    var recipe: Int
    var menu2: Int
    var menu3: Int

    static let fallback = RecipeCosts(recipe: 1, menu2: 4, menu3: 5)

    init(recipe: Int, menu2: Int, menu3: Int) {
        self.recipe = recipe
        self.menu2 = menu2
        self.menu3 = menu3
    }

    /// Leniently: a field that is missing, not a whole number or below 1 takes its fallback
    /// value. Throws only when `costs` isn't an object at all.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        func read(_ key: CodingKeys, _ fallback: Int) -> Int {
            guard let value = (try? container.decodeIfPresent(Int.self, forKey: key)) ?? nil, value >= 1 else {
                return fallback
            }
            return value
        }
        recipe = read(.recipe, Self.fallback.recipe)
        menu2 = read(.menu2, Self.fallback.menu2)
        menu3 = read(.menu3, Self.fallback.menu3)
    }

    func cost(of kind: RecipeKind, courses: Int) -> Int {
        switch kind {
        case .recipe: return recipe
        case .menu: return courses == 3 ? menu3 : menu2
        }
    }

    /// What `request` takes, by its own kind and course count.
    func cost(of request: GenerateRequest) -> Int {
        cost(of: request.kind, courses: request.courses ?? 2)
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

    /// Everything the server binds a `requestId` to: the whole body but the id.
    func hasSameInputs(as other: GenerateRequest) -> Bool {
        var other = other
        other.requestId = requestId
        return self == other
    }
}

struct VerifyPurchaseRequest: Encodable, Sendable {
    let store = "appstore"
    var signedTransaction: String
    /// The person saw, before paying, that a refund request makes us tell Apple how many of
    /// the recipes were used. False when that isn't known (a purchase from an older build).
    var consumptionConsent: Bool
}

struct MergeBalanceRequest: Encodable, Sendable {
    var sourceIdToken: String
}

struct DeleteAccountRequest: Encodable, Sendable {
    var appleAuthorizationCode: String?
}

struct EmptyRequest: Encodable, Sendable {}

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

/// Every answer that carries `balance` also carries the costs and, for an account whose
/// earlier purchases were refunded after the recipes were used, `purchasesBlocked`.
protocol BalanceCarrying {
    var balance: Int { get }
    var costs: WireCosts? { get }
    var purchasesBlocked: WireFlag? { get }
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
}

struct BalanceResponse: Decodable, Sendable, BalanceCarrying {
    var balance: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
}

/// `moved` can be negative: a store refund's debt moves with the recipes.
struct MergeBalanceResponse: Decodable, Sendable, BalanceCarrying {
    var balance: Int
    var moved: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
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
    static func from(code: String, reason: String?, charged: Bool? = nil) -> RecipeServerError {
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
        case nil: break
        default: return .unrecognised(charged: charged ?? false)
        }
        switch code {
        case "unauthenticated": return .unauthenticated
        case "unavailable", "deadline-exceeded": return .offline
        case "invalid-argument": return .invalidRequest
        case "not-found", "unimplemented": return .callableMissing
        default: return .server
        }
    }

    /// Whether the same request may be sent again under the same id. A replayed App Check token
    /// is refused before anything is reserved, so the same id is still unused or still held.
    var isRetryableWithSameID: Bool {
        switch self {
        case .offline, .server, .appCheckReplayed, .unknownRequest, .callableMissing: return true
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

    /// What Generate sends while an interrupted request is still held: the held one, under its
    /// own id, when the form still asks for exactly the same thing (so a second tap fetches the
    /// first answer instead of paying again); otherwise the fresh one under its new id. An id is
    /// only ever stored and sent with the body it was made for, so the server never sees it
    /// with other inputs (REQUEST_ID_REUSED).
    static func requestToSend(fresh: GenerateRequest, held: GenerateRequest?) -> GenerateRequest {
        guard let held, held.hasSameInputs(as: fresh) else { return fresh }
        return held
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
