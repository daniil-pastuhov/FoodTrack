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

    /// A name cleaned to what the server's "Name rules" accept (CONTRACT.md; the server's own
    /// check is `functions/src/core/inputSafety.ts`), or nil when nothing is left:
    ///
    /// - normalised as the server does: NFKC, apostrophe and dash look-alikes folded to `'` and
    ///   `-`, `⁄` to `/`, invisible and bidirectional characters removed, every other space or
    ///   control character a space;
    /// - letters only of the Latin and Cyrillic scripts, with at most one combining mark each;
    ///   ASCII digits, space and hyphen; `' % , . / &` kept only where the server allows them;
    ///   anything else (Greek, CJK, emoji, other digits, other punctuation) becomes a space;
    /// - runs of spaces and of hyphens collapsed, at most 8 words, any hyphen part longer than 28
    ///   characters dropped, and at most 40 UTF-16 units (cut between characters).
    ///
    /// The server's "suspicious" checks (look-alike letters, encoded text, words about the model)
    /// aren't copied: an honest name that trips one is refused by field, and left out then.
    static func cleanName(_ raw: String) -> String? {
        var normalised = String.UnicodeScalarView()
        for scalar in raw.precomposedStringWithCompatibilityMapping.unicodeScalars {
            if isInvisible(scalar) { continue }
            if apostropheLookalikes.contains(scalar.value) {
                normalised.append("'")
            } else if isDashLookalike(scalar) {
                normalised.append("-")
            } else if scalar == "\u{2044}" {
                normalised.append("/")
            } else if isSpaceOrControl(scalar) {
                normalised.append(" ")
            } else {
                normalised.append(scalar)
            }
        }
        // NFKC again: removing invisible characters can bring a letter and its mark together.
        var scalars: [Unicode.Scalar] = []
        for scalar in String(normalised).precomposedStringWithCompatibilityMapping.unicodeScalars {
            if isLetter(scalar) {
                scalars.append(isLatinOrCyrillic(scalar) ? scalar : " ")
            } else if isMark(scalar) {
                // One mark, straight after a kept letter; any other mark goes (a stack of marks
                // is refused as suspicious).
                if let last = scalars.last, isLetter(last) { scalars.append(scalar) }
            } else if isASCIIDigit(scalar) || scalar == " " || scalar == "-" || namePunctuation.contains(scalar) {
                scalars.append(scalar)
            } else {
                scalars.append(" ")
            }
        }
        // Each pass only removes or shortens, so this settles; four passes are plenty.
        var name = String(String.UnicodeScalarView(scalars))
        for _ in 0..<4 {
            let before = name
            name = limitedWords(spacingRefusedPunctuation(tidied(name)))
            name = tidied(prefix(name, utf16Units: nameMaxLength))
            if name == before { break }
        }
        name = spacingRefusedPunctuation(tidied(name))
        return name.isEmpty ? nil : name
    }

    private static let apostropheLookalikes: Set<UInt32> = [0x2018, 0x2019, 0x201B, 0x02BC, 0x02B9, 0x60, 0xB4, 0x2032]
    private static let namePunctuation: Set<Unicode.Scalar> = ["'", "%", ",", ".", "/", "&"]

    private static func isDashLookalike(_ s: Unicode.Scalar) -> Bool {
        (0x2010...0x2015).contains(s.value) || [0x2212, 0xFE58, 0xFE63, 0xFF0D].contains(s.value)
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

    private static func isASCIIDigit(_ s: Unicode.Scalar) -> Bool { (0x30...0x39).contains(s.value) }

    /// Letters of the Latin and Cyrillic scripts, by block. Narrower than the scripts (no
    /// spacing modifier letters, no phonetic extensions): a letter left out here only becomes a
    /// space, while one the server doesn't count as Latin or Cyrillic would fail the request.
    private static func isLatinOrCyrillic(_ s: Unicode.Scalar) -> Bool {
        let v = s.value
        let latin = (0x41...0x5A).contains(v) || (0x61...0x7A).contains(v) || (0xC0...0x24F).contains(v)
            || (0x250...0x2AF).contains(v) || (0x1E00...0x1EFF).contains(v) || (0x2C60...0x2C7F).contains(v)
            || (0xA720...0xA7FF).contains(v) || (0xAB30...0xAB64).contains(v)
        let cyrillic = (0x400...0x52F).contains(v) || (0x1C80...0x1C88).contains(v) || (0xA640...0xA69F).contains(v)
        return latin || cyrillic
    }

    /// Runs of spaces and of hyphens collapsed, spaces trimmed.
    private static func tidied(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for s in text.unicodeScalars {
            if (s == " " || s == "-"), out.last == s { continue }
            out.append(s)
        }
        return String(out).trimmingCharacters(in: .whitespaces)
    }

    /// `' % , . / &` where the server's position rules refuse them become spaces.
    private static func spacingRefusedPunctuation(_ text: String) -> String {
        var chars = Array(text.unicodeScalars)
        var changed = true
        while changed {
            changed = false
            for i in chars.indices where namePunctuation.contains(chars[i]) {
                let prev: Unicode.Scalar? = i > 0 ? chars[i - 1] : nil
                let next: Unicode.Scalar? = i + 1 < chars.count ? chars[i + 1] : nil
                let beforePrev: Unicode.Scalar? = i > 1 ? chars[i - 2] : nil
                if !punctuationAllowed(chars[i], prev: prev, next: next, beforePrev: beforePrev) {
                    chars[i] = " "
                    changed = true
                }
            }
        }
        return tidied(String(String.UnicodeScalarView(chars)))
    }

    /// The server's rules, character by character (CONTRACT.md, "Name rules").
    static func punctuationAllowed(_ c: Unicode.Scalar, prev: Unicode.Scalar?, next: Unicode.Scalar?, beforePrev: Unicode.Scalar?) -> Bool {
        let letter: (Unicode.Scalar?) -> Bool = { $0.map(isLetter) ?? false }
        let digit: (Unicode.Scalar?) -> Bool = { $0.map(isASCIIDigit) ?? false }
        switch c {
        case "'": return letter(prev) && letter(next)
        case "&": return prev != nil && next != nil && (letter(prev) || prev == " ") && (letter(next) || next == " ")
        case ".": return (letter(prev) && (next == " " || digit(next))) || (digit(prev) && digit(next))
        case "%": return digit(prev) || (prev == " " && digit(beforePrev))
        case ",", "/": return digit(prev) && digit(next)
        default: return false
        }
    }

    /// At most 8 words, each hyphen part at most 28 characters (longer parts are dropped).
    private static func limitedWords(_ text: String) -> String {
        let words = text.split(separator: " ").compactMap { word -> String? in
            let parts = word.split(separator: "-").filter { $0.unicodeScalars.count <= maxCharsPerWordPart }
            let joined = parts.joined(separator: "-")
            return joined.isEmpty ? nil : joined
        }
        return words.prefix(maxWordsPerName).joined(separator: " ")
    }

    /// The longest run of whole characters from the start of `text` that is at most `limit`
    /// UTF-16 units: the server counts a name's length as JavaScript does, so a letter with
    /// combining marks, or one outside the BMP, counts for more than one.
    static func prefix(_ text: String, utf16Units limit: Int) -> String {
        var units = 0
        var end = text.startIndex
        for index in text.indices {
            let size = text[index].utf16.count
            guard units + size <= limit else { break }
            units += size
            end = text.index(after: index)
        }
        return String(text[..<end])
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
        today: Date = Date(),
        excluding refused: Set<String> = []
    ) -> GenerateRequest {
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

    /// The first allergen that can't be sent as the person wrote it: one with nothing left after
    /// cleaning (written in a script the server doesn't take), or one past the 20 the server
    /// accepts. An allergen is never left out silently: a recipe written without it could be
    /// unsafe, so the request isn't sent and the person is told.
    static func unsendableAllergen(_ allergens: [String]) -> String? {
        var seen = Set<String>()
        for raw in allergens.sorted() {
            guard let name = cleanName(raw) else { return raw }
            if seen.insert(name).inserted, seen.count > maxAllergens { return raw }
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
