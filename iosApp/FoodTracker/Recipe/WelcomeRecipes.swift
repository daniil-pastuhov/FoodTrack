import Foundation

// Free recipes for signing in (the backend's "welcome grant", functions/CONTRACT.md
// `getWelcomeOffer`, `claimWelcome`, "Capability `acceptsWelcome`"): the wire types and the pure
// rules for what is shown where, when the offer is asked again and what a claim came to. Nothing
// here touches the network or the screen, so iosApp/RecipeConfigCheck/check.sh runs it as it is.
// One rule on both platforms; Android: `WelcomeRecipes.kt` in shared/.../recipe/.

// MARK: Wire

/// `getWelcomeOffer`'s answer: how many free recipes a sign-in gets now. Read leniently: anything
/// but a whole number from 0 to `WelcomeRecipes.maxRecipes` reads as 0 (no offer).
struct WelcomeOfferResponse: Decodable, Sendable, Equatable {
    let recipes: Int

    init(recipes: Int) { self.recipes = WelcomeRecipes.believed(recipes) }

    private enum CodingKeys: String, CodingKey { case recipes }

    init(from decoder: Decoder) throws {
        let container = try? decoder.container(keyedBy: CodingKeys.self)
        let value: LenientInt? = (try? container?.decodeIfPresent(LenientInt.self, forKey: .recipes)) ?? nil
        recipes = WelcomeRecipes.believed(value?.value)
    }
}

/// `claimWelcome`'s status. Anything else reads as nil (the claim's answer isn't understood).
enum WelcomeClaimStatus: String, Sendable {
    case granted = "GRANTED"
    case already = "ALREADY"
    case off = "OFF"
}

/// `claimWelcome`'s answer: the status, how many were added, and everything an answer with a
/// balance carries.
struct ClaimWelcomeResponse: Decodable, Sendable, BalanceCarrying {
    var status: WireText?
    var granted: LenientInt?
    var balance: Int
    var costs: WireCosts?
    var purchasesBlocked: WireFlag?
    var acceptsOptionalIngredients: WireFlag?
    var acceptsMultiChoiceConfig: WireFlag?
    var acceptsDanish: WireFlag?
    var acceptsMenuDays: WireFlag?
    var acceptsTaste: WireFlag?
    var acceptsStaples: WireFlag?
    var tasteQuizVersion: WireText?
    var acceptsWelcome: WireFlag?
    var welcomeRecipes: LenientInt?

    var claimStatus: WelcomeClaimStatus? { status?.value.flatMap(WelcomeClaimStatus.init(rawValue:)) }
}

// MARK: Kept on the phone

/// The last answer of `getWelcomeOffer`, with when it came. Kept beside the other recipe state
/// (`RecipeCreditsStorage`), outside backups and `WireSettings`.
struct WelcomeOfferAnswer: Codable, Equatable, Sendable {
    var recipes: Int
    var receivedAt: Date
}

// MARK: Rules

/// Where the person stands, for the offer: no account, recipes kept on this device (an anonymous
/// account), or signed in with Apple or Google.
enum WelcomeAccount: Equatable, Sendable {
    case none
    case device
    case signedIn
}

/// Everything the offer depends on, read from the paid route's state at one moment.
struct WelcomeSituation: Equatable, Sendable {
    /// The paid route can exist here (App Check, `RecipeCredits.isPaidRouteAvailable`).
    var paidRouteAvailable: Bool
    /// The store has actually offered packs: not unreachable, restricted, or selling none.
    var packsOffered = true
    /// Recipes from packs are paused (`GENERATION_DISABLED`): no offer, as no Buy.
    var buyingPaused = false
    /// The account may not buy (`purchasesBlocked`): no offer, as no Buy.
    var purchasesBlocked = false
    var account: WelcomeAccount
    /// This account has claimed on this phone (GRANTED or ALREADY): never offered again to it.
    var claimed = false
    /// The last `getWelcomeOffer` answer if it is under a day old (`WelcomeRecipes.fresh`).
    var offer: Int?
    /// `welcomeRecipes` from the last answer with a balance for this account (0 from a server
    /// without `acceptsWelcome`), if under a day old (`fresh`); nil otherwise. Read only when
    /// signed in with Apple or Google.
    var accountRecipes: Int?
}

/// The offer as shown: always with the server's number. `needsSignIn`: "Sign in and get 3 free
/// recipes" (the sign-in sheet); otherwise "Get 3 free recipes" (one tap claims).
struct WelcomeHint: Equatable, Sendable {
    let recipes: Int
    let needsSignIn: Bool
}

/// What a claim came to.
enum WelcomeClaimOutcome: Equatable, Sendable {
    /// `granted` free recipes were added (from the answer itself).
    case granted(Int)
    /// This Apple or Google account has had them already, here or anywhere.
    case already
    /// Off for now, or today's grants are used up; nothing changed.
    case off
    /// The claim didn't reach our server, or its answer wasn't understood: Try again (a claim is
    /// idempotent, so a retry never grants twice).
    case notReached
}

/// What follows a sign-in.
enum WelcomeAfterSignIn: Equatable, Sendable {
    /// No offer was showing: an ordinary sign-in.
    case nothing
    /// An offer was showing: claim once.
    case claim
    /// An offer was showing, and this account claimed on this phone before: said as ALREADY
    /// without calling (the contract: never call again after GRANTED or ALREADY).
    case alreadyClaimed
}

/// The line under the offer in menu mode, when the pack has too few for the chosen menu set.
enum WelcomeMenuCover: Equatable, Sendable {
    /// "3 free recipes are enough for this menu."
    case enough
    /// "3 free recipes cover single recipes or a smaller menu, not this one."
    case smallerMenu
    /// "3 free recipes cover single recipes, not a menu."
    case singleOnly
}

enum WelcomeRecipes {
    /// The server's own limit (`config/runtime.welcome.recipes`, 0-20); above it isn't believed.
    static let maxRecipes = 20
    /// An answer older than this counts as none.
    static let offerLifetime: TimeInterval = 24 * 60 * 60
    /// Asked again when the Recipe tab is shown, but not more often than this.
    static let refreshInterval: TimeInterval = 15 * 60

    static func believed(_ value: Int?) -> Int {
        guard let value, (0...maxRecipes).contains(value) else { return 0 }
        return value
    }

    /// The cached answer's number if it is less than a day old; nil otherwise (none, too old, or
    /// from the future after a clock change, which can't be trusted either).
    static func fresh(_ answer: WelcomeOfferAnswer?, now: Date) -> Int? {
        guard let answer else { return nil }
        let age = now.timeIntervalSince(answer.receivedAt)
        guard age >= 0, age < offerLifetime else { return nil }
        return answer.recipes
    }

    /// Whether this install asks `getWelcomeOffer` at all: only where the paid route can exist,
    /// and only while signed out (no account, or recipes on this device). Signed in, the number
    /// comes beside the balance with no extra call. Android: `welcomeOfferAskable`.
    static func offerAskable(_ s: WelcomeSituation) -> Bool {
        gate(s) && s.account != .signedIn
    }

    /// Whether free recipes could be offered at all, whatever the number: where Buy would be (the
    /// paid route, packs actually offered, not paused, not blocked). Android: `welcomeGate`.
    static func gate(_ s: WelcomeSituation) -> Bool {
        s.paidRouteAvailable && s.packsOffered && !s.buyingPaused && !s.purchasesBlocked
    }

    /// Whether to ask now. Right before the sign-in sheet opens: always, so its number is current.
    /// When the tab is shown: when nothing was asked or answered in the last 15 minutes, measured
    /// from the last ask (`lastAsked`, kept across launches and set on every ask, failed ones too,
    /// so a failing server isn't asked on every showing or cold start); without one, from the
    /// answer's time unless that lies in the future. A clock moved back before the last ask counts
    /// as time passed once: that ask is made, and its own time takes over. Android:
    /// `shouldAskWelcomeOffer`.
    static func shouldAsk(lastAsked: Date?, answer: WelcomeOfferAnswer?, now: Date, beforeSignIn: Bool) -> Bool {
        if beforeSignIn { return true }
        guard let last = lastAsked ?? answer.map(\.receivedAt).flatMap({ $0 <= now ? $0 : nil }) else { return true }
        let since = now.timeIntervalSince(last)
        return since < 0 || since >= refreshInterval
    }

    /// What a failed ask leaves: an old server (`not-found` / unimplemented) answers 0; anything
    /// else (offline, App Check, busy) keeps the last answer, until it is a day old, and shows no
    /// error.
    static func answer(afterFailure error: RecipeServerError, cached: WelcomeOfferAnswer?, now: Date) -> WelcomeOfferAnswer? {
        error == .callableMissing ? WelcomeOfferAnswer(recipes: 0, receivedAt: now) : cached
    }

    /// The offer, if any. Never without a number above zero, never where buying isn't offered
    /// (no paid route, paused, blocked), never to an account that has claimed. Signed in, the
    /// number beside the balance comes first; the day's answer stands in until one arrives.
    static func hint(_ s: WelcomeSituation) -> WelcomeHint? {
        guard gate(s) else { return nil }
        if s.account == .signedIn, s.claimed { return nil }
        // The account's own number only for an Apple or Google sign-in; signed out (no account,
        // or recipes on this device) the offer's answer is the only source.
        let number = s.account == .signedIn ? (s.accountRecipes ?? s.offer) : s.offer
        guard let recipes = number, recipes > 0 else { return nil }
        return WelcomeHint(recipes: recipes, needsSignIn: s.account != .signedIn)
    }

    /// Any sign-in made while an offer shows claims (the offer itself, Recipe settings' buttons,
    /// the purchase choice).
    static func afterSignIn(offerShowing: Bool, claimedHere: Bool) -> WelcomeAfterSignIn {
        guard offerShowing else { return .nothing }
        return claimedHere ? .alreadyClaimed : .claim
    }

    static func outcome(of response: ClaimWelcomeResponse) -> WelcomeClaimOutcome {
        switch response.claimStatus {
        case .granted?: return .granted(max(0, response.granted?.value ?? 0))
        case .already?: return .already
        case .off?: return .off
        case nil: return .notReached
        }
    }

    /// A claim that failed. A server without `claimWelcome` (not-found / unimplemented) has no
    /// grant: OFF, with no Try again, which could only spend a limited-use token for nothing.
    /// Anything else (`SIGN_IN_REQUIRED` included, which a signed-in account can't get) leaves the
    /// offer as "Get 3 free recipes" with Try again. Android: `welcomeOutcomeOf`.
    static func outcome(of error: RecipeServerError) -> WelcomeClaimOutcome {
        error == .callableMissing ? .off : .notReached
    }

    /// After GRANTED or ALREADY the account is never offered (or claimed for) again on this phone.
    static func claimSettled(_ outcome: WelcomeClaimOutcome) -> Bool {
        switch outcome {
        case .granted, .already: return true
        case .off, .notReached: return false
        }
    }

    /// Whether an outcome is said. Always, except for a claim made beside the first purchase
    /// (`quiet`), where only a grant is: no dialog over the store's payment sheet.
    static func announces(_ outcome: WelcomeClaimOutcome, quiet: Bool) -> Bool {
        guard quiet else { return true }
        if case .granted = outcome { return true }
        return false
    }

    /// `welcomeRecipes` as an answer with a balance gives it: the number from a server that says
    /// `acceptsWelcome`, 0 from one that doesn't (no grant there).
    static func accountRecipes(acceptsWelcome: Bool, welcomeRecipes: Int?) -> Int {
        acceptsWelcome ? believed(welcomeRecipes) : 0
    }

    /// The menu-mode line: whether the free recipes, with what is in the pack, cover `menuCost`;
    /// otherwise whether some smaller menu the server prices would fit (the 3-day menus and, from a
    /// server with menu sets, every set in `costs.menuSets`).
    static func menuCover(recipes: Int, balance: Int, menuCost: Int, costs: RecipeCosts, takesMenuDays: Bool) -> WelcomeMenuCover {
        let available = recipes + max(0, balance)
        if available >= menuCost { return .enough }
        var prices = [costs.menu2, costs.menu3]
        if takesMenuDays, let sets = costs.menuSets { prices += sets.values.flatMap(\.values) }
        return prices.contains { $0 <= available } ? .smallerMenu : .singleOnly
    }
}

// MARK: A sign-in linked to an existing account

extension WelcomeRecipes {
    /// Right after a sign-in is linked to an existing account (`user.link(with:)`), before
    /// anything else and whether or not an offer shows: the ID token is refreshed so it names the
    /// new provider, then `getBalance` is called once. Returns the balance answer, or nil when
    /// either step failed: silent, retried by the next balance call, and never failing or undoing
    /// the link. Android: `recordLinkedSignIn`.
    ///
    /// Why: the server only learns which Google and Apple accounts belong to an account when the
    /// app talks to it, and it records each one it sees so that the free recipes are given once
    /// per Google or Apple account (`welcomes/`). Firebase does not tell the server about a link
    /// or an unlink. Without this call, a sign-in linked and then unlinked again before the next
    /// server call would never be recorded, and could claim free recipes again from another
    /// account. The apps never unlink, so this needs a tampered client; the call makes the window
    /// as short as one request.
    static func recordLinkedSignIn<Balance>(
        refreshToken: () async throws -> Void,
        balance: () async throws -> Balance
    ) async -> Balance? {
        do {
            try await refreshToken()
            return try await balance()
        } catch {
            return nil
        }
    }
}
