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
    /// `true` with a non-empty `allergens`, sent only after the person agreed to send them
    /// (`AllergenConsent`); nil (left out of the JSON) otherwise. The server refuses allergens
    /// without it (`ALLERGEN_CONSENT_REQUIRED`). Nil in a request stored by an older version.
    var allergenConsent: Bool?
    /// The rest of the pantry, which the recipe may use as well ("Send all products"): only to a
    /// server that said `acceptsOptionalIngredients`, already capped by
    /// `RecipeWire.optionalIngredients`; nil (left out of the JSON) otherwise, and in a request
    /// stored by an older version. Part of the inputs a `requestId` is bound to.
    var optionalIngredients: [WireIngredient]?

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
/// earlier purchases were refunded after the recipes were used, `purchasesBlocked`; and, from a
/// server that reads `optionalIngredients`, `acceptsOptionalIngredients: true`.
protocol BalanceCarrying {
    var balance: Int { get }
    var costs: WireCosts? { get }
    var purchasesBlocked: WireFlag? { get }
    var acceptsOptionalIngredients: WireFlag? { get }
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
}

struct BalanceResponse: Decodable, Sendable, BalanceCarrying {
    var balance: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
    var acceptsOptionalIngredients: WireFlag?
}

/// `moved` can be negative: a store refund's debt moves with the recipes.
struct MergeBalanceResponse: Decodable, Sendable, BalanceCarrying {
    var balance: Int
    var moved: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
    var acceptsOptionalIngredients: WireFlag?
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
    /// request's `otherItems` go as `optionalIngredients`.
    static func request(
        for request: RecipeRequest,
        kind: RecipeKind,
        courses: Int,
        requestID: UUID,
        today: Date = Date(),
        excluding refused: Set<String> = [],
        withOptional: Bool = false
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
            courses: kind == .menu ? (courses == 3 ? 3 : 2) : nil,
            config: WireRecipeConfig(request.config),
            ingredients: Array(ingredients.prefix(maxIngredients)),
            allergens: Array(allergens),
            today: isoDay(today),
            // Built only past the consent step (`writeOnServer`), so allergens here were agreed to.
            allergenConsent: AllergenConsent.flag(for: Array(allergens)),
            optionalIngredients: optional.isEmpty ? nil : optional
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
    static let version = 1

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
