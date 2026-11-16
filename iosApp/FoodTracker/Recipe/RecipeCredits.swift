import Combine
import FirebaseAppCheck
import FirebaseAuth
import FirebaseCore
import Foundation
import Network
import StoreKit
import SwiftUI
import UIKit

/// What a purchase came to, for the pack sheet.
enum RecipePurchaseOutcome: Equatable {
    case added
    /// Paid; our server hasn't confirmed it yet. The callout on the tab takes over.
    case notAddedYet
    /// Ask to Buy, a bank's approval, a cash top-up.
    case pending
    case cancelled
    /// The store, or our server before any money moved, couldn't be reached.
    case storeUnavailable
    /// App Check (or, with it, the anonymous sign-in) refused this install before any money moved:
    /// recipes from packs aren't available here. Not a connection problem, so no Try again.
    /// `appCheck`: it was App Check's own token that was refused (Debug builds name the cause).
    case notAvailableHere(appCheck: Bool)
    /// The server refused it for good: refunded, or already used.
    case rejected
}

/// The one-off messages the paid route raises, shown as snackbars by the screen on top.
enum RecipeCreditsNotice: Equatable {
    case added(Int)
    case signInFailed
    case providerInUse
    case accountDeleted
    /// The recipes kept on this phone (an anonymous account) and what our server held for them
    /// were deleted; the phone is back to never having bought. Android's `DeviceDataDeleted`.
    case deviceDataDeleted
    case accountActionFailed
    /// Deleting waits for a purchase that is paid, or whose payment is under way, and not added
    /// yet: the server ties it to this account, so deleting now would leave it with nobody.
    case deletionWaitsForPurchase
    /// `BUSY` from `deleteAccount` or `mergeBalance`: a recipe is still being written on the
    /// account. Nothing changed; try again in a moment.
    case accountBusy
    /// A join of this install into an account moved recipes (`moved` > 0) or, after a store
    /// refund, a debt (`moved` < 0). As Android's `PaidEvent.Merged`.
    case merged(moved: Int)
    /// Free recipes for signing in (`claimWelcome`): GRANTED, "3 free recipes added".
    case welcomeGranted(Int)
    /// OFF: not available right now. `justSignedIn`: the person signed in for them, which the
    /// message says too; nothing else changed.
    case welcomeOff(justSignedIn: Bool)
    /// The claim didn't reach our server (or wasn't understood): Try again claims once more.
    case welcomeNotReached

    /// The failures, and a deletion whatever came of it, rule the rating prompt out for the
    /// session, as Android's `ReviewPrompter.isProblem(PaidEvent)`.
    var blocksReviewPrompt: Bool {
        switch self {
        case .signInFailed, .providerInUse, .accountActionFailed, .accountDeleted, .deviceDataDeleted, .accountBusy,
             .deletionWaitsForPurchase, .welcomeNotReached:
            return true
        // A grant is neutral for the rating prompt, and so is an answer that there is none now.
        case .added, .merged, .welcomeGranted, .welcomeOff: return false
        }
    }
}

/// Keys the paid route keeps on this phone, outside `AppSettings` and backups. A store
/// transaction is the purchase's own record; these only let the screen say so straight away.
struct RecipeCreditsStorage {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    private enum Key {
        static let unconfirmed = "com.foodtracker.recipe.unconfirmed_purchases"
        static let pendingSince = "com.foodtracker.recipe.payment_pending_since"
        static let choiceMade = "com.foodtracker.recipe.purchase_choice_made"
        static let balance = "com.foodtracker.recipe.balance"
        static let balanceUID = "com.foodtracker.recipe.balance_uid"
        static let generation = "com.foodtracker.recipe.pending_generation"
        static let generationSavedAt = "com.foodtracker.recipe.pending_generation_saved_at"
        static let courses = "com.foodtracker.recipe.menu_courses"
        static let days = "com.foodtracker.recipe.menu_days"
        static let costs = "com.foodtracker.recipe.costs"
        static let blocked = "com.foodtracker.recipe.purchases_blocked"
        static let consentStarts = "com.foodtracker.recipe.consumption_consent_started"
        static let allergenConsent = "com.foodtracker.recipe.allergen_consent"
        static let serverTakesOptional = "com.foodtracker.recipe.server_takes_optional"
        static let serverTakesMultiChoice = "com.foodtracker.recipe.server_takes_multi_choice"
        static let serverTakesDanish = "com.foodtracker.recipe.server_takes_danish"
        static let serverTakesMenuDays = "com.foodtracker.recipe.server_takes_menu_days"
        static let serverTakesTaste = "com.foodtracker.recipe.server_takes_taste"
        static let serverTakesStaples = "com.foodtracker.recipe.server_takes_staples"
        static let welcomeOffer = "com.foodtracker.recipe.welcome_offer"
        static let welcomeOfferAskedAt = "com.foodtracker.recipe.welcome_offer_asked_at"
        static let welcomeClaimed = "com.foodtracker.recipe.welcome_claimed"
        static let welcomeAccount = "com.foodtracker.recipe.welcome_account"
    }

    private struct StoredWelcomeAccount: Codable {
        var uid: String
        var answer: WelcomeOfferAnswer
    }

    /// `welcomeRecipes` as the last answer with a balance gave it, with its time, for `uid` only
    /// (`WelcomeRecipes.fresh` decides whether it still counts). Android: `welcomeAccount`.
    func welcomeAccount(for uid: String) -> WelcomeOfferAnswer? {
        guard let stored = decode(StoredWelcomeAccount.self, Key.welcomeAccount), stored.uid == uid else { return nil }
        return stored.answer
    }

    func cacheWelcomeAccount(_ answer: WelcomeOfferAnswer, for uid: String) {
        encode(StoredWelcomeAccount(uid: uid, answer: answer), Key.welcomeAccount)
    }

    /// The last answer of `getWelcomeOffer` and when it came (`WelcomeRecipes.fresh` decides
    /// whether it still counts). Android: `welcome_offer` in `RecipePaidStore`.
    var welcomeOffer: WelcomeOfferAnswer? {
        get { decode(WelcomeOfferAnswer.self, Key.welcomeOffer) }
        nonmutating set { encode(newValue, Key.welcomeOffer) }
    }

    /// When `getWelcomeOffer` was last asked, answered or not: at most every 15 minutes.
    var welcomeOfferAskedAt: Date? {
        get { defaults.object(forKey: Key.welcomeOfferAskedAt) as? Date }
        nonmutating set { defaults.set(newValue, forKey: Key.welcomeOfferAskedAt) }
    }

    /// The accounts (uids) that claimed on this phone, GRANTED or ALREADY: never offered again.
    var welcomeClaimed: Set<String> {
        get { Set(defaults.stringArray(forKey: Key.welcomeClaimed) ?? []) }
        nonmutating set { defaults.set(newValue.sorted(), forKey: Key.welcomeClaimed) }
    }

    /// The server's last answer that carried a balance said `acceptsOptionalIngredients`: only
    /// then are the other pantry items sent with "Send all products".
    var serverTakesOptional: Bool {
        get { defaults.bool(forKey: Key.serverTakesOptional) }
        nonmutating set { defaults.set(newValue, forKey: Key.serverTakesOptional) }
    }

    /// The same for `acceptsMultiChoiceConfig`: only then is a config sent that the legacy values
    /// can't say exactly (`WireRecipeConfig.make`).
    var serverTakesMultiChoice: Bool {
        get { defaults.bool(forKey: Key.serverTakesMultiChoice) }
        nonmutating set { defaults.set(newValue, forKey: Key.serverTakesMultiChoice) }
    }

    /// The same for `acceptsDanish`: only then is a request in Danish sent.
    var serverTakesDanish: Bool {
        get { defaults.bool(forKey: Key.serverTakesDanish) }
        nonmutating set { defaults.set(newValue, forKey: Key.serverTakesDanish) }
    }

    /// The same for `acceptsMenuDays`: only then are the Days choice and one course a day
    /// offered, and `days` / `courses: 1` sent (`MenuSet`).
    var serverTakesMenuDays: Bool {
        get { defaults.bool(forKey: Key.serverTakesMenuDays) }
        nonmutating set { defaults.set(newValue, forKey: Key.serverTakesMenuDays) }
    }

    /// The same for `acceptsTaste`: only then does a non-empty taste profile go as `taste`
    /// (`TasteProfile.toSend`). Cleared when a request that carried it was refused as invalid.
    var serverTakesTaste: Bool {
        get { defaults.bool(forKey: Key.serverTakesTaste) }
        nonmutating set { defaults.set(newValue, forKey: Key.serverTakesTaste) }
    }

    /// The same for `acceptsStaples`: only then do the person's staples go as `staples`
    /// (`RecipeWire.stapleNames`). Cleared when a request that carried them was refused as invalid.
    var serverTakesStaples: Bool {
        get { defaults.bool(forKey: Key.serverTakesStaples) }
        nonmutating set { defaults.set(newValue, forKey: Key.serverTakesStaples) }
    }

    /// The answer to sending allergens to our server (`AllergenConsent`), with its time and the
    /// wording's version. Nil until asked.
    var allergenConsent: AllergenConsentRecord? {
        get { decode(AllergenConsentRecord.self, Key.allergenConsent) }
        nonmutating set { encode(newValue, Key.allergenConsent) }
    }

    /// When the sheet last started a purchase of each product with the consumption line shown,
    /// by product id: how a transaction that arrives later (Ask to Buy) knows its consent.
    var consentStarts: [String: Date] {
        get { decode([String: Date].self, Key.consentStarts) ?? [:] }
        nonmutating set { encode(newValue, Key.consentStarts) }
    }

    /// The last costs the server sent, whichever account it sent them to: they are the same for
    /// everyone. Nil until the first answer that carried them.
    var costs: RecipeCosts? {
        get { decode(RecipeCosts.self, Key.costs) }
        nonmutating set { encode(newValue, Key.costs) }
    }

    var unconfirmed: [UnconfirmedPurchase] {
        get { decode([UnconfirmedPurchase].self, Key.unconfirmed) ?? [] }
        nonmutating set { encode(newValue, Key.unconfirmed) }
    }

    var paymentPendingSince: Date? {
        get { defaults.object(forKey: Key.pendingSince) as? Date }
        nonmutating set { defaults.set(newValue, forKey: Key.pendingSince) }
    }

    var purchaseChoiceMade: Bool {
        get { defaults.bool(forKey: Key.choiceMade) }
        nonmutating set { defaults.set(newValue, forKey: Key.choiceMade) }
    }

    /// The last balance the server gave for `uid`, shown while offline.
    func cachedBalance(for uid: String) -> Int? {
        guard defaults.string(forKey: Key.balanceUID) == uid, defaults.object(forKey: Key.balance) != nil else { return nil }
        return defaults.integer(forKey: Key.balance)
    }

    /// Whether the server last said `uid` may not buy: kept with the balance.
    func cachedPurchasesBlocked(for uid: String) -> Bool {
        defaults.string(forKey: Key.balanceUID) == uid && defaults.bool(forKey: Key.blocked)
    }

    func cache(balance: Int, purchasesBlocked: Bool = false, for uid: String) {
        defaults.set(uid, forKey: Key.balanceUID)
        defaults.set(balance, forKey: Key.balance)
        defaults.set(purchasesBlocked, forKey: Key.blocked)
    }

    /// The generation a lost connection, a server error or a long IN_PROGRESS interrupted,
    /// asked after under the same id. Dropped after 6 days: the server keeps a finished
    /// generation for 7 days after it finished, so until then asking can only fetch it (or
    /// start one that never reached the server), never charge a second time.
    var pendingGeneration: GenerateRequest? {
        get {
            guard let savedAt = defaults.object(forKey: Key.generationSavedAt) as? Date,
                  Date().timeIntervalSince(savedAt) < Self.pendingGenerationLifetime else { return nil }
            return decode(GenerateRequest.self, Key.generation)
        }
        nonmutating set {
            encode(newValue, Key.generation)
            defaults.set(newValue == nil ? nil : Date(), forKey: Key.generationSavedAt)
        }
    }

    static let pendingGenerationLifetime: TimeInterval = 6 * 24 * 60 * 60

    /// The menu's courses a day as last chosen: 1, 2 or 3 (two when never chosen). One is used
    /// only while the server takes menu sets (`MenuSet.effective`).
    var menuCourses: Int {
        get {
            let stored = defaults.integer(forKey: Key.courses)
            return MenuSet.courseChoices.contains(stored) ? stored : 2
        }
        nonmutating set { defaults.set(MenuSet.courseChoices.contains(newValue) ? newValue : 2, forKey: Key.courses) }
    }

    /// The menu's days as last chosen: 1 to 4 (three when never chosen). Used only while the
    /// server takes menu sets.
    var menuDays: Int {
        get {
            let stored = defaults.integer(forKey: Key.days)
            return MenuSet.dayChoices.contains(stored) ? stored : MenuSet.defaultDays
        }
        nonmutating set { defaults.set(MenuSet.dayChoices.contains(newValue) ? newValue : MenuSet.defaultDays, forKey: Key.days) }
    }

    private func decode<T: Decodable>(_ type: T.Type, _ key: String) -> T? {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(type, from: $0) }
    }

    private func encode<T: Encodable>(_ value: T?, _ key: String) {
        if let value, let data = try? JSONEncoder().encode(value) {
            defaults.set(data, forKey: key)
        } else {
            defaults.removeObject(forKey: key)
        }
    }
}

/// The paid route's state: the recipe count, the account, the packs and every purchase on its
/// way to the server. One per app, created with the Recipe tab's model and started at launch
/// so no transaction waits for the tab to be opened.
@MainActor
final class RecipeCredits: ObservableObject {
    /// A purchase, account or server problem shown on screen rules the rating prompt out for
    /// the session (`ReviewPrompter`).
    private static func noteReviewProblem() { ReviewPrompter.shared.problemShown() }

    /// Nil while there is no user, which is the state of anyone who never bought.
    @Published private(set) var balance: Int?
    /// What a recipe and a menu take, as the server last said; 1, 4 and 5 until it has.
    @Published private(set) var costs: RecipeCosts
    /// The server's last answer with a balance said `acceptsMenuDays` (kept in `storage`):
    /// the form offers Days and one course a day.
    @Published private(set) var serverTakesMenuDays: Bool
    /// The taste quiz's version as the last reply with a balance named it (`tasteQuizVersion`):
    /// when it isn't the cached one, the definition is fetched quietly (`RecipeViewModel`).
    @Published private(set) var serverTasteQuizVersion: String?
    /// Whatever sets it (the Auth listener, sign-in, merge, `ensureUser`, sign-out, deletion), a new
    /// uid reads its own stored `welcomeRecipes`, never the previous account's.
    @Published private(set) var account: RecipeAccountState = .none {
        didSet {
            if account.uid != oldValue.uid { welcomeAccountAnswer = account.uid.flatMap(storage.welcomeAccount(for:)) }
        }
    }
    @Published private(set) var packs: RecipePacksState = .idle { didSet { if packs == .unreachable { Self.noteReviewProblem() } } }
    @Published private(set) var unconfirmed: [UnconfirmedPurchase] = []
    @Published private(set) var paymentPending = false
    @Published private(set) var serverPaused = false { didSet { if serverPaused { Self.noteReviewProblem() } } }
    /// Earlier purchases were refunded after the recipes were used: no way to buy shows, and a
    /// line says why. What is in the balance still works.
    @Published private(set) var purchasesBlocked = false
    /// The server takes no App Store purchases from this build (`STORE_MISMATCH`), for this
    /// launch: buying is hidden, and the purchase stays unfinished rather than being lost.
    @Published private(set) var storeMismatch = false { didSet { if storeMismatch { Self.noteReviewProblem() } } }
    @Published private(set) var attestationFailed = false
    /// A server call failed for want of a connection, or the phone has none.
    @Published private(set) var connectionLost = false { didSet { if connectionLost { Self.noteReviewProblem() } } }
    @Published private(set) var isOnline = true
    @Published private(set) var isConfirming = false
    @Published private(set) var isSigningIn = false
    @Published var notice: RecipeCreditsNotice? { didSet { if notice?.blocksReviewPrompt == true { Self.noteReviewProblem() } } }
    /// "This purchase couldn't be added", a T6 dialog.
    @Published var rejectedPurchase = false { didSet { if rejectedPurchase { Self.noteReviewProblem() } } }
    /// The same dialog for a purchase this build can't add (`STORE_MISMATCH`): it says why, and
    /// that the purchase was not added. Once per purchase per launch.
    @Published var rejectedByBuild = false { didSet { if rejectedByBuild { Self.noteReviewProblem() } } }
    /// "Add this phone's recipes to your account?"
    @Published var pendingMerge: RecipePendingMerge?
    /// The last `getWelcomeOffer` answer, shown at once while under a day old.
    @Published private(set) var welcomeOffer: WelcomeOfferAnswer?
    /// `welcomeRecipes` from the last answer with a balance for the account signed in now, with
    /// its time (kept across runs, `RecipeCreditsStorage.welcomeAccount`); nil until one came.
    @Published private(set) var welcomeAccountAnswer: WelcomeOfferAnswer?
    /// The accounts that claimed on this phone (`RecipeCreditsStorage.welcomeClaimed`).
    @Published private(set) var welcomeClaimed: Set<String>
    /// `claimWelcome` is on its way: "Getting your free recipes…" / "Adding your free recipes…".
    @Published private(set) var isClaimingWelcome = false
    /// ALREADY: "This account has already had its free recipes", a dialog with See recipe packs.
    @Published var welcomeAlready = false
    /// A sign-in made while the offer showed, waiting on the merge question: claims once it ends.
    private var mergeClaimsWelcome = false
    /// The `getWelcomeOffer` request on its way, which the ask before the sign-in sheet awaits.
    private var welcomeOfferTask: Task<Void, Never>?
    /// The account was deleted but the server said Apple's link stayed (`appleLinkRemoved:
    /// false`): a dialog says how to remove it, until dismissed.
    @Published var appleLinkLeft = false

    let backend: RecipeBackend
    let storage: RecipeCreditsStorage
    private let accountService = RecipeAccount()
    private var started = false
    private var updatesTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var pauseTask: Task<Void, Never>?
    static let pauseRetryInterval: Double = 5 * 60
    private var retryDelay: Duration = .seconds(15)
    private var submitting = Set<UInt64>()
    /// Purchases the server refused with STORE_MISMATCH this launch: not sent again on the
    /// backoff, only at the next launch or on "Try now".
    private var storeMismatched = Set<UInt64>()
    private var lastUID: String?
    private var authListener: AuthStateDidChangeListenerHandle?
    private let pathMonitor = NWPathMonitor()

    /// How long an Ask to Buy request is waited for before the callout goes away; a declined
    /// one never sends anything.
    static let pendingTimeout: TimeInterval = 7 * 24 * 60 * 60

    init(backend: RecipeBackend = FirebaseRecipeBackend(), storage: RecipeCreditsStorage = RecipeCreditsStorage()) {
        self.backend = backend
        self.storage = storage
        costs = storage.costs ?? .fallback
        serverTakesMenuDays = storage.serverTakesMenuDays
        unconfirmed = storage.unconfirmed
        paymentPending = Self.isPending(since: storage.paymentPendingSince, now: Date())
        welcomeOffer = storage.welcomeOffer
        welcomeClaimed = storage.welcomeClaimed
    }

    // MARK: What the screen shows

    /// Whether the paid route exists in this build: it can attest, and the store sells packs to
    /// it (or it already holds recipes). Otherwise only the free routes show.
    var isPaidRouteAvailable: Bool {
        guard FirebaseBootstrap.canAttest, !attestationFailed else { return false }
        #if DEBUG
        if demoMode { return true }
        #endif
        switch packs {
        case .loaded, .unreachable: return true
        case .idle, .loading, .notOffered: return balance != nil
        }
    }

    /// Buy is hidden while generation is paused, while a payment is on its way, so nobody
    /// pays twice, and for an account the server blocks from buying.
    var canBuy: Bool {
        isPaidRouteAvailable && !serverPaused && unconfirmedHere.isEmpty && !paymentPending && !purchasesBlocked
            && !storeMismatch
    }

    /// Paid for and not added yet, for this account: what the "being confirmed" callout counts.
    /// Not one this build can't add (`STORE_MISMATCH`): that one would never be confirmed, and
    /// its dialog says so.
    var unconfirmedHere: [UnconfirmedPurchase] {
        unconfirmed.filter { !$0.boughtByOtherAccount && !storeMismatched.contains($0.id) }
    }

    /// Purchases that belong to another account, which only signing in to it can add.
    var showsOtherAccountPurchases: Bool { unconfirmed.contains { $0.boughtByOtherAccount } }

    var otherAccountCredits: Int { unconfirmed.filter(\.boughtByOtherAccount).reduce(0) { $0 + $1.credits } }

    /// Where Buy would be, the line that says why it isn't.
    var showsPurchasesBlocked: Bool { isPaidRouteAvailable && purchasesBlocked }

    /// The first purchase asks where the recipes should live, once.
    var needsPurchaseChoice: Bool {
        account == .none && !storage.purchaseChoiceMade
    }

    var unconfirmedCredits: Int { unconfirmedHere.reduce(0) { $0 + $1.credits } }

    /// Deleting is held back while a purchase of this account is unconfirmed or its payment is
    /// still under way (not one bought by another account, which isn't this account's).
    var deletionWaitsForPurchase: Bool { !unconfirmedHere.isEmpty || paymentPending }

    var showsOffline: Bool { isPaidRouteAvailable && balance != nil && (connectionLost || !isOnline) }

    static func isPending(since: Date?, now: Date) -> Bool {
        guard let since else { return false }
        return now.timeIntervalSince(since) < pendingTimeout
    }

    // MARK: Start

    /// At launch: listen for transactions (Ask to Buy approvals, purchases made on another
    /// device's sheet) and resubmit any the server hasn't confirmed. Creates no user unless a
    /// transaction of ours is waiting.
    func start() {
        guard !started else { return }
        started = true
        if let app = FirebaseApp.app() {
            let auth = FirebaseBootstrap.auth(app: app)
            // Now, not when the listener first fires, so launch work sees the user at once.
            accountChanged(auth.currentUser)
            authListener = auth.addStateDidChangeListener { [weak self] _, user in
                MainActor.assumeIsolated { self?.accountChanged(user) }
            }
        }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            Task { @MainActor in
                guard let self else { return }
                #if DEBUG
                // A demo's "offline" stays, whatever the simulator's connection.
                if self.demoMode { return }
                #endif
                self.isOnline = online
                if online { self.connectionLost = false }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "com.foodtracker.recipe.path"))
        updatesTask = Task { [weak self] in
            for await result in StoreKit.Transaction.updates {
                await self?.handle(result)
            }
        }
        Task { await resubmitUnfinished() }
    }

    private func accountChanged(_ user: User?) {
        #if DEBUG
        if demoMode { return }
        #endif
        let previous = account.uid
        account = RecipeAccount.state(of: user)
        balance = account.uid.map { storage.cachedBalance(for: $0) ?? 0 }
        purchasesBlocked = account.uid.map { storage.cachedPurchasesBlocked(for: $0) } ?? false
        let uid = account.uid
        defer { lastUID = uid }
        // Another account now: a purchase that belonged to another one may be this one's.
        if started, uid != nil, uid != lastUID, unconfirmed.contains(where: \.boughtByOtherAccount) {
            Task { await resubmitUnfinished() }
        }
    }

    /// Asks the store for the packs, once per launch unless it failed.
    func loadPacksIfNeeded() async {
        switch packs {
        case .loaded, .loading, .notOffered: return
        case .idle, .unreachable: break
        }
        packs = .loading
        packs = await RecipeStoreKit.loadPacks()
    }

    func reloadPacks() async {
        packs = .loading
        packs = await RecipeStoreKit.loadPacks()
    }

    /// The count from the server, for an install that has a user. Never creates one.
    func refreshBalance() async {
        guard account.uid != nil, isPaidRouteAvailable else { return }
        #if DEBUG
        if demoMode { return }
        #endif
        do {
            let response = try await backend.balance()
            apply(response)
            connectionLost = false
        } catch {
            note(error)
        }
    }

    // MARK: Results of server calls

    /// A server answer's balance, whether it blocks buying (absent: no), and its costs when it
    /// carried readable ones.
    func apply(_ response: some BalanceCarrying) {
        let blocked = response.purchasesBlocked?.value ?? false
        balance = response.balance
        if purchasesBlocked != blocked { purchasesBlocked = blocked }
        if let uid = account.uid { storage.cache(balance: response.balance, purchasesBlocked: blocked, for: uid) }
        if let received = response.costs?.value {
            storage.costs = received
            if received != costs { costs = received }
        }
        // Absent from a server that predates the field, or after a rollback: then nothing extra goes.
        storage.serverTakesOptional = response.acceptsOptionalIngredients?.value ?? false
        storage.serverTakesMultiChoice = response.acceptsMultiChoiceConfig?.value ?? false
        storage.serverTakesDanish = response.acceptsDanish?.value ?? false
        setServerTakesMenuDays(response.acceptsMenuDays?.value ?? false)
        storage.serverTakesTaste = response.acceptsTaste?.value ?? false
        storage.serverTakesStaples = response.acceptsStaples?.value ?? false
        let quizVersion = response.tasteQuizVersion?.value
        if serverTasteQuizVersion != quizVersion { serverTasteQuizVersion = quizVersion }
        let welcome = WelcomeRecipes.accountRecipes(
            acceptsWelcome: response.acceptsWelcome?.value ?? false, welcomeRecipes: response.welcomeRecipes?.value
        )
        setWelcomeAccount(welcome)
    }

    /// Kept, and republished so the form's choices follow at once. Cleared too when a request
    /// that needed it was refused as invalid (a rolled-back server).
    func setServerTakesMenuDays(_ takes: Bool) {
        storage.serverTakesMenuDays = takes
        if serverTakesMenuDays != takes { serverTakesMenuDays = takes }
    }

    /// The server's last answer with a balance said `acceptsTaste`: the taste profile's row shows,
    /// the quiz can be fetched and answers are sent. Demo launches act as such a server.
    var serverTakesTaste: Bool {
        #if DEBUG
        if demoMode { return true }
        #endif
        return storage.serverTakesTaste
    }

    /// The cheapest pack as the store formats its price, once the store has answered (the tab
    /// asks it anyway, to know whether to offer packs); nil before.
    var lowestPackPrice: String? {
        guard case .loaded(let packs) = packs else { return nil }
        return packs.min { $0.product.price < $1.product.price }?.displayPrice
    }

    /// What a recipe, or a menu of `courses` courses a day for `days` days, takes.
    func cost(of kind: RecipeKind, courses: Int, days: Int = MenuSet.defaultDays) -> Int {
        costs.cost(of: kind, courses: courses, days: days)
    }

    func callSucceeded() {
        connectionLost = false
        serverPaused = false
    }

    /// Makes sure calls have a user to be made as. Only the purchase path and the generation
    /// of an install that already holds recipes get here.
    func ensureUser() async throws {
        try await accountService.ensureUser()
        account = accountService.state
    }

    /// Records what a failed server call says about the route: no connection, paused, closed
    /// account, or a build that can't attest.
    func note(_ error: Error) {
        switch error as? RecipeServerError {
        case .offline?: connectionLost = true
        case .generationDisabled?:
            serverPaused = true
            // Offered again after 5 minutes, as on Android; the next call says if it still is.
            pauseTask?.cancel()
            pauseTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(Self.pauseRetryInterval))
                guard !Task.isCancelled else { return }
                self?.serverPaused = false
            }
        case .accountClosed?:
            accountService.signOut()
            account = .none
            balance = nil
        case .unauthenticated?: attestationFailed = true
        case .storeMismatch?: storeMismatch = true
        default: break
        }
    }

    // MARK: Buying

    func chosePurchaseLocation() {
        storage.purchaseChoiceMade = true
    }

    /// Takes the person through the store's own sheet for `pack`, then has the server add it.
    /// `consumptionConsent`: the sheet showed the line about refunds and consumption.
    func buy(
        _ pack: RecipePack,
        consumptionConsent: Bool,
        using purchase: RecipePurchaseAction
    ) async -> RecipePurchaseOutcome {
        guard !isConfirming else { return .cancelled }
        isConfirming = true
        defer { isConfirming = false }
        // A user and a working App Check token before any money moves: a build that can't
        // attest would take payment it could never turn into recipes. This token only proves
        // App Check works and is never sent; `verifyPurchase` fetches its own.
        do {
            _ = try await AppCheck.appCheck().limitedUseToken()
        } catch {
            if RecipeAccount.isNetwork(error) { return .storeUnavailable }
            // Said as it is, not as a store problem: nothing was charged, the free ways still work.
            attestationFailed = true
            return .notAvailableHere(appCheck: true)
        }
        do {
            try await ensureUser()
            storage.purchaseChoiceMade = true
        } catch {
            if RecipeAccount.isNetwork(error) || (error as? RecipeServerError) == .offline {
                return .storeUnavailable
            }
            attestationFailed = true
            return .notAvailableHere(appCheck: false)
        }
        let result: Product.PurchaseResult
        do {
            // Ties the purchase to the user it is for; the server accepts purchases without it.
            let options: Set<Product.PurchaseOption> = account.uid
                .map { [.appAccountToken(RecipeWire.appAccountToken(forUID: $0))] } ?? []
            if consumptionConsent {
                storage.consentStarts[pack.product.id] = Date()
            }
            result = try await purchase(pack.product, options)
        } catch {
            return .storeUnavailable
        }
        switch result {
        case .success(let verification):
            return await handle(verification)
        case .pending:
            paymentPending = true
            storage.paymentPendingSince = Date()
            return .pending
        case .userCancelled:
            return .cancelled
        @unknown default:
            return .cancelled
        }
    }

    /// Sends one transaction to the server and finishes it only on the answers the contract
    /// says to: GRANTED, ALREADY_GRANTED, REJECTED_PERMANENT.
    @discardableResult
    private func handle(_ verification: VerificationResult<StoreKit.Transaction>) async -> RecipePurchaseOutcome {
        let transaction = verification.unsafePayloadValue
        guard RecipeStoreKit.isRecipePack(transaction) else { return .cancelled }
        // An approved Ask to Buy, or any purchase of ours, ends the wait.
        if paymentPending {
            paymentPending = false
            storage.paymentPendingSince = nil
        }
        guard submitting.insert(transaction.id).inserted else { return .notAddedYet }
        defer { submitting.remove(transaction.id) }
        let consent = RecipeStoreKit.consumptionConsent(
            record: unconfirmed.first { $0.id == transaction.id },
            consentStartedAt: storage.consentStarts[transaction.productID],
            purchaseDate: transaction.purchaseDate
        )
        remember(RecipeStoreKit.unconfirmed(transaction, consumptionConsent: consent))
        do {
            try await ensureUser()
            var response = try await verify(verification.jwsRepresentation, consumptionConsent: consent)
            if response == nil {
                // ACCOUNT_CLOSED: the user went away underneath us; start again as this install.
                try await ensureUser()
                response = try await verify(verification.jwsRepresentation, consumptionConsent: consent)
            }
            guard let response else { return notAddedYet() }
            callSucceeded()
            apply(response)
            guard response.status.finishesTransaction else { return notAddedYet() }
            await transaction.finish()
            forget(transaction.id)
            switch response.status {
            case .granted:
                notice = .added(RecipeStoreKit.unconfirmed(transaction).credits)
                return .added
            case .alreadyGranted:
                return .added
            case .rejectedPermanent:
                rejectedPurchase = true
                return .rejected
            case .retry:
                return notAddedYet()
            }
        } catch RecipeServerError.purchaseAccountMismatch {
            // Bought by another account: kept unfinished, so that account can still add it,
            // and not retried on the backoff. Sent again at launch and when the account changes.
            markBoughtByOtherAccount(transaction.id)
            return .notAddedYet
        } catch RecipeServerError.storeMismatch {
            // A build or setup bug that resending can't fix: not finished (that would lose
            // the purchase), not retried this launch, and no more buying.
            // It will never be confirmed from this build, so it leaves the "being confirmed"
            // callout (see `unconfirmedHere`) and a dialog says what happened instead.
            if storeMismatched.insert(transaction.id).inserted { rejectedByBuild = true }
            storeMismatch = true
            return .rejected
        } catch {
            note(error)
            return notAddedYet()
        }
    }

    private func markBoughtByOtherAccount(_ id: UInt64) {
        guard let index = unconfirmed.firstIndex(where: { $0.id == id }), !unconfirmed[index].boughtByOtherAccount else { return }
        unconfirmed[index].boughtByOtherAccount = true
        storage.unconfirmed = unconfirmed
    }

    /// Nil on ACCOUNT_CLOSED, after signing the closed account out.
    private func verify(_ jws: String, consumptionConsent: Bool) async throws -> VerifyPurchaseResponse? {
        do {
            return try await backend.verifyPurchase(signedTransaction: jws, consumptionConsent: consumptionConsent)
        } catch RecipeServerError.accountClosed {
            note(RecipeServerError.accountClosed)
            return nil
        }
    }

    private func notAddedYet() -> RecipePurchaseOutcome {
        scheduleRetry()
        return .notAddedYet
    }

    /// Whether the backoff still has a purchase to send: one this account is waiting for, not
    /// another account's and not one this build can't submit.
    private var hasRetryablePurchase: Bool {
        unconfirmed.contains { !$0.boughtByOtherAccount && !storeMismatched.contains($0.id) }
    }

    private func remember(_ purchase: UnconfirmedPurchase) {
        guard !unconfirmed.contains(where: { $0.id == purchase.id }) else { return }
        unconfirmed.append(purchase)
        storage.unconfirmed = unconfirmed
    }

    private func forget(_ id: UInt64) {
        unconfirmed.removeAll { $0.id == id }
        storage.unconfirmed = unconfirmed
        if !hasRetryablePurchase {
            retryTask?.cancel()
            retryTask = nil
            retryDelay = .seconds(15)
        }
    }

    /// Every unfinished transaction of ours, again: at launch, on "Try now", when the app comes
    /// back or the account changes, and on a backoff while any wait. Records for transactions
    /// StoreKit no longer has (finished elsewhere) are dropped. `waitingOnly` (the backoff, the
    /// app coming back): only those this account is waiting for, not another account's or one
    /// the server refused for this build.
    func resubmitUnfinished(waitingOnly: Bool = false) async {
        #if DEBUG
        if demoMode { return }
        #endif
        var stillUnfinished = Set<UInt64>()
        var pending: [VerificationResult<StoreKit.Transaction>] = []
        for await result in StoreKit.Transaction.unfinished {
            let transaction = result.unsafePayloadValue
            guard RecipeStoreKit.isRecipePack(transaction) else { continue }
            stillUnfinished.insert(transaction.id)
            pending.append(result)
        }
        for record in unconfirmed where !stillUnfinished.contains(record.id) {
            forget(record.id)
        }
        for result in pending {
            let id = result.unsafePayloadValue.id
            if waitingOnly, storeMismatched.contains(id) || unconfirmed.contains(where: { $0.id == id && $0.boughtByOtherAccount }) {
                continue
            }
            if !waitingOnly { storeMismatched.remove(id) }
            await handle(result)
        }
    }

    private func scheduleRetry() {
        guard retryTask == nil else { return }
        let delay = retryDelay
        retryDelay = min(retryDelay * 2, .seconds(15 * 60))
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self?.retryTask = nil
            await self?.resubmitUnfinished(waitingOnly: true)
        }
    }

    // MARK: Account

    /// Signs in from the purchase choice or Recipe settings. Returns whether the person ends up
    /// signed in; an account that already has recipes waits on `pendingMerge`.
    /// Any sign-in made while the offer of free recipes shows claims them once it succeeds
    /// (`WelcomeRecipes.afterSignIn`). `quietCancel`: a cancelled sign-in says nothing (the
    /// sign-in sheet of the offer); otherwise it is "Couldn't sign in", as before.
    @discardableResult
    /// `quietClaim`: the first purchase's account choice; the claim runs beside the purchase and
    /// only a grant is said.
    /// `claims`: whether it claims, when the caller decides (the sign-in sheet, by the number it
    /// opened with: a plain sheet never claims); nil: by the offer showing now.
    func signIn(quietCancel: Bool = false, quietClaim: Bool = false, claims: Bool? = nil, _ obtain: () async throws -> RecipeSignInCredential) async -> Bool {
        let offerShowing = claims ?? (welcomeHint?.needsSignIn == true)
        isSigningIn = true
        defer { isSigningIn = false }
        do {
            let credential = try await obtain()
            let outcome = try await accountService.signIn(with: credential, installBalance: account.isSignedIn ? 0 : (balance ?? 0))
            account = accountService.state
            switch outcome {
            case .signedIn(let merged):
                // An install with nothing to ask about was merged on the way (the debt of a
                // negative balance follows the person); its answer has the account's balance.
                if let merged {
                    apply(merged)
                    if merged.moved != 0 { notice = .merged(moved: merged.moved) }
                }
                // A link was followed at once by one `getBalance` (see `RecipeAccount`): its answer.
                let linkBalance = accountService.takeLinkBalance()
                if let linkBalance { apply(linkBalance) }
                callSucceeded()
                registerAppleSignIn(credential.appleAuthorizationCode)
                if offerShowing {
                    // On the purchase path (`quietClaim`) the claim finishes before the purchase
                    // starts, so its balance can never land after the purchase's and hide it.
                    await claimAfterSignIn(quiet: quietClaim, balanceKnown: linkBalance != nil || merged != nil)
                } else if linkBalance == nil {
                    await refreshBalance()
                }
                return true
            case .needsMerge(var merge):
                merge.appleAuthorizationCode = credential.appleAuthorizationCode
                mergeClaimsWelcome = offerShowing
                pendingMerge = merge
                return false
            }
        } catch RecipeAccountError.cancelled {
            if !quietCancel { notice = .signInFailed }
            return false
        } catch RecipeAccountError.providerInUse {
            notice = .providerInUse
            return false
        } catch {
            note(error)
            notice = Self.accountNotice(for: error, otherwise: .signInFailed)
            return false
        }
    }

    func confirmMerge() async {
        guard let merge = pendingMerge else { return }
        pendingMerge = nil
        isSigningIn = true
        defer { isSigningIn = false }
        do {
            let response = try await accountService.completeMerge(merge)
            account = accountService.state
            apply(response)
            if response.moved != 0 { notice = .merged(moved: response.moved) }
            callSucceeded()
            registerAppleSignIn(merge.appleAuthorizationCode)
            if mergeClaimsWelcome {
                mergeClaimsWelcome = false
                await claimAfterSignIn(balanceKnown: true)
            }
        } catch {
            mergeClaimsWelcome = false
            await accountService.cancelMerge()
            note(error)
            notice = Self.accountNotice(for: error, otherwise: .signInFailed)
        }
    }

    func cancelMerge() async {
        pendingMerge = nil
        mergeClaimsWelcome = false
        await accountService.cancelMerge()
    }

    func signOut() {
        accountService.signOut()
        account = .none
        balance = nil
    }

    // MARK: Free recipes for signing in

    /// How the offer comes and goes on screen when an answer arrives or changes: the row grows
    /// while it fades in (the views pick a plain cross-fade under Reduce Motion), about 280 ms.
    static var welcomeMotion: Animation {
        UIAccessibility.isReduceMotionEnabled ? .easeInOut(duration: 0.2) : .easeInOut(duration: 0.28)
    }

    private var welcomeAccount: WelcomeAccount {
        switch account {
        case .none: return .none
        case .anonymous: return .device
        case .signedIn: return .signedIn
        }
    }

    /// The account signed in now has claimed on this phone (GRANTED or ALREADY).
    var welcomeClaimedHere: Bool {
        guard case .signedIn(let uid, _, _) = account else { return false }
        return welcomeClaimed.contains(uid)
    }

    /// The offer as it shows now, with the server's number; nil shows nothing anywhere.
    var welcomeHint: WelcomeHint? { WelcomeRecipes.hint(welcomeSituation) }

    /// The store has actually offered packs (not unreachable, not restricted, not selling none).
    private var packsOffered: Bool {
        #if DEBUG
        if demoMode { return true }
        #endif
        if case .loaded(let offered) = packs { return !offered.isEmpty }
        return false
    }

    private var welcomeSituation: WelcomeSituation {
        WelcomeSituation(
            paidRouteAvailable: isPaidRouteAvailable,
            packsOffered: packsOffered,
            buyingPaused: serverPaused,
            // A build the server takes no App Store purchases from (STORE_MISMATCH) hides it too, as Buy.
            purchasesBlocked: purchasesBlocked || storeMismatch,
            account: welcomeAccount,
            claimed: welcomeClaimedHere,
            offer: WelcomeRecipes.fresh(welcomeOffer, now: Date()),
            accountRecipes: WelcomeRecipes.fresh(welcomeAccountAnswer, now: Date())
        )
    }

    /// Asks `getWelcomeOffer` when the Recipe tab is shown (at most every 15 minutes) and right
    /// before the sign-in sheet opens (`beforeSignIn`). Only where the paid route can be available
    /// and while signed out or not claimed; sends nothing about the person and creates no account
    /// (the callable needs App Check only). A failure is silent and keeps the last answer.
    func refreshWelcomeOffer(beforeSignIn: Bool = false) async {
        #if DEBUG
        if demoMode { return }
        #endif
        // One already on its way: the ask before the sign-in sheet waits for its answer (within
        // the sheet's 4 s) rather than opening with an older number.
        if let running = welcomeOfferTask {
            if beforeSignIn { await running.value }
            return
        }
        let now = Date()
        guard WelcomeRecipes.offerAskable(welcomeSituation),
              WelcomeRecipes.shouldAsk(lastAsked: storage.welcomeOfferAskedAt, answer: welcomeOffer, now: now, beforeSignIn: beforeSignIn) else { return }
        storage.welcomeOfferAskedAt = now
        let task = Task { await self.askWelcomeOffer() }
        welcomeOfferTask = task
        await task.value
        welcomeOfferTask = nil
    }

    private func askWelcomeOffer() async {
        let answer: WelcomeOfferAnswer?
        do {
            let response = try await backend.welcomeOffer()
            answer = WelcomeOfferAnswer(recipes: response.recipes, receivedAt: Date())
        } catch {
            answer = WelcomeRecipes.answer(afterFailure: error as? RecipeServerError ?? .server, cached: welcomeOffer, now: Date())
        }
        storage.welcomeOffer = answer
        setWelcomeOffer(answer)
    }

    /// The account's number from an answer with a balance, kept with its time for this uid; the
    /// offer moves (animated) only when what it shows changes.
    private func setWelcomeAccount(_ recipes: Int) {
        guard let uid = account.uid else { return }
        let answer = WelcomeOfferAnswer(recipes: recipes, receivedAt: Date())
        #if DEBUG
        if !demoMode { storage.cacheWelcomeAccount(answer, for: uid) }
        #else
        storage.cacheWelcomeAccount(answer, for: uid)
        #endif
        if WelcomeRecipes.fresh(welcomeAccountAnswer, now: Date()) == recipes {
            welcomeAccountAnswer = answer
        } else {
            withAnimation(Self.welcomeMotion) { welcomeAccountAnswer = answer }
        }
    }

    private func setWelcomeOffer(_ answer: WelcomeOfferAnswer?) {
        guard answer != welcomeOffer else { return }
        let before = WelcomeRecipes.fresh(welcomeOffer, now: Date())
        let after = WelcomeRecipes.fresh(answer, now: Date())
        if before == after {
            welcomeOffer = answer
        } else {
            withAnimation(Self.welcomeMotion) { welcomeOffer = answer }
        }
    }

    /// After a sign-in made while the offer showed: claim once, or, for an account that claimed on
    /// this phone before, say so without calling (the contract never calls again after GRANTED or
    /// ALREADY).
    /// `balanceKnown`: the sign-in already applied a balance (a link's `getBalance`, a merge).
    /// Otherwise, and whenever the claim brought none (not reached, an old server), the balance is
    /// asked as after any sign-in, so an account's recipes show at once.
    private func claimAfterSignIn(quiet: Bool = false, balanceKnown: Bool = false) async {
        guard let uid = account.uid else { return }
        switch WelcomeRecipes.afterSignIn(offerShowing: true, claimedHere: welcomeClaimed.contains(uid)) {
        case .claim:
            let broughtBalance = await claimWelcome(justSignedIn: true, quiet: quiet)
            if !broughtBalance && !balanceKnown { await refreshBalance() }
        case .alreadyClaimed:
            if !quiet { welcomeAlready = true }
            if !balanceKnown { await refreshBalance() }
        case .nothing:
            if !balanceKnown { await refreshBalance() }
        }
    }

    /// `claimWelcome` for the account signed in now: "Get 3 free recipes" (one tap), Try again
    /// after a claim that didn't reach our server, and every sign-in made while the offer showed.
    /// Safe to repeat: the server grants once per Apple or Google account.
    /// `quiet`: a sign-in from the first purchase's account choice, while the store's payment
    /// sheet follows: only a grant is said (no dialog over the payment), as on Android.
    /// Returns whether its answer carried a balance (and so was applied).
    @discardableResult
    func claimWelcome(justSignedIn: Bool = false, quiet: Bool = false) async -> Bool {
        guard !isClaimingWelcome, case .signedIn(let uid, _, _) = account else { return false }
        isClaimingWelcome = true
        defer { isClaimingWelcome = false }
        let outcome: WelcomeClaimOutcome
        var broughtBalance = false
        #if DEBUG
        if demoMode {
            try? await Task.sleep(for: .seconds(1.5))
            applyDemoClaim()
            finishClaim(demoClaimOutcome, uid: uid, justSignedIn: justSignedIn, quiet: quiet)
            return true
        }
        #endif
        do {
            let response = try await backend.claimWelcome()
            callSucceeded()
            apply(response)
            broughtBalance = true
            outcome = WelcomeRecipes.outcome(of: response)
        } catch {
            note(error)
            // A server without `claimWelcome`: no grant there.
            if (error as? RecipeServerError) == .callableMissing { setWelcomeAccount(0) }
            outcome = WelcomeRecipes.outcome(of: error as? RecipeServerError ?? .server)
        }
        finishClaim(outcome, uid: uid, justSignedIn: justSignedIn, quiet: quiet)
        return broughtBalance
    }

    private func finishClaim(_ outcome: WelcomeClaimOutcome, uid: String, justSignedIn: Bool, quiet: Bool = false) {
        if WelcomeRecipes.claimSettled(outcome) {
            withAnimation(Self.welcomeMotion) { welcomeClaimed.insert(uid) }
            #if DEBUG
            if !demoMode { storage.welcomeClaimed = welcomeClaimed }
            #else
            storage.welcomeClaimed = welcomeClaimed
            #endif
        }
        if WelcomeRecipes.announces(outcome, quiet: quiet) { showClaimOutcome(outcome, justSignedIn: justSignedIn) }
    }

    private func showClaimOutcome(_ outcome: WelcomeClaimOutcome, justSignedIn: Bool) {
        switch outcome {
        case .granted(let count): notice = .welcomeGranted(count)
        case .already: welcomeAlready = true
        case .off: notice = .welcomeOff(justSignedIn: justSignedIn)
        case .notReached: notice = .welcomeNotReached
        }
    }

    #if DEBUG
    /// `-demoRecipeCredits <n>` with `-demoRecipeState <state>` (phone, account, unconfirmed,
    /// pending, paused, offline, blocked, otherAccount, busy): the paid route's screens without a server or a store, for
    /// screenshots and layout checks. Nothing is sent anywhere.
    /// `menuSets` (unless `-demoRecipeMenuSets NO`): as from a server that says
    /// `acceptsMenuDays`, with the server's default menu-set prices; kept for this run only.
    func showDemo(balance: Int, state: String?, menuSets: Bool = true) {
        demoMode = true
        self.balance = balance
        if menuSets {
            serverTakesMenuDays = true
            costs = RecipeCosts(recipe: 1, menu2: 4, menu3: 5, menuSets: [
                "1": ["1": 1, "2": 2, "3": 3], "2": ["1": 2, "2": 3, "3": 4],
                "3": ["1": 3, "2": 4, "3": 5], "4": ["1": 4, "2": 5, "3": 6],
            ])
        } else {
            serverTakesMenuDays = false
            costs = .fallback
        }
        switch state {
        case "account": account = .signedIn(uid: "demo", providers: [.apple], email: "k7x2m9@privaterelay.appleid.com")
        // No account at all, as anyone who never bought or signed in.
        case "none":
            account = .none
            self.balance = nil
        default: account = .anonymous(uid: "demo")
        }
        switch state {
        case "unconfirmed": unconfirmed = [UnconfirmedPurchase(transactionID: 1, productID: "recipes_50", credits: 50)]
        case "pending": paymentPending = true
        case "paused": serverPaused = true
        case "offline": connectionLost = true
        case "blocked": purchasesBlocked = true
        case "otherAccount":
            unconfirmed = [UnconfirmedPurchase(transactionID: 1, productID: "recipes_50", credits: 50, boughtByOtherAccount: true)]
        default: break
        }
    }

    private(set) var demoMode = false
    /// What a claim answers in a demo (`-demoWelcomeOutcome`); GRANTED with the demo's number
    /// unless told otherwise.
    private var demoClaimOutcome: WelcomeClaimOutcome = .granted(3)

    /// `-demoWelcomeOffer <n>` (with `-demoRecipeCredits`): the offer as from our server, nothing
    /// asked or stored. `delay` > 0 (`-demoWelcomeDelay <s>`): it arrives that long after launch,
    /// with the motion. Signed in, `n` is also what the balance carries. `claimed`
    /// (`-demoWelcomeClaimed YES`): this account claimed here. `claiming` (`-demoWelcomeClaiming
    /// YES`): a claim stays on its way. `outcome` (`-demoWelcomeOutcome granted|already|off|
    /// notReached`): what a claim answers, and, with `showOutcome`, delivered once 1.5 s after
    /// launch as if a sign-in's claim had just finished.
    func showWelcomeDemo(offer: Int?, delay: Double, claimed: Bool, claiming: Bool, outcome: String?, showOutcome: Bool, outcomeDelay: Double = 1.5) {
        let recipes = offer ?? 0
        switch outcome {
        case "already": demoClaimOutcome = .already
        case "off": demoClaimOutcome = .off
        case "notReached": demoClaimOutcome = .notReached
        default: demoClaimOutcome = .granted(recipes > 0 ? recipes : 3)
        }
        if claimed, let uid = account.uid { welcomeClaimed.insert(uid) }
        isClaimingWelcome = claiming
        let deliver = { [weak self] in
            guard let self, let offer else { return }
            self.welcomeOffer = WelcomeOfferAnswer(recipes: offer, receivedAt: Date())
            if case .signedIn = self.account { self.welcomeAccountAnswer = WelcomeOfferAnswer(recipes: offer, receivedAt: Date()) }
        }
        if delay > 0 {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(delay))
                withAnimation(Self.welcomeMotion) { deliver() }
            }
        } else {
            deliver()
        }
        if showOutcome {
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(outcomeDelay))
                guard let self else { return }
                if self.account.uid == nil || self.account == .anonymous(uid: "demo") {
                    self.account = .signedIn(uid: "demo", providers: [.apple], email: "k7x2m9@privaterelay.appleid.com")
                    self.balance = self.balance ?? 0
                }
                self.applyDemoClaim()
                self.finishClaim(self.demoClaimOutcome, uid: "demo", justSignedIn: true)
            }
        }
    }

    /// What the demo's claim does to the count and the account's number, as the server's answer would.
    private func applyDemoClaim() {
        switch demoClaimOutcome {
        case .granted(let count): balance = (balance ?? 0) + count
        case .off: withAnimation(Self.welcomeMotion) { welcomeAccountAnswer = WelcomeOfferAnswer(recipes: 0, receivedAt: Date()) }
        case .already, .notReached: break
        }
    }

    #endif

    /// Deletes the account from our server: a signed-in one after a fresh sign-in with its own
    /// provider, or the anonymous one that keeps this phone's recipes (no sign-in needed). Only
    /// when there is one: someone who never started a purchase has nothing on the server, and no
    /// user is made to delete.
    func deleteAccount() async {
        guard account.uid != nil, !isSigningIn else { return }
        // Whatever comes of it, no rating request follows in this run.
        Self.noteReviewProblem()
        // A purchase paid for but not added yet is tied to this account on the server
        // (PURCHASE_ACCOUNT_MISMATCH for anyone else): deleting now would strand it.
        if deletionWaitsForPurchase {
            notice = .deletionWaitsForPurchase
            return
        }
        isSigningIn = true
        defer { isSigningIn = false }
        if case .anonymous = account {
            await deleteDeviceData()
            return
        }
        do {
            let credential: RecipeSignInCredential
            if account.providers.contains(.apple) {
                credential = try await AppleSignInRequest().perform()
            } else {
                credential = try await GoogleSignInSupport.signIn()
            }
            let response = try await accountService.deleteAccount(with: credential, backend: backend)
            account = .none
            balance = nil
            notice = .accountDeleted
            // Rare: the server couldn't revoke Apple's token with the fresh code nor the stored
            // one. Only then is the person told how to remove the link in Apple's settings.
            appleLinkLeft = response.leftAppleLink
        } catch RecipeAccountError.cancelled {
            return
        } catch {
            note(error)
            notice = Self.accountNotice(for: error, otherwise: .accountActionFailed)
        }
    }

    /// The anonymous account's deletion. As `mergeBalance`'s source, it needs no recent sign-in.
    /// Afterwards the phone is as if it had never bought: no user, no count, no held generation
    /// (its record went with the account), and the next purchase asks again where to keep them.
    private func deleteDeviceData() async {
        do {
            _ = try await backend.deleteAccount(appleAuthorizationCode: nil)
        } catch {
            note(error)
            // Deleted already (from here, with the answer lost): `note` signed out.
            if (error as? RecipeServerError) != .accountClosed {
                notice = Self.accountNotice(for: error, otherwise: .accountActionFailed)
                return
            }
        }
        accountService.signOut()
        account = .none
        balance = nil
        storage.pendingGeneration = nil
        storage.purchaseChoiceMade = false
        notice = .deviceDataDeleted
    }

    /// Hands an Apple sign-in's code to the server in the background, so Apple's link can be
    /// removed when the account is deleted, from this phone or from Android. Quiet: the sign-in
    /// has already succeeded, a failure (an expired or used code, no connection) shows nothing,
    /// and the next Apple sign-in tries again. Nothing is logged. A Google sign-in has no code.
    private func registerAppleSignIn(_ code: String?) {
        guard let code, !code.isEmpty, account.providers.contains(.apple) else { return }
        let backend = backend
        Task { try? await backend.registerAppleSignIn(authorizationCode: code) }
    }

    /// What an account call's failure tells the person: a generation in flight has its own
    /// message, anything else `otherwise`.
    static func accountNotice(for error: Error, otherwise: RecipeCreditsNotice) -> RecipeCreditsNotice {
        (error as? RecipeServerError) == .busy ? .accountBusy : otherwise
    }
}
