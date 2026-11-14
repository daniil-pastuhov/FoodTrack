import Combine
import Foundation
import SwiftUI

/// The free routes the Recipe tab offers right now, worked out from what this phone has. The
/// server route's availability is `RecipeCredits.isPaidRouteAvailable`.
struct RecipeRoutes: Equatable {
    /// The phone's own model can write in the chosen language.
    var onDevice: Bool
    /// The phone has a model that is still downloading: the "getting ready" callout.
    var onDevicePreparing: Bool

    static func from(_ availability: OnDeviceRecipeAvailability) -> RecipeRoutes {
        RecipeRoutes(onDevice: availability == .available, onDevicePreparing: availability == .preparing)
    }
}

/// What the tab is doing.
enum RecipePhase: Equatable {
    case idle
    case writingOnDevice
    case writingOnServer(RecipeKind)
}

/// A single recipe, or the three-day menu that only the server writes.
enum RecipeMode: Hashable {
    case recipe, menu
}

/// What just happened, for one snackbar.
enum RecipeFailure: Equatable {
    case onDeviceFailed
    case onDeviceRefused
    /// Failed, nothing taken. `sameRequest` retries under the same id (a server error), else a
    /// new attempt (the generation itself failed).
    case serverFailed(RecipeKind, sameRequest: Bool)
    /// Failed and counted: a failure caused by the request itself, once the account is past its
    /// allowance of given-back failures (one for every ten recipes bought). `taken` is what that
    /// kind of write takes.
    case serverRefused(RecipeKind, taken: Int)
    /// The server refused for a reason this version doesn't know. `taken` is 0 when nothing was
    /// taken.
    case serverUnrecognised(RecipeKind, taken: Int)
    case busy
    /// Too many in a minute.
    case rateLimited
    /// This account's limit for the day. Nothing taken; tomorrow (UTC).
    case userDailyLimit
    /// Three technical failures today, each given back. Nothing taken; tomorrow (UTC).
    case infraFailureLimit
    /// The whole service's limit for the day.
    case dailyCap
    /// A recipe or menu arrived; the count after it.
    case arrived(balance: Int)
    /// A request held from before the form changed finished after all (the server doesn't stop
    /// an abandoned one, and charges it) and was saved.
    case earlierArrived(RecipeKind)
    /// That earlier request failed, and the failure was counted (`taken` is what it cost).
    case earlierRefused(taken: Int)
    /// The server refused this ticked item's name; the request went on without it.
    case itemLeftOut(String)
    /// The server refused this ticked item's name and it couldn't be left out (the only one, or
    /// leaving one out was already tried). Nothing was written or taken.
    case itemNotSent(String)
    /// An allergen that can't be sent: nothing was written, since a recipe without it could be
    /// unsafe.
    case allergenRefused(String)
    /// More allergens are set than the server takes in one request (`limit`). Nothing was
    /// written: none is ever left out.
    case tooManyAllergens(limit: Int)
    /// Allergens are set and the person refused, or withdrew, sending them to our server.
    /// Nothing was sent; Recipe settings turns it back on, and the free routes still work.
    case allergenConsentDeclined
}

extension RecipeFailure {
    /// Every snackbar but the two that bring a recipe rules the rating prompt out for the
    /// session. As Android's `ReviewPrompter.onRecipeEvent`.
    var blocksReviewPrompt: Bool {
        switch self {
        case .arrived, .earlierArrived: return false
        default: return true
        }
    }
}

/// A way to get a recipe from the form, each its own button: a tap on one never falls back to
/// another, so a free route never spends a recipe. Android: `RecipeAction`.
enum RecipeAction: Equatable {
    case server, onDevice, share, copy, buy
}

/// What the form offers: `primary` is pinned above the pill (nil: nothing pinned), `secondary`
/// sits under the form in this order. Android: `RecipeActions` (`RecipeUiState.actions`).
struct RecipeActions: Equatable {
    var primary: RecipeAction?
    var secondary: [RecipeAction]

    /// With recipes in the pack the server is pinned and both free ways stay one tap away; without
    /// (or offline), the phone's model or the shared prompt is pinned. A menu is the server's only.
    static func of(isMenu: Bool, serverCanWrite: Bool, offline: Bool, onDevice: Bool, canBuy: Bool) -> RecipeActions {
        if isMenu { return RecipeActions(primary: serverCanWrite ? .server : nil, secondary: []) }
        let primary: RecipeAction = serverCanWrite && !offline ? .server : onDevice ? .onDevice : .share
        var secondary: [RecipeAction] = []
        if onDevice && primary != .onDevice { secondary.append(.onDevice) }
        if primary != .share { secondary.append(.share) }
        secondary.append(.copy)
        if canBuy && primary != .server { secondary.append(.buy) }
        return RecipeActions(primary: primary, secondary: secondary)
    }
}

/// Where "How recipes work" was opened: by itself on the first visit, or from Recipe settings.
enum RecipeIntroOrigin: Equatable {
    case firstOpen, fromSettings
}

/// Where the allergen consent step was opened: before a server request (agreeing sends it) or
/// from the switch in Recipe settings (agreeing only records it).
enum AllergenConsentPrompt: Equatable {
    case beforeWriting, fromSettings
}

/// The screens pushed from the Recipe tab.
enum RecipeDestination: Hashable {
    case result(id: String)
    /// One dish of a saved menu.
    case dish(id: String, day: Int, course: Int)
    case saved
    case settings
}

/// The Recipe tab's state: the configuration, which items are ticked, the routes, the one write
/// in flight, and saved recipes. Separate from `NativePantryViewModel`, which it only reads
/// items from. The paid route's account, purchases and count are `credits`, whose changes this
/// object republishes so screens observe one thing.
@MainActor
final class RecipeViewModel: ObservableObject {
    @Published private(set) var config: RecipeConfig
    @Published private(set) var selectedIDs: Set<String> = []
    @Published private(set) var phase: RecipePhase = .idle
    @Published private(set) var onDeviceAvailability: OnDeviceRecipeAvailability = .unsupported
    @Published private(set) var saved: [SavedRecipe] = []
    /// The tab's navigation stack. Here rather than in the view so a recipe that arrives, or a
    /// way in from Home, can set it while the tab is off screen.
    @Published var path: [RecipeDestination] = []
    @Published var failure: RecipeFailure? {
        // A failed or refused generation rules the rating prompt out for the session.
        didSet { if failure?.blocksReviewPrompt == true { ReviewPrompter.shared.problemShown() } }
    }
    @Published var mode: RecipeMode = .recipe
    @Published private(set) var menuCourses: Int
    /// Opens the pack sheet: Buy recipes, the count in the bar, or a generation that found the
    /// pack empty.
    @Published var showsBuySheet = false
    /// Every server instance was busy on the last call: the writing screen says it is trying
    /// again rather than failing.
    @Published private(set) var serverBusyRetrying = false
    /// The allergen consent step on screen, and where it was opened from; nil when not shown.
    @Published var consentPrompt: AllergenConsentPrompt?
    /// The answer to sending allergens to our server, as recorded on this phone.
    @Published private(set) var allergenConsent: AllergenConsentRecord?
    /// "Send all products" and whether the introduction was seen.
    @Published private(set) var options: RecipeOptions
    /// "How recipes work" on screen, and where from; nil when it isn't.
    @Published var intro: RecipeIntroOrigin?
    /// A demo or screenshot launch (any `-demo…` argument): the introduction never opens by
    /// itself in this run.
    private var introSuppressed = ReviewPrompter.isDemoLaunch

    /// Whether the person has changed the ticks since they were picked for them. Until then the
    /// soonest items follow the pantry as it changes.
    private(set) var selectionIsCustom = false

    private let configStore: RecipeConfigStore
    private let savedStore: SavedRecipeStore
    private let onDeviceModel: OnDeviceRecipeModel
    let credits: RecipeCredits
    private var writeTask: Task<Void, Never>?
    /// Item names the server refused this session (lower-cased, as sent), left out of later
    /// requests so a refused name is never sent twice.
    private var refusedNames = Set<String>()
    private var creditsChanges: AnyCancellable?
    private var allergenChanges: AnyCancellable?

    /// How many of the soonest items are ticked for the person.
    static let defaultTickCount = 3
    /// The tab always lists at least this many items, more when more are near their date.
    static let minimumShownCount = 5

    init(
        configStore: RecipeConfigStore = UserDefaultsRecipeConfigStore(),
        savedStore: SavedRecipeStore? = nil,
        onDeviceModel: OnDeviceRecipeModel = FoundationModelsRecipeModel(),
        credits: RecipeCredits? = nil
    ) {
        self.configStore = configStore
        self.savedStore = savedStore ?? SwiftDataSavedRecipeStore()
        self.onDeviceModel = onDeviceModel
        let credits = credits ?? RecipeCredits()
        self.credits = credits
        config = configStore.load()
        options = configStore.loadOptions()
        menuCourses = credits.storage.menuCourses
        saved = self.savedStore.all()
        refreshAvailability()
        creditsChanges = credits.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        allergenConsent = credits.storage.allergenConsent
        // A refusal is forgotten once the allergen list is emptied (`AllergenConsent.afterListChange`).
        allergenChanges = SettingsStore.shared.$settings
            .map { AllergenConsent.hasAllergens($0.allergens) }
            .removeDuplicates()
            .sink { [weak self] has in self?.allergensChanged(hasAllergens: has) }
    }

    // MARK: Allergen consent

    private func recordAllergenConsent(_ record: AllergenConsentRecord?) {
        credits.storage.allergenConsent = record
        allergenConsent = record
    }

    private func allergensChanged(hasAllergens: Bool) {
        let current = credits.storage.allergenConsent
        let next = AllergenConsent.afterListChange(current, hasAllergens: hasAllergens)
        if next != current { recordAllergenConsent(next) }
    }

    /// "Agree and continue" (or "Agree" from Recipe settings): recorded with the time and the
    /// wording's version. Returns whether the request it was asked for should now be sent.
    @discardableResult
    func agreeToAllergenConsent() -> Bool {
        guard let prompt = consentPrompt else { return false }
        recordAllergenConsent(AllergenConsentRecord(agreed: true, at: Date(), version: AllergenConsent.version))
        consentPrompt = nil
        return prompt == .beforeWriting
    }

    /// "Cancel": recorded as a refusal, so the step isn't shown on every tap; the server route
    /// stays blocked while allergens are set, and the free routes are there.
    func declineAllergenConsent() {
        guard consentPrompt != nil else { return }
        recordAllergenConsent(AllergenConsentRecord(agreed: false, at: Date(), version: AllergenConsent.version))
        consentPrompt = nil
    }

    /// Recipe settings' switch: off withdraws at once; on shows the consent step first.
    func setAllergenConsent(_ on: Bool) {
        if on {
            if !AllergenConsent.isGiven(allergenConsent) { consentPrompt = .fromSettings }
        } else {
            recordAllergenConsent(AllergenConsentRecord(agreed: false, at: Date(), version: AllergenConsent.version))
        }
    }

    /// At launch: purchases waiting for the server, and a generation a closed app interrupted,
    /// which the same id fetches without a second charge.
    func start() {
        credits.start()
        if credits.storage.pendingGeneration != nil, credits.account.uid != nil {
            resumeServerWrite()
        }
    }

    var routes: RecipeRoutes { RecipeRoutes.from(onDeviceAvailability) }

    // MARK: Configuration

    func updateConfig(_ transform: (inout RecipeConfig) -> Void) {
        var updated = config
        transform(&updated)
        guard updated != config else { return }
        let languageChanged = updated.language != config.language
        config = updated
        configStore.save(updated)
        if languageChanged { refreshAvailability() }
    }

    /// "Send all products" in Recipe settings.
    func setSendAllItems(_ on: Bool) {
        updateOptions { $0.sendAllItems = on }
    }

    private func updateOptions(_ transform: (inout RecipeOptions) -> Void) {
        var updated = options
        transform(&updated)
        guard updated != options else { return }
        options = updated
        configStore.saveOptions(updated)
    }

    // MARK: Introduction

    /// The tab is on screen: the first time ever, "How recipes work" opens. Never in a demo
    /// launch, never again once closed. Returns whether it opened, so the caller treats it as
    /// onboarding (no rating request in this session). As Android's `onTabShown`.
    @discardableResult
    func onTabShown() -> Bool {
        guard !introSuppressed, !options.introSeen, intro == nil else { return false }
        intro = .firstOpen
        return true
    }

    /// Recipe settings' "How recipes work".
    func showIntro() { intro = .fromSettings }

    /// Closed, whichever way: never opens by itself again.
    func dismissIntro() {
        intro = nil
        updateOptions { $0.introSeen = true }
    }

    /// "See recipe packs": closed, then the pack sheet.
    func introToPacks() {
        dismissIntro()
        if credits.canBuy { showsBuySheet = true }
    }

    /// `-demoRecipeIntro YES`: shown as on a first open, to look at it.
    func showIntroForDemo() { intro = .firstOpen }

    /// Something is saved, so the header offers the saved list: the first recipe written shows
    /// it, deleting the last one hides it.
    var hasSaved: Bool { !saved.isEmpty }

    /// Reads the saved list again, for changes made outside this view model.
    func reloadSaved() {
        let current = savedStore.all()
        if current.map(\.id) != saved.map(\.id) { saved = current }
    }

    /// The form's actions now; see `RecipeActions`.
    var actions: RecipeActions {
        RecipeActions.of(
            isMenu: credits.isPaidRouteAvailable && mode == .menu,
            serverCanWrite: mode == .menu ? serverCanWriteMenu : serverCanWriteRecipe,
            offline: credits.showsOffline,
            onDevice: routes.onDevice,
            canBuy: credits.canBuy
        )
    }

    /// What "Send all products" adds from `items`: everything not ticked, soonest first; empty
    /// with it off.
    func optionalItems(from items: [PantryItem]) -> [PantryItem] {
        guard options.sendAllItems else { return [] }
        return Self.soonestFirst(items.filter { !selectedIDs.contains($0.id) })
    }

    /// Re-asks the model, which can finish downloading or be switched on while the app runs.
    func refreshAvailability() {
        onDeviceAvailability = onDeviceModel.availability(for: config.resolvedLanguage)
    }

    // MARK: Ticked items

    /// Called whenever the pantry changes: drops ticks on items that are gone, and while the
    /// person hasn't chosen, keeps the soonest items ticked.
    func syncSelection(with items: [PantryItem]) {
        let ids = Set(items.map(\.id))
        if selectionIsCustom {
            selectedIDs.formIntersection(ids)
        } else {
            selectedIDs = Set(Self.defaultSelection(items))
        }
    }

    func toggle(_ id: String) {
        selectionIsCustom = true
        if selectedIDs.contains(id) { selectedIDs.remove(id) } else { selectedIDs.insert(id) }
    }

    /// Home's "Get recipe ideas" and the summary notification's action arrive with their own
    /// items, which replace the picked ones.
    func select(_ ids: [String], from items: [PantryItem]) {
        let present = Set(items.map(\.id))
        let chosen = Set(ids).intersection(present)
        guard !chosen.isEmpty else { return }
        selectionIsCustom = true
        selectedIDs = chosen
    }

    func selectedItems(from items: [PantryItem]) -> [PantryItem] {
        Self.soonestFirst(items.filter { selectedIDs.contains($0.id) })
    }

    // MARK: Routes

    func request(for items: [PantryItem]) -> RecipeRequest {
        RecipeRequest(
            items: selectedItems(from: items).map(RecipeIngredientInput.init),
            config: config,
            allergens: Array(SettingsStore.shared.settings.allergens),
            otherItems: optionalItems(from: items).map(RecipeIngredientInput.init)
        )
    }

    func sharePrompt(for items: [PantryItem]) -> String {
        RecipePromptBuilder.sharePrompt(for: request(for: items))
    }

    func setMenuCourses(_ courses: Int) {
        menuCourses = courses == 3 ? 3 : 2
        credits.storage.menuCourses = menuCourses
    }

    // MARK: Server route

    /// Whether the server writes the recipe: while there are recipes in the pack. Otherwise the
    /// free routes take the primary action.
    var serverCanWriteRecipe: Bool { serverCanWrite(.recipe) }
    var serverCanWriteMenu: Bool { serverCanWrite(.menu) }

    /// What a recipe, or a menu of the chosen number of courses, takes, from the server's costs.
    var recipeCost: Int { credits.cost(of: .recipe, courses: menuCourses) }
    var menuCost: Int { credits.cost(of: .menu, courses: menuCourses) }

    private func serverCanWrite(_ kind: RecipeKind) -> Bool {
        credits.isPaidRouteAvailable && !credits.serverPaused
            && (credits.balance ?? 0) >= credits.cost(of: kind, courses: menuCourses)
    }

    func writeOnServer(from items: [PantryItem]) {
        let kind: RecipeKind = mode == .menu ? .menu : .recipe
        let request = request(for: items)
        guard !request.items.isEmpty, phase == .idle else { return }
        // Allergens are health data: sent only with the person's consent, and never left out
        // instead (a recipe written without them could be unsafe).
        switch AllergenConsent.step(hasAllergens: AllergenConsent.hasAllergens(request.allergens), record: credits.storage.allergenConsent) {
        case .ask:
            consentPrompt = .beforeWriting
            return
        case .declined:
            failure = .allergenConsentDeclined
            return
        case .notNeeded, .granted:
            break
        }
        // More than the server takes: stopped and said so, never sent with some left out.
        if Set(request.allergens.compactMap(RecipeWire.cleanName).map { $0.lowercased() }).count > RecipeWire.maxAllergens {
            failure = .tooManyAllergens(limit: RecipeWire.maxAllergens)
            return
        }
        if let allergen = RecipeWire.unsendableAllergen(request.allergens) {
            failure = .allergenRefused(allergen)
            return
        }
        // The other items go only to a server that said it reads them (`acceptsOptionalIngredients`).
        let fresh = RecipeWire.request(
            for: request, kind: kind, courses: menuCourses, requestID: UUID(), excluding: refusedNames,
            withOptional: options.sendAllItems && credits.storage.serverTakesOptional
        )
        guard !fresh.ingredients.isEmpty else {
            // Every ticked name was made of characters the server refuses.
            failure = .serverFailed(kind, sameRequest: false)
            return
        }
        let held = credits.storage.pendingGeneration
        let wire = RecipeWire.requestToSend(fresh: fresh, held: held)
        // A held request for other inputs may still be written, and charged, on the server: it
        // is fetched (and saved) first, and stays the stored one until it has settled.
        let earlier = RecipeWire.abandonedRequest(fresh: fresh, held: held)
        if earlier == nil { credits.storage.pendingGeneration = wire }
        // A held request may already be running or done on the server: asked after, not resent.
        runServerWrite(wire, start: wire.requestId == fresh.requestId, collecting: earlier)
    }

    /// Sends the interrupted request again under its own id: "Try again" after a lost
    /// connection or a server error, and at launch.
    func resumeServerWrite() {
        guard phase == .idle, let wire = credits.storage.pendingGeneration else { return }
        runServerWrite(wire, start: false)
    }

    var hasInterruptedServerWrite: Bool { credits.storage.pendingGeneration != nil }

    /// `start`: a new request, sent with `generateRecipe`. Otherwise (resuming a held one) and
    /// for every IN_PROGRESS poll, `getGeneration` asks after it without spending a limited-use
    /// App Check token, falling back to `generateRecipe` with the same id and inputs when the
    /// server doesn't know the id or predates `getGeneration`. `earlier`: a held request for
    /// other inputs, settled (and its result saved) before `wire` is sent.
    /// `afterRefusal`: `wire` already left out an item the server refused; a second refusal
    /// stops rather than trying again.
    private func runServerWrite(_ wire: GenerateRequest, start: Bool, collecting earlier: GenerateRequest? = nil, afterRefusal: Bool = false) {
        phase = .writingOnServer(wire.kind)
        serverBusyRetrying = false
        let credits = credits
        writeTask = Task { [weak self] in
            var collecting = earlier != nil
            do {
                try await credits.ensureUser()
                if let earlier, let self {
                    let response = try await self.settle(earlier, start: false, mayStart: false)
                    if let response, response.status == .inProgress {
                        // Still being written: the server would answer the new one with BUSY.
                        self.stopServerWrite(with: .busy)
                        return
                    }
                    if let response { self.collectEarlier(earlier, response) }
                    collecting = false
                    credits.storage.pendingGeneration = wire
                }
                guard let self else { return }
                guard let response = try await self.settle(wire, start: start, mayStart: true) else {
                    self.stopServerWrite(with: .serverFailed(wire.kind, sameRequest: true))
                    return
                }
                self.finishServerWrite(wire, response)
            } catch is CancellationError {
                self?.phase = .idle
                self?.serverBusyRetrying = false
            } catch RecipeServerError.refusedName(let field) where !collecting {
                self?.refused(field: field, in: wire, afterRefusal: afterRefusal)
            } catch {
                // While the earlier request is being asked after, it stays the stored one, and
                // "Try again" builds the new one again (and asks after the earlier first).
                if collecting, let earlier {
                    self?.failServerWrite(earlier, error, retriesFresh: true, kind: wire.kind)
                } else {
                    self?.failServerWrite(wire, error)
                }
            }
        }
    }

    /// Sends `wire` (`start`) or asks after it until it settles: DONE or FAILED, or still
    /// IN_PROGRESS when the wait runs out (150 s for a recipe, 270 s for a menu, as Android).
    /// IN_PROGRESS is asked again every 3 s, never sooner. Busy servers (every instance taken)
    /// are asked again with the same id after 3, 6, 12, 24, 30 s; and while the server may
    /// already hold the request, so are the per-minute throttle and BUSY, which say nothing
    /// about it. `mayStart` false (an abandoned request): never sent with `generateRecipe`, and
    /// nil when the server never got it.
    private func settle(_ wire: GenerateRequest, start: Bool, mayStart: Bool) async throws -> GenerateResponse? {
        let backend = credits.backend
        let deadline = Date().addingTimeInterval(wire.kind == .menu ? 270 : 150)
        var sendsRequest = start
        // An older server without `getGeneration`: re-sending is how it is asked.
        var serverLacksGetGeneration = false
        // Whether the server may hold the request: anything but a first send not yet answered.
        var mayBeHeld = !start
        var busyAnswers = 0
        defer { serverBusyRetrying = false }
        while true {
            let response: GenerateResponse
            do {
                if sendsRequest || serverLacksGetGeneration {
                    response = try await backend.generate(wire)
                } else {
                    do {
                        response = try await backend.generation(requestId: wire.requestId)
                    } catch let error as RecipeServerError where error.fallsBackToGenerate {
                        guard mayStart else { return nil }
                        if error == .callableMissing { serverLacksGetGeneration = true }
                        response = try await backend.generate(wire)
                    }
                }
            } catch let error as RecipeServerError
                where error == .serversBusy || (mayBeHeld && error.keepsAskingAfterHeldRequest) {
                guard busyAnswers < RecipeWire.maxBusyRetries, Date() < deadline else {
                    // Kept under its id for "Try again": nothing was charged yet, or it may still
                    // be written and charged, and the same id never pays twice.
                    throw RecipeServerError.server
                }
                serverBusyRetrying = error == .serversBusy
                try await Task.sleep(for: RecipeWire.busyRetryDelay(attempt: busyAnswers))
                busyAnswers += 1
                continue
            }
            serverBusyRetrying = false
            busyAnswers = 0
            sendsRequest = false
            mayBeHeld = true
            if response.status == .inProgress, Date() < deadline {
                credits.apply(response)
                try await Task.sleep(for: .seconds(3))
                continue
            }
            return response
        }
    }

    /// The answer for a request held from before the form changed: saved quietly when it
    /// arrived, the count updated either way. Nothing navigates; the new request follows.
    private func collectEarlier(_ earlier: GenerateRequest, _ response: GenerateResponse) {
        credits.apply(response)
        credits.callSucceeded()
        switch Self.outcome(of: response, for: earlier, costs: credits.costs) {
        case .saved(let content):
            savedStore.save(SavedRecipe(content: content))
            saved = savedStore.all()
            failure = .earlierArrived(earlier.kind)
        case .failure(.serverRefused(_, let taken)):
            failure = .earlierRefused(taken: taken)
        case .failure:
            // Refunded, or nothing usable: nothing to say about a request the person moved on from.
            break
        }
    }

    /// A name the server refused (`INVALID_REQUEST` with `details.field`). Nothing was charged.
    /// A refused item is left out and the request sent once more under a new id; a refused
    /// allergen stops it. Never more than one retry: repeated refusals lock the account for the
    /// day (`USER_DAILY_LIMIT`).
    private func refused(field: String, in wire: GenerateRequest, afterRefusal: Bool) {
        credits.storage.pendingGeneration = nil
        switch RecipeWire.refusedName(field: field, in: wire) {
        case .ingredient(let index, let name)?:
            refusedNames.insert(name.lowercased())
            guard !afterRefusal, let next = RecipeWire.dropping(ingredientAt: index, from: wire, newID: UUID()) else {
                stopServerWrite(with: .itemNotSent(name))
                return
            }
            failure = .itemLeftOut(name)
            credits.storage.pendingGeneration = next
            runServerWrite(next, start: true, afterRefusal: true)
        case .allergen(let name)?:
            stopServerWrite(with: .allergenRefused(name))
        case nil:
            stopServerWrite(with: .serverFailed(wire.kind, sameRequest: false))
        }
    }

    private func stopServerWrite(with failure: RecipeFailure) {
        writeTask = nil
        phase = .idle
        serverBusyRetrying = false
        self.failure = failure
    }

    private func finishServerWrite(_ wire: GenerateRequest, _ response: GenerateResponse) {
        writeTask = nil
        phase = .idle
        serverBusyRetrying = false
        credits.apply(response)
        credits.callSucceeded()
        let outcome = Self.outcome(of: response, for: wire, costs: credits.costs)
        if outcome.clearsRequest { credits.storage.pendingGeneration = nil }
        switch outcome {
        case .saved(let content):
            let entry = SavedRecipe(content: content)
            savedStore.save(entry)
            saved = savedStore.all()
            path = [.result(id: entry.id)]
            ReviewPrompter.shared.recipeArrived()
            failure = .arrived(balance: response.balance)
        case .failure(let failure):
            self.failure = failure
        }
    }

    /// `retriesFresh`: the failure came while asking after an earlier request, so "Try again"
    /// builds the new one (of `kind`) again rather than resuming the stored one.
    private func failServerWrite(_ wire: GenerateRequest, _ error: Error, retriesFresh: Bool = false, kind: RecipeKind? = nil) {
        writeTask = nil
        phase = .idle
        serverBusyRetrying = false
        credits.note(error)
        let serverError = error as? RecipeServerError ?? .server
        if !serverError.isRetryableWithSameID { credits.storage.pendingGeneration = nil }
        if serverError == .insufficientCredits {
            if credits.canBuy { showsBuySheet = true }
            return
        }
        let kind = kind ?? wire.kind
        guard var failure = Self.failure(for: serverError, kind: kind, takenIfCharged: credits.costs.cost(of: wire)) else { return }
        if retriesFresh, case .serverFailed = failure { failure = .serverFailed(kind, sameRequest: false) }
        self.failure = failure
    }

    /// The snackbar a failed `generateRecipe` call raises, or nil where a lasting condition's
    /// callout (or the pack sheet, for INSUFFICIENT_CREDITS) says it instead. `takenIfCharged` is
    /// what the request takes, for an unknown refusal that says it was charged.
    static func failure(for error: RecipeServerError, kind: RecipeKind, takenIfCharged: Int) -> RecipeFailure? {
        switch error {
        case .insufficientCredits, .offline, .generationDisabled, .accountClosed:
            return nil
        case .busy: return .busy
        case .rateLimited: return .rateLimited
        case .userDailyLimit: return .userDailyLimit
        case .infraFailureLimit: return .infraFailureLimit
        case .dailyCap: return .dailyCap
        case .server, .appCheckReplayed, .unknownRequest, .callableMissing, .serversBusy:
            // Nothing was decided on the server for this attempt: ask again under the same id.
            return .serverFailed(kind, sameRequest: true)
        case .requestIdReused, .unauthenticated, .invalidRequest, .recentSignInRequired, .mergeNotAllowed,
             .storeMismatch, .purchaseAccountMismatch, .refusedName, .allergenConsentRequired:
            // REQUEST_ID_REUSED: the stored request is already dropped, so "Try again" builds a
            // new one under a new id.
            return .serverFailed(kind, sameRequest: false)
        case .unrecognised(let charged):
            return .serverUnrecognised(kind, taken: charged ? takenIfCharged : 0)
        }
    }

    enum ServerOutcome: Equatable {
        case saved(SavedRecipe.Content)
        case failure(RecipeFailure)

        /// Whether the stored request is done with: everything but an answer to ask for again.
        var clearsRequest: Bool {
            if case .failure(.serverFailed(_, sameRequest: true)) = self { return false }
            return true
        }
    }

    /// What one `generateRecipe` answer means for the screen. A DONE without the right payload,
    /// or an answer still IN_PROGRESS past the wait, reads as a failure retried under the same
    /// id: the server holds the charge until it settles, and the same id never pays twice.
    /// `costs` says how many a counted failure took.
    static func outcome(of response: GenerateResponse, for wire: GenerateRequest, costs: RecipeCosts) -> ServerOutcome {
        let kind = wire.kind
        switch response.status {
        case .done:
            switch kind {
            case .recipe:
                if let recipe = response.recipe { return .saved(.recipe(RecipeWire.markedServer(recipe))) }
            case .menu:
                if var menu = response.menu, !menu.days.isEmpty {
                    for day in menu.days.indices {
                        for course in menu.days[day].courses.indices {
                            menu.days[day].courses[course].recipe.source = .server
                        }
                    }
                    return .saved(.menu(menu))
                }
            }
            return .failure(.serverFailed(kind, sameRequest: true))
        case .inProgress:
            return .failure(.serverFailed(kind, sameRequest: true))
        case .failed:
            return .failure(
                response.charged
                    ? .serverRefused(kind, taken: costs.cost(of: wire))
                    : .serverFailed(kind, sameRequest: false)
            )
        }
    }

    // MARK: On-device route

    func writeOnDevice(from items: [PantryItem]) {
        let request = request(for: items)
        guard !request.items.isEmpty, phase == .idle else { return }
        phase = .writingOnDevice
        let model = onDeviceModel
        writeTask = Task { [weak self] in
            do {
                let recipe = try await model.write(request)
                self?.finishWriting(with: recipe)
            } catch is CancellationError {
                self?.phase = .idle
            } catch {
                self?.failWriting(error as? OnDeviceRecipeError ?? .failed)
            }
        }
    }

    /// Stop, on the "Writing on this phone" screen. Nothing was paid, so nothing is lost.
    func stopWriting() {
        writeTask?.cancel()
        writeTask = nil
        phase = .idle
    }

    private func finishWriting(with recipe: Recipe) {
        guard phase == .writingOnDevice else { return }
        writeTask = nil
        let entry = SavedRecipe(content: .recipe(recipe))
        savedStore.save(entry)
        saved = savedStore.all()
        phase = .idle
        path = [.result(id: entry.id)]
        ReviewPrompter.shared.recipeArrived()
    }

    private func failWriting(_ error: OnDeviceRecipeError) {
        guard phase == .writingOnDevice else { return }
        writeTask = nil
        phase = .idle
        switch error {
        case .unavailable, .unsupportedLanguage:
            // The route goes away with the reason; the prompt is still there.
            refreshAvailability()
            failure = .onDeviceFailed
        case .refused:
            failure = .onDeviceRefused
        case .failed:
            failure = .onDeviceFailed
        }
    }

    // MARK: Saved recipes

    /// What Saved recipes lists: recipes and menus, newest first.
    var savedRecipes: [SavedRecipe] { saved }

    func savedRecipe(id: String) -> SavedRecipe? {
        saved.first { $0.id == id }
    }

    /// Returns what Undo puts back.
    func deleteSaved(id: String) -> SavedRecipe? {
        guard let entry = savedRecipe(id: id) else { return nil }
        savedStore.delete(id: id)
        saved = savedStore.all()
        return entry
    }

    func restoreSaved(_ entry: SavedRecipe) {
        savedStore.save(entry)
        saved = savedStore.all()
    }

    #if DEBUG
    /// `-demoRecipeState busy`: the writing screen while every server instance is busy and the
    /// request is sent again. Nothing is sent.
    func showDemoServerBusy() {
        phase = .writingOnServer(mode == .menu ? .menu : .recipe)
        serverBusyRetrying = true
    }

    /// `-demoRoute recipeResult`: a recipe as the phone's model would write one, saved so the
    /// result and Saved recipes screens can be seen where the model can't run, as in the
    /// simulator. Returns its id.
    func saveDemoRecipe() -> String {
        let recipe = Recipe(
            title: "Spinach yoghurt toast",
            summary: "Uses the bread, spinach and yoghurt.",
            minutes: 10,
            servings: config.servings,
            difficulty: .easy,
            ingredients: [
                RecipeIngredient(amount: "4 slices", name: "Sourdough bread", fromPantry: true),
                RecipeIngredient(amount: "100 g", name: "Baby spinach", fromPantry: true),
                RecipeIngredient(amount: "150 g", name: "Greek yoghurt", fromPantry: true),
                RecipeIngredient(amount: "", name: "Salt, pepper, olive oil", fromPantry: false),
            ],
            steps: [
                "Toast the bread.",
                "Wilt the spinach in a little oil for a minute and season.",
                "Spread yoghurt on the toast and top with the spinach.",
            ],
            tip: "Day-old sourdough toasts better than fresh.",
            source: .device
        )
        let entry = SavedRecipe(content: .recipe(recipe))
        savedStore.save(entry)
        saved = savedStore.all()
        return entry.id
    }

    /// `-demoRoute recipeMenu`: a three-day menu as the server would write one, saved.
    func saveDemoMenu() -> String {
        func dish(_ title: String, _ minutes: Int) -> Recipe {
            Recipe(
                title: title,
                summary: "",
                minutes: minutes,
                servings: config.servings,
                difficulty: .easy,
                ingredients: [RecipeIngredient(amount: "100 g", name: "Baby spinach", fromPantry: true)],
                steps: ["Prepare and serve."],
                source: .server
            )
        }
        let calendar = Calendar.current
        let days = (0..<3).map { offset -> RecipeMenuDay in
            let date = calendar.date(byAdding: .day, value: offset, to: Date()) ?? Date()
            return RecipeMenuDay(
                date: RecipeWire.isoDay(date),
                uses: [["bread", "spinach", "yoghurt"], ["yoghurt", "milk"], ["salmon", "strawberries"]][offset],
                courses: [
                    RecipeMenuCourse(course: .starter, recipe: dish(["Spinach and yoghurt soup", "Cucumber and yoghurt salad", "Salmon rillettes on toast"][offset], 20)),
                    RecipeMenuCourse(course: .main, recipe: dish(["Eggs on garlic sourdough toast", "Creamy milk-poached salmon", "Pan-fried salmon with greens"][offset], 25)),
                    RecipeMenuCourse(course: .dessert, recipe: dish(["Yoghurt with honey and toasted crumbs", "Vanilla milk pudding", "Strawberries with sweet cream"][offset], 10)),
                ]
            )
        }
        let entry = SavedRecipe(content: .menu(RecipeMenu(days: days)))
        savedStore.save(entry)
        saved = savedStore.all()
        return entry.id
    }
    #endif

    // MARK: Pure rules

    static func soonestFirst(_ items: [PantryItem]) -> [PantryItem] {
        items.sorted {
            $0.expiryDate != $1.expiryDate
                ? $0.expiryDate < $1.expiryDate
                : $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
    }

    /// The three soonest items.
    static func defaultSelection(_ items: [PantryItem]) -> [String] {
        soonestFirst(items).prefix(defaultTickCount).map(\.id)
    }

    /// The rows on the tab: everything past its date or due within the "soon" window, at least
    /// five items, and anything else that is ticked, soonest first.
    static func shownItems(_ items: [PantryItem], selected: Set<String>, expiringSoonDays: Int) -> [PantryItem] {
        soonestFirst(items).enumerated()
            .filter { index, item in
                index < minimumShownCount || item.daysRemaining <= expiringSoonDays || selected.contains(item.id)
            }
            .map(\.element)
    }

    /// "Add other items" lists what the tab doesn't.
    static func otherItems(_ items: [PantryItem], selected: Set<String>, expiringSoonDays: Int) -> [PantryItem] {
        let shown = Set(shownItems(items, selected: selected, expiringSoonDays: expiringSoonDays).map(\.id))
        return soonestFirst(items.filter { !shown.contains($0.id) })
    }
}
