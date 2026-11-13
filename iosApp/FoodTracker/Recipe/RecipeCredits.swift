import Combine
import FirebaseAppCheck
import FirebaseAuth
import FirebaseCore
import Foundation
import Network
import StoreKit

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
    /// The server refused it for good: refunded, or already used.
    case rejected
}

/// The one-off messages the paid route raises, shown as snackbars by the screen on top.
enum RecipeCreditsNotice: Equatable {
    case added(Int)
    case signInFailed
    case providerInUse
    case accountDeleted
    case accountActionFailed
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

    func cache(balance: Int, for uid: String) {
        defaults.set(uid, forKey: Key.balanceUID)
        defaults.set(balance, forKey: Key.balance)
    }

    /// The generation a lost connection, a server error or a long IN_PROGRESS interrupted,
    /// retried under the same id. Dropped after 24 hours, past which the server has settled it.
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

    static let pendingGenerationLifetime: TimeInterval = 24 * 60 * 60

    var menuCourses: Int {
        get { defaults.integer(forKey: Key.courses) == 3 ? 3 : 2 }
        nonmutating set { defaults.set(newValue == 3 ? 3 : 2, forKey: Key.courses) }
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
    /// Nil while there is no user, which is the state of anyone who never bought.
    @Published private(set) var balance: Int?
    @Published private(set) var account: RecipeAccountState = .none
    @Published private(set) var packs: RecipePacksState = .idle
    @Published private(set) var unconfirmed: [UnconfirmedPurchase] = []
    @Published private(set) var paymentPending = false
    @Published private(set) var serverPaused = false
    @Published private(set) var attestationFailed = false
    /// A server call failed for want of a connection, or the phone has none.
    @Published private(set) var connectionLost = false
    @Published private(set) var isOnline = true
    @Published private(set) var isConfirming = false
    @Published private(set) var isSigningIn = false
    @Published var notice: RecipeCreditsNotice?
    /// "This purchase couldn't be added", a T6 dialog.
    @Published var rejectedPurchase = false
    /// "Add this phone's recipes to your account?"
    @Published var pendingMerge: RecipePendingMerge?

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
    private var authListener: AuthStateDidChangeListenerHandle?
    private let pathMonitor = NWPathMonitor()

    /// How long an Ask to Buy request is waited for before the callout goes away; a declined
    /// one never sends anything.
    static let pendingTimeout: TimeInterval = 7 * 24 * 60 * 60

    init(backend: RecipeBackend = FirebaseRecipeBackend(), storage: RecipeCreditsStorage = RecipeCreditsStorage()) {
        self.backend = backend
        self.storage = storage
        unconfirmed = storage.unconfirmed
        paymentPending = Self.isPending(since: storage.paymentPendingSince, now: Date())
    }

    // MARK: What the screen shows

    /// Whether the paid route exists in this build: it can attest, and the store sells packs to
    /// it (or it already holds recipes). Otherwise only the free routes show.
    var isPaidRouteAvailable: Bool {
        guard FirebaseBootstrap.canAttest, !attestationFailed else { return false }
        switch packs {
        case .loaded, .unreachable: return true
        case .idle, .loading, .notOffered: return balance != nil
        }
    }

    /// Buy is hidden while generation is paused, and while a payment is on its way, so nobody
    /// pays twice.
    var canBuy: Bool {
        isPaidRouteAvailable && !serverPaused && unconfirmed.isEmpty && !paymentPending
    }

    /// The first purchase asks where the recipes should live, once.
    var needsPurchaseChoice: Bool {
        account == .none && !storage.purchaseChoiceMade
    }

    var unconfirmedCredits: Int { unconfirmed.reduce(0) { $0 + $1.credits } }

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
                self.isOnline = online
                if online { self.connectionLost = false }
            }
        }
        pathMonitor.start(queue: DispatchQueue(label: "com.foodtracker.recipe.path"))
        updatesTask = Task { [weak self] in
            for await result in Transaction.updates {
                await self?.handle(result)
            }
        }
        Task { await resubmitUnfinished() }
    }

    private func accountChanged(_ user: User?) {
        #if DEBUG
        if demoMode { return }
        #endif
        account = RecipeAccount.state(of: user)
        balance = account.uid.map { storage.cachedBalance(for: $0) ?? 0 }
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
            apply(balance: try await backend.balance())
            connectionLost = false
        } catch {
            note(error)
        }
    }

    // MARK: Results of server calls

    func apply(balance newBalance: Int) {
        balance = newBalance
        if let uid = account.uid { storage.cache(balance: newBalance, for: uid) }
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
        default: break
        }
    }

    // MARK: Buying

    func chosePurchaseLocation() {
        storage.purchaseChoiceMade = true
    }

    /// Takes the person through the store's own sheet for `pack`, then has the server add it.
    func buy(_ pack: RecipePack, using purchase: RecipePurchaseAction) async -> RecipePurchaseOutcome {
        guard !isConfirming else { return .cancelled }
        isConfirming = true
        defer { isConfirming = false }
        // A user and a working App Check token before any money moves: a build that can't
        // attest would take payment it could never turn into recipes.
        do {
            _ = try await AppCheck.appCheck().limitedUseToken()
            try await ensureUser()
            storage.purchaseChoiceMade = true
        } catch {
            if RecipeAccount.isNetwork(error) || (error as? RecipeServerError) == .offline {
                return .storeUnavailable
            }
            attestationFailed = true
            return .storeUnavailable
        }
        let result: Product.PurchaseResult
        do {
            result = try await purchase(pack.product)
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
    private func handle(_ verification: VerificationResult<Transaction>) async -> RecipePurchaseOutcome {
        let transaction = verification.unsafePayloadValue
        guard RecipeStoreKit.isRecipePack(transaction) else { return .cancelled }
        // An approved Ask to Buy, or any purchase of ours, ends the wait.
        if paymentPending {
            paymentPending = false
            storage.paymentPendingSince = nil
        }
        guard submitting.insert(transaction.id).inserted else { return .notAddedYet }
        defer { submitting.remove(transaction.id) }
        remember(RecipeStoreKit.unconfirmed(transaction))
        do {
            try await ensureUser()
            var response = try await verify(verification.jwsRepresentation)
            if response == nil {
                // ACCOUNT_CLOSED: the user went away underneath us; start again as this install.
                try await ensureUser()
                response = try await verify(verification.jwsRepresentation)
            }
            guard let response else { return notAddedYet() }
            callSucceeded()
            apply(balance: response.balance)
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
        } catch {
            note(error)
            return notAddedYet()
        }
    }

    /// Nil on ACCOUNT_CLOSED, after signing the closed account out.
    private func verify(_ jws: String) async throws -> VerifyPurchaseResponse? {
        do {
            return try await backend.verifyPurchase(signedTransaction: jws)
        } catch RecipeServerError.accountClosed {
            note(RecipeServerError.accountClosed)
            return nil
        }
    }

    private func notAddedYet() -> RecipePurchaseOutcome {
        scheduleRetry()
        return .notAddedYet
    }

    private func remember(_ purchase: UnconfirmedPurchase) {
        guard !unconfirmed.contains(where: { $0.id == purchase.id }) else { return }
        unconfirmed.append(purchase)
        storage.unconfirmed = unconfirmed
    }

    private func forget(_ id: UInt64) {
        unconfirmed.removeAll { $0.id == id }
        storage.unconfirmed = unconfirmed
        if unconfirmed.isEmpty {
            retryTask?.cancel()
            retryTask = nil
            retryDelay = .seconds(15)
        }
    }

    /// Every unfinished transaction of ours, again: at launch, on "Try now", when the app comes
    /// back, and on a backoff while any wait. Records for transactions StoreKit no longer has
    /// (finished elsewhere) are dropped.
    func resubmitUnfinished() async {
        #if DEBUG
        if demoMode { return }
        #endif
        var stillUnfinished = Set<UInt64>()
        var pending: [VerificationResult<Transaction>] = []
        for await result in Transaction.unfinished {
            let transaction = result.unsafePayloadValue
            guard RecipeStoreKit.isRecipePack(transaction) else { continue }
            stillUnfinished.insert(transaction.id)
            pending.append(result)
        }
        for record in unconfirmed where !stillUnfinished.contains(record.id) {
            forget(record.id)
        }
        for result in pending {
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
            await self?.resubmitUnfinished()
        }
    }

    // MARK: Account

    /// Signs in from the purchase choice or Recipe settings. Returns whether the person ends up
    /// signed in; an account that already has recipes waits on `pendingMerge`.
    @discardableResult
    func signIn(_ obtain: () async throws -> RecipeSignInCredential) async -> Bool {
        isSigningIn = true
        defer { isSigningIn = false }
        do {
            let credential = try await obtain()
            let outcome = try await accountService.signIn(with: credential, installBalance: account.isSignedIn ? 0 : (balance ?? 0))
            account = accountService.state
            switch outcome {
            case .signedIn:
                callSucceeded()
                await refreshBalance()
                return true
            case .needsMerge(let merge):
                pendingMerge = merge
                return false
            }
        } catch RecipeAccountError.cancelled {
            notice = .signInFailed
            return false
        } catch RecipeAccountError.providerInUse {
            notice = .providerInUse
            return false
        } catch {
            note(error)
            notice = .signInFailed
            return false
        }
    }

    func confirmMerge() async {
        guard let merge = pendingMerge else { return }
        pendingMerge = nil
        isSigningIn = true
        defer { isSigningIn = false }
        do {
            let newBalance = try await accountService.completeMerge(merge)
            account = accountService.state
            apply(balance: newBalance)
            callSucceeded()
        } catch {
            await accountService.cancelMerge()
            note(error)
            notice = .signInFailed
        }
    }

    func cancelMerge() async {
        pendingMerge = nil
        await accountService.cancelMerge()
    }

    func signOut() {
        accountService.signOut()
        account = .none
        balance = nil
    }

    #if DEBUG
    /// `-demoRecipeCredits <n>` with `-demoRecipeState <state>` (phone, account, unconfirmed,
    /// pending, paused, offline): the paid route's screens without a server or a store, for
    /// screenshots and layout checks. Nothing is sent anywhere.
    func showDemo(balance: Int, state: String?) {
        demoMode = true
        self.balance = balance
        account = state == "account"
            ? .signedIn(uid: "demo", providers: [.apple], email: "k7x2m9@privaterelay.appleid.com")
            : .anonymous(uid: "demo")
        switch state {
        case "unconfirmed": unconfirmed = [UnconfirmedPurchase(transactionID: 1, productID: "recipes_50", credits: 50)]
        case "pending": paymentPending = true
        case "paused": serverPaused = true
        case "offline": connectionLost = true
        default: break
        }
    }

    private(set) var demoMode = false
    #endif

    /// A fresh sign-in with the account's own provider, then the deletion on the server.
    func deleteAccount() async {
        guard account.isSignedIn else { return }
        isSigningIn = true
        defer { isSigningIn = false }
        do {
            let credential: RecipeSignInCredential
            if account.providers.contains(.apple) {
                credential = try await AppleSignInRequest().perform()
            } else {
                credential = try await GoogleSignInSupport.signIn()
            }
            try await accountService.deleteAccount(with: credential, backend: backend)
            account = .none
            balance = nil
            notice = .accountDeleted
        } catch RecipeAccountError.cancelled {
            return
        } catch {
            note(error)
            notice = .accountActionFailed
        }
    }
}
