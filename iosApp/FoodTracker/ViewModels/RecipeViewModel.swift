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

/// A single recipe, or the menu set (days and courses a day) that only the server writes.
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
    /// `NOT_ENOUGH_ITEMS`: the ticked items can't make it once the allergens and diets are
    /// applied (for a menu, some day has nothing it may use). Nothing taken; the form stays.
    /// `days`: a menu's day count, for the wording (3 for a recipe).
    case notEnoughItems(RecipeKind, days: Int)
    /// The selected diets can't all be sent to the server as it is now (gluten-free with another
    /// diet, before it reads several): nothing was sent. Android: `DietsNeedServerUpdate`.
    case dietsNeedServerUpdate
    /// The recipe would be in Danish and the server hasn't said `acceptsDanish` (one deployed
    /// before it): nothing was sent, never another language in its place. Android:
    /// `LanguageNeedsServerUpdate`.
    case languageNeedsServerUpdate
    /// `IMAGE_UNAVAILABLE`: the picture level asked for isn't made now. Nothing was taken; the
    /// costs are asked for again (which withdraws the level) and Try again is a new request.
    /// Android: `RecipeEvent.PicturesUnavailable`.
    case picturesUnavailable
    /// "Report this recipe" was received: "Thanks. We'll look at it." The rating prompt is ruled
    /// out for the session by `ReviewPrompter.reportSent` itself.
    case reportSent
}

extension RecipeFailure {
    /// Every snackbar but the two that bring a recipe rules the rating prompt out for the
    /// session. As Android's `ReviewPrompter.onRecipeEvent`.
    var blocksReviewPrompt: Bool {
        switch self {
        case .arrived, .earlierArrived: return false
        // Rules the prompt out through `ReviewPrompter.reportSent`.
        case .reportSent: return false
        default: return true
        }
    }
}

/// A way to get a recipe from the form, each its own button: a tap on one never falls back to
/// another, so a free route never spends a recipe. Android: `RecipeAction`, which also has a
/// Copy for Android below 10, whose share sheet can't copy; iOS's always can, so it has none.
enum RecipeAction: Equatable {
    case server, onDevice, share, buy
}

/// What the form offers: `primary` is the first, filled button of the form's button group (nil:
/// none), `secondary` follows it in this order. Android: `RecipeActions` (`RecipeUiState.actions`).
struct RecipeActions: Equatable {
    var primary: RecipeAction?
    var secondary: [RecipeAction]

    /// With recipes in the pack the server is the primary and both free ways stay one tap away;
    /// without (or offline), the phone's model or the shared prompt is. A menu is the server's only.
    static func of(isMenu: Bool, serverCanWrite: Bool, offline: Bool, onDevice: Bool, canBuy: Bool) -> RecipeActions {
        if isMenu { return RecipeActions(primary: serverCanWrite ? .server : nil, secondary: []) }
        let primary: RecipeAction = serverCanWrite && !offline ? .server : onDevice ? .onDevice : .share
        var secondary: [RecipeAction] = []
        if onDevice && primary != .onDevice { secondary.append(.onDevice) }
        if primary != .share { secondary.append(.share) }
        if canBuy && primary != .server { secondary.append(.buy) }
        return RecipeActions(primary: primary, secondary: secondary)
    }
}

/// The report sheet on screen (`RecipeViewModel.openReport`). `reportId` is kept for every retry
/// of this report, so the server stores it once. Android: `ReportSheet`.
struct RecipeReportSheet: Identifiable, Equatable {
    var id: String { reportId }
    let requestId: String
    let isMenu: Bool
    let hasPictures: Bool
    let reportId: String
    var reason: RecipeReportReason? = nil
    var note: String = ""
    var status: RecipeReportStatus = .editing
    /// Send was tapped without a reason: "Choose a reason."
    var reasonMissing = false

    var reasons: [RecipeReportReason] { RecipeReporting.reasons(hasPictures: hasPictures) }
}

/// Where "How recipes work" was opened: by itself on the first visit, or from Recipe settings.
/// The free recipes' sign-in sheet as it opened: `recipes` is the offer then (0: a plain sign-in).
struct WelcomeSheetRequest: Identifiable, Equatable {
    let id = UUID()
    let recipes: Int
}

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
    /// The taste-profile quiz, over Recipe settings.
    case tasteQuiz
    /// The Staples screen, from the form's staples line.
    case staples
}

/// The definition the open quiz shows.
enum TasteQuizLoad: Equatable {
    case loading
    /// Couldn't load the questions, and none is cached: Retry and Close.
    case failed
    case ready(TasteQuiz)
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
    @Published var path: [RecipeDestination] = [] {
        didSet {
            // A result's picture lines go once it is left (one that arrived unread keeps them until
            // it has been opened and left): the saved copy opens without them, as on Android.
            guard !pictureNotices.isEmpty else { return }
            func results(_ path: [RecipeDestination]) -> Set<String> {
                Set(path.compactMap { destination -> String? in
                    if case .result(let id) = destination { return id }
                    return nil
                })
            }
            let left = results(oldValue).subtracting(results(path))
            guard !left.isEmpty else { return }
            let kept = pictureNotices.filter { !left.contains($0.key) }
            if kept != pictureNotices { pictureNotices = kept }
        }
    }
    /// The Pictures sheet, from the form's Pictures row.
    @Published var showsPicturesSheet = false
    @Published var failure: RecipeFailure? {
        // A failed or refused generation rules the rating prompt out for the session.
        didSet { if failure?.blocksReviewPrompt == true { ReviewPrompter.shared.problemShown() } }
    }
    @Published var mode: RecipeMode = .recipe
    /// The menu's courses a day and days as last chosen; what is asked for is `menuSet`.
    @Published private(set) var menuCourses: Int
    @Published private(set) var menuDays: Int
    /// The day count of the menu being written, for the writing screen's title.
    @Published private(set) var writingMenuDays = MenuSet.defaultDays
    /// The picture level of the recipe being written (nil: none), for the writing screen's body.
    @Published private(set) var writingPictures: RecipePictureLevel?
    /// "Report this recipe / menu" on screen; nil when it isn't.
    @Published var reportSheet: RecipeReportSheet?
    /// The quiet lines for results that just arrived with fewer pictures than asked, by saved id:
    /// shown on that result until it is left, never when it is opened later.
    @Published private(set) var pictureNotices: [String: RecipePictureNotice] = [:]
    /// The pictures of results that arrived in this run, by saved id, so opening one doesn't read
    /// them back. Only the last few.
    private var pictureCache: [String: SavedRecipePictures] = [:]
    private var pictureCacheOrder: [String] = []
    /// Whether the Recipe tab is on screen with the app active (`RecipeView` says so): a result
    /// arriving then, with nothing pushed over the writing screen, is shown and so read.
    private var tabOnScreen = false
    private var sceneActive = true
    /// Opens the pack sheet: Buy recipes, the count in the bar, or a generation that found the
    /// pack empty.
    @Published var showsBuySheet = false
    /// The sign-in sheet of the free recipes, with the number it opened with: it never changes
    /// while open.
    @Published var welcomeSheet: WelcomeSheetRequest?
    /// The offer was tapped and its number is being asked again before the sheet opens.
    @Published private(set) var openingWelcomeSheet = false
    /// Every server instance was busy on the last call: the writing screen says it is trying
    /// again rather than failing.
    @Published private(set) var serverBusyRetrying = false
    /// The allergen consent step on screen, and where it was opened from; nil when not shown.
    @Published var consentPrompt: AllergenConsentPrompt?
    /// The answer to sending allergens to our server, as recorded on this phone.
    @Published private(set) var allergenConsent: AllergenConsentRecord?
    /// "Send all products" and whether the introduction was seen.
    @Published private(set) var options: RecipeOptions
    /// The taste profile as saved on this phone (empty: none). Only a server request carries it,
    /// filtered through the definition (`TasteProfile.toSend`).
    @Published private(set) var taste: TasteProfile
    /// The quiz steps the person has been through, kept apart from the answers.
    @Published private(set) var tasteSeen: Set<String>
    /// The last good quiz definition in the app's language, from the cache: the row's summary and
    /// what is sent read it. Nil until one was fetched in this language.
    @Published private(set) var tasteQuiz: TasteQuiz?
    /// The definition the open quiz shows; never swapped while it is open.
    @Published private(set) var tasteQuizLoad: TasteQuizLoad = .loading
    /// The sheet that says what a taste profile does (no recipes in a pack, no saved profile).
    @Published var showsTastePromo = false
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
    private var tasteVersionChanges: AnyCancellable?
    private var allergenChanges: AnyCancellable?

    /// How many of the soonest items are ticked for the person (whatever their date, past it
    /// included). Android: `RECIPE_DEFAULT_TICKED`. The one place to change it on iOS.
    static let defaultTickCount = 3
    /// How many of the soonest items the tab lists before "Add other items"; ticked items are
    /// listed as well, wherever they fall. Android: `RECIPE_SHOWN_ITEMS`. The one place to
    /// change it on iOS.
    static let shownCount = 5

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
        taste = configStore.loadTaste()
        tasteSeen = configStore.loadTasteSeen()
        tasteQuiz = configStore.loadTasteQuiz(language: RecipeLanguage.app.rawValue)
        menuCourses = credits.storage.menuCourses
        menuDays = credits.storage.menuDays
        saved = self.savedStore.all()
        refreshAvailability()
        creditsChanges = credits.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        tasteVersionChanges = credits.$serverTasteQuizVersion.combineLatest(credits.$balance)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.prefetchTasteQuizIfNeeded() }
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

    /// The phone has its own model, but not for the chosen recipe language: Recipe settings says
    /// why "Generate on this device" isn't offered. Android: `RecipeUiState.onDeviceLanguageUnsupported`.
    var onDeviceLanguageUnsupported: Bool { onDeviceAvailability == .unsupportedLanguage }

    // MARK: Configuration

    func updateConfig(_ transform: (inout RecipeConfig) -> Void) {
        var updated = config
        transform(&updated)
        updated.normalize()
        guard updated != config else { return }
        let languageChanged = updated.language != config.language
        config = updated
        configStore.save(updated)
        if languageChanged { refreshAvailability() }
    }

    /// "Send all products" in Recipe settings.
    // MARK: Taste profile

    /// The quiz's definition is fetched in the app's own language (the server answers English for
    /// one it doesn't have).
    private var tasteLanguage: String { RecipeLanguage.app.rawValue }

    /// What the settings row does: no row without the paid route or a server that takes taste
    /// profiles; the quiz with recipes in a pack or a saved profile; otherwise the promo sheet.
    var tasteEntry: TasteProfile.Entry {
        TasteProfile.entry(
            paidRouteAvailable: credits.isPaidRouteAvailable,
            serverTakesTaste: credits.serverTakesTaste,
            purchasesBlocked: credits.purchasesBlocked,
            balance: credits.balance,
            hasSavedProfile: !taste.isEmpty
        )
    }

    /// Whether the next server request carries the profile: the server action's helper line
    /// then says "Uses your taste profile".
    var sendsTaste: Bool { tasteToSend != nil }

    private var tasteToSend: [String: [String]]? {
        TasteProfile.toSend(taste, quiz: tasteQuiz, serverTakesTaste: credits.serverTakesTaste)
    }

    /// The row tapped: the quiz (its definition from the cache at once, or fetched) or the promo.
    /// The promo calls nothing.
    func tasteRowTapped() {
        switch tasteEntry {
        case .quiz: openTasteQuiz()
        case .promo: showsTastePromo = true
        case .hidden: break
        }
    }

    func openTasteQuiz() {
        tasteQuiz = configStore.loadTasteQuiz(language: tasteLanguage)
        #if DEBUG
        if let demo = demoTasteQuiz { tasteQuiz = demo.failing ? nil : demo.quiz }
        #endif
        if let cached = tasteQuiz {
            tasteQuizLoad = .ready(cached)
            // Kept for the next open, never swapped under the person's fingers.
            Task { await fetchTasteQuiz(updatingOpenQuiz: false) }
        } else {
            tasteQuizLoad = .loading
            Task { await fetchTasteQuiz(updatingOpenQuiz: true) }
        }
        if path.last != .tasteQuiz { path.append(.tasteQuiz) }
    }

    /// "Retry" on the quiz's error state.
    func retryTasteQuiz() {
        tasteQuizLoad = .loading
        Task { await fetchTasteQuiz(updatingOpenQuiz: true) }
    }

    /// Asks the server for the definition. Only someone with an account on our server opens the
    /// quiz (recipes in a pack, or a profile saved then); without one nothing is created for it:
    /// the cached definition, or the error state. A definition that can't be used leaves the last
    /// good one in place.
    private func fetchTasteQuiz(updatingOpenQuiz: Bool) async {
        let language = tasteLanguage
        var fetched: TasteQuiz?
        #if DEBUG
        if let demo = demoTasteQuiz {
            fetched = demo.failing ? nil : demo.quiz
            try? await Task.sleep(for: .milliseconds(300))
        } else if credits.account.uid != nil, credits.serverTakesTaste {
            fetched = try? await credits.backend.tasteQuiz(language: language)
        }
        #else
        if credits.account.uid != nil, credits.serverTakesTaste {
            fetched = try? await credits.backend.tasteQuiz(language: language)
        }
        #endif
        var cached = configStore.loadTasteQuiz(language: language)
        if let fetched, fetched.isUsable {
            #if DEBUG
            // A demo's sample never goes into the real cache.
            if demoTasteQuiz == nil { configStore.saveTasteQuiz(fetched, language: language) }
            #else
            configStore.saveTasteQuiz(fetched, language: language)
            #endif
            tasteQuiz = fetched
            cached = fetched
        }
        guard updatingOpenQuiz else { return }
        if let quiz = TasteQuiz.afterFetch(fetched, cached: cached) {
            tasteQuizLoad = .ready(quiz)
        } else {
            tasteQuizLoad = .failed
        }
    }

    /// "See recipe packs" on the promo: the Buy sheet once the promo has gone.
    func tastePromoToPacks() {
        showsTastePromo = false
        guard credits.canBuy else { return }
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(450))
            self?.showsBuySheet = true
        }
    }

    /// The quiz's Save: the answers as edited against `quiz`; the steps `visited` in it count as
    /// seen. Nothing chosen is the same as clearing (seen steps stay).
    func saveTaste(_ draft: TasteProfile, quiz: TasteQuiz, visited: Set<String>) {
        let stored = draft.saved(from: quiz)
        setTaste(stored.isEmpty ? TasteProfile() : stored)
        setTasteSeen(TasteProfile.seenAfter(tasteSeen, visited: visited))
    }

    /// "Clear taste profile": gone at once; what it was comes back with `restoreTaste`. The steps
    /// been through stay so: clearing doesn't bring the dot back.
    @discardableResult
    func clearTaste() -> TasteProfile {
        let previous = taste
        setTaste(TasteProfile())
        return previous
    }

    func restoreTaste(_ profile: TasteProfile) { setTaste(profile) }

    /// Leaving the quiz without saving: the steps shown count as been through, with or without
    /// a profile; the answers stay as stored.
    func leaveTasteQuiz(seen visited: Set<String>) {
        setTasteSeen(TasteProfile.seenAfter(tasteSeen, visited: visited))
    }

    private func setTasteSeen(_ seen: Set<String>) {
        guard seen != tasteSeen else { return }
        configStore.saveTasteSeen(seen)
        tasteSeen = seen
    }

    /// The dot on the header's settings button and on the row without a profile.
    var hasNewTasteQuestions: Bool {
        TasteProfile.hasNewQuestions(entry: tasteEntry, seen: tasteSeen, quiz: tasteQuiz)
    }

    /// Versions already fetched in the background this run, by language.
    private var tastePrefetches = Set<String>()

    /// After a balance reply (and at launch, which is when iOS changes the app's language): fetch
    /// the definition quietly when the server's `tasteQuizVersion` isn't the cached one and the row
    /// opens the quiz, at most once per version and language. Silent on failure (a later reply
    /// tries again); never under an open quiz.
    private func prefetchTasteQuizIfNeeded() {
        let language = tasteLanguage
        let version = credits.serverTasteQuizVersion
        let key = "\(language):\(version ?? "")"
        guard TasteProfile.shouldPrefetch(
            serverVersion: version,
            cachedVersion: configStore.loadTasteQuiz(language: language)?.version,
            entry: tasteEntry,
            hasAccount: credits.account.uid != nil,
            alreadyFetched: tastePrefetches.contains(key)
        ) else { return }
        #if DEBUG
        if demoTasteQuiz != nil { return }
        #endif
        tastePrefetches.insert(key)
        Task { [weak self, credits] in
            guard let fetched = try? await credits.backend.tasteQuiz(language: language), fetched.isUsable else {
                self?.tastePrefetches.remove(key)
                return
            }
            guard let self else { return }
            self.configStore.saveTasteQuiz(fetched, language: language)
            // The row and the dot follow; an open quiz keeps its own definition (`tasteQuizLoad`).
            self.tasteQuiz = fetched
        }
    }

    private func setTaste(_ profile: TasteProfile) {
        configStore.saveTaste(profile)
        if profile != taste { taste = profile }
    }

    #if DEBUG
    func showTasteLoadingForDemo() {
        demoTasteQuiz = nil
        tasteQuizLoad = .loading
    }

    /// `-demoRoute tasteQuiz` / `tasteQuizError`: the quiz from a bundled sample definition, or
    /// its error state, with nothing fetched.
    private(set) var demoTasteQuiz: (quiz: TasteQuiz?, failing: Bool)?
    /// `-demoTasteStep N`: the quiz opens at step N (from 1) instead of where it would.
    var demoTasteStep: Int?

    /// Nothing is stored: the profile is this run's only.
    func showTasteDemo(quiz: TasteQuiz?, profile: TasteProfile, seen: Set<String>, failing: Bool) {
        demoTasteQuiz = (quiz, failing)
        taste = profile
        tasteSeen = seen
        if failing {
            tasteQuiz = nil
            tasteQuizLoad = .failed
        } else if let quiz {
            tasteQuiz = quiz
            tasteQuizLoad = .ready(quiz)
        }
    }
    #endif

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

    /// "See recipe packs": closed, then the pack sheet, once the closing sheet has gone (SwiftUI
    /// won't present a second sheet from the same view while the first is still dismissing).
    func introToPacks() {
        dismissIntro()
        guard credits.canBuy else { return }
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(450))
            self?.showsBuySheet = true
        }
    }

    // MARK: Free recipes for signing in

    /// The offer's action, wherever it shows: signed out, the sign-in sheet (after asking the
    /// number once more, so the sheet names the current one); signed in, the claim itself.
    func takeWelcomeOffer() {
        guard let hint = credits.welcomeHint else { return }
        if hint.needsSignIn {
            openWelcomeSignIn()
        } else {
            Task { await credits.claimWelcome() }
        }
    }

    /// Asks `getWelcomeOffer` again (at most 4 s), then opens the sign-in sheet with the number
    /// as it stands; at 0 the sheet is a plain sign-in that promises nothing.
    func openWelcomeSignIn() {
        guard !openingWelcomeSheet, welcomeSheet == nil else { return }
        openingWelcomeSheet = true
        let credits = credits
        Task { @MainActor [weak self] in
            let refresh = Task { await credits.refreshWelcomeOffer(beforeSignIn: true) }
            await withTaskGroup(of: Void.self) { group in
                group.addTask { await refresh.value }
                group.addTask { try? await Task.sleep(for: .seconds(4)) }
                await group.next()
                group.cancelAll()
            }
            guard let self else { return }
            self.openingWelcomeSheet = false
            let hint = credits.welcomeHint
            self.welcomeSheet = WelcomeSheetRequest(recipes: hint?.needsSignIn == true ? hint?.recipes ?? 0 : 0)
        }
    }

    /// "How recipes work"'s offer: closed (counts as closed for good), then the offer's action
    /// once the closing sheet has gone.
    func introToWelcome() {
        dismissIntro()
        afterSheetCloses { $0.takeWelcomeOffer() }
    }

    /// The Buy sheet's "Or sign in and get 3 free recipes": that sheet closes first.
    func buySheetToWelcome() {
        showsBuySheet = false
        afterSheetCloses { $0.takeWelcomeOffer() }
    }

    /// The "already had them" dialog, once a closing sheet has gone.
    func showWelcomeAlreadyAfterSheet() {
        afterSheetCloses { $0.credits.welcomeAlready = true }
    }

    /// "See recipe packs" from the "already had them" dialog.
    func welcomeToPacks() {
        credits.welcomeAlready = false
        guard credits.canBuy else { return }
        afterSheetCloses { $0.showsBuySheet = true }
    }

    private func afterSheetCloses(_ then: @escaping @MainActor (RecipeViewModel) -> Void) {
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(450))
            guard let self else { return }
            then(self)
        }
    }

    /// `-demoRecipeIntro YES`: shown as on a first open, to look at it.
    func showIntroForDemo() { intro = .firstOpen }

    /// Something is saved, so the header offers the saved list: the first recipe written shows
    /// it, deleting the last one hides it.
    var hasSaved: Bool { !saved.isEmpty }

    /// Reads the saved list again, for changes made outside this view model.
    func reloadSaved() {
        let current = savedStore.all()
        if current != saved { saved = current }
        pruneUnread()
    }

    // MARK: Unread saved recipes

    /// How many saved recipes and menus haven't been seen yet: the header's badge.
    var unreadCount: Int { RecipeUnread.count(options.unreadSaved, savedIDs: saved.map(\.id)) }

    func isUnread(_ id: String) -> Bool { options.unreadSaved.contains(id) }

    /// A result's screen appeared (on arrival, from Saved recipes or anywhere else): read.
    func markRead(_ id: String) {
        updateOptions { $0.unreadSaved = RecipeUnread.afterOpen($0.unreadSaved, id: id) }
    }

    private func pruneUnread() {
        let ids = saved.map(\.id)
        updateOptions { $0.unreadSaved = RecipeUnread.pruned($0.unreadSaved, savedIDs: ids) }
    }

    /// `RecipeView` appearing and disappearing, and the app becoming active or not.
    func setTabOnScreen(_ onScreen: Bool) { tabOnScreen = onScreen }
    func setSceneActive(_ active: Bool) { sceneActive = active }

    /// Whether a result arriving now would be seen: the writing screen is what is on screen.
    private var writingScreenOnScreen: Bool { tabOnScreen && sceneActive && path.isEmpty }

    /// Saves a result that just arrived. Shown on the writing screen when that is on screen (and
    /// so read); otherwise kept unread for the badge, and the snackbar says it is in Saved recipes.
    /// Returns whether it was shown.
    @discardableResult
    private func saveArrived(_ entry: SavedRecipe) -> Bool {
        let shown = writingScreenOnScreen
        savedStore.save(entry)
        saved = savedStore.all()
        updateOptions { $0.unreadSaved = RecipeUnread.afterSave($0.unreadSaved, id: entry.id, shown: shown) }
        if shown { path = [.result(id: entry.id)] }
        return shown
    }

    /// `items` past their date, soonest first, by how many days: the form's red warning, the
    /// shared prompt's line and what a recipe written on the phone carries. Android:
    /// `RecipeUiState.pastDateTicked`.
    static func pastDate(of items: [PantryItem]) -> [PastDateItem] {
        soonestFirst(items).filter { $0.daysRemaining < 0 }.map { PastDateItem(name: $0.name, daysOver: -$0.daysRemaining) }
    }

    /// The ticked items past their date, from `items` (the pantry).
    func pastDateTicked(from items: [PantryItem]) -> [PastDateItem] {
        Self.pastDate(of: selectedItems(from: items))
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
        #if DEBUG
        if let demoOnDevice { onDeviceAvailability = demoOnDevice ? .available : .unsupported }
        #endif
    }

    #if DEBUG
    /// `-demoRecipeOnDevice YES|NO`: the form as on a phone whose model writes the language, or
    /// one without a model, whatever this device has, for layout checks.
    var demoOnDevice: Bool? = nil {
        didSet { refreshAvailability() }
    }
    #endif

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

    /// `staples`: the person's staples (`NativePantryViewModel.stapleRows`); those not on the
    /// shopping list go to every route whatever "Send all products" says, less those it already lists.
    func request(for items: [PantryItem], staples: [StapleRow] = []) -> RecipeRequest {
        let ticked = selectedItems(from: items)
        let others = optionalItems(from: items)
        return RecipeRequest(
            items: ticked.map(RecipeIngredientInput.init),
            config: config,
            allergens: Array(SettingsStore.shared.settings.allergens),
            otherItems: others.map(RecipeIngredientInput.init),
            staples: stapleNames(for: items, staples: staples)
        )
    }

    /// The staples a route gets from `staples` (`Staples.promptStaples`): the form's "Your N
    /// staples may be used too" counts them. Android: `RecipeUiState.stapleNames`.
    func stapleNames(for items: [PantryItem], staples: [StapleRow]) -> [String] {
        Staples.promptStaples(staples, ticked: selectedItems(from: items).map(\.name), others: optionalItems(from: items).map(\.name))
    }

    func sharePrompt(for items: [PantryItem], staples: [StapleRow] = []) -> String {
        RecipePromptBuilder.sharePrompt(for: request(for: items, staples: staples))
    }

    func setMenuCourses(_ courses: Int) {
        menuCourses = MenuSet.courseChoices.contains(courses) ? courses : 2
        credits.storage.menuCourses = menuCourses
    }

    func setMenuDays(_ days: Int) {
        menuDays = MenuSet.dayChoices.contains(days) ? days : MenuSet.defaultDays
        credits.storage.menuDays = menuDays
    }

    /// The menu set the form asks for: the chosen days and courses where the server takes and
    /// prices them, otherwise the 3-day menu (`MenuSet.effective`).
    var menuSet: MenuSet {
        MenuSet.effective(days: menuDays, courses: menuCourses, takesMenuDays: credits.serverTakesMenuDays, costs: credits.costs)
    }

    /// The menu form's "few items for this menu" hint (`MenuSet.itemsHint`) for the menu set asked
    /// for, from `items`: the ticked ones, and with "Send all products" on and a server that reads
    /// them, the other items the request would carry (`RecipeWire.optionalIngredients`). Nil in
    /// single-recipe mode and with enough items. Android: `RecipeUiState.menuItemsHint`.
    func menuItemsHint(for items: [PantryItem]) -> MenuItemsHint? {
        guard credits.isPaidRouteAvailable, mode == .menu else { return nil }
        let ticked = selectedItems(from: items)
        var optional = 0
        if options.sendAllItems, credits.storage.serverTakesOptional {
            let wire = { (item: PantryItem) -> WireIngredient? in
                RecipeWire.cleanName(item.name).map { WireIngredient(name: $0, expiresOn: nil, quantity: nil) }
            }
            optional = RecipeWire.optionalIngredients(
                ticked: Array(ticked.compactMap(wire).prefix(RecipeWire.maxIngredients)),
                others: optionalItems(from: items).compactMap(wire)
            ).count
        }
        return menuSet.itemsHint(ticked: ticked.count, optional: optional)
    }

    /// The Days choices (empty: not shown) and the Courses choices for the form.
    var menuDayOptions: [Int] { MenuSet.dayOptions(for: menuSet, takesMenuDays: credits.serverTakesMenuDays, costs: credits.costs) }
    var menuCourseOptions: [Int] { MenuSet.courseOptions(for: menuSet, takesMenuDays: credits.serverTakesMenuDays, costs: credits.costs) }

    // MARK: Server route

    /// Whether the server writes the recipe: while there are recipes in the pack. Otherwise the
    /// free routes take the primary action.
    var serverCanWriteRecipe: Bool { serverCanWrite(.recipe) }
    var serverCanWriteMenu: Bool { serverCanWrite(.menu) }

    /// What a recipe, or the chosen menu set, takes, from the server's costs.
    var recipeCost: Int { credits.cost(of: .recipe, courses: 2) }

    /// The form's Pictures row and what a recipe from the server really carries
    /// (`RecipePictures.choice`): only while the server is the primary of a single recipe.
    var pictureChoice: RecipePictureChoice {
        RecipePictures.choice(
            chosen: options.pictureLevel,
            costs: credits.costs,
            serverTakes: credits.serverTakesRecipeImage,
            balance: credits.balance ?? 0,
            serverPrimary: mode == .recipe && actions.primary == .server
        )
    }

    /// What the server's Generate recipe takes: the picture level really sent's price.
    var serverRecipeCost: Int { credits.costs.picture(pictureChoice.sent) ?? recipeCost }

    /// A row in the Pictures sheet: remembered beside the other recipe options.
    func setPictureLevel(_ level: RecipePictureLevel) {
        updateOptions { $0.pictureLevel = level }
    }
    var menuCost: Int { credits.cost(of: .menu, courses: menuSet.courses, days: menuSet.days) }

    private func serverCanWrite(_ kind: RecipeKind) -> Bool {
        credits.isPaidRouteAvailable && !credits.serverPaused
            && (credits.balance ?? 0) >= (kind == .menu ? menuCost : recipeCost)
    }

    func writeOnServer(from items: [PantryItem], staples: [StapleRow] = []) {
        let kind: RecipeKind = mode == .menu ? .menu : .recipe
        let request = request(for: items, staples: staples)
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
        // The multi-choice config only to a server that said it reads it; otherwise the closest
        // legacy values, or nothing at all when a selected diet would be lost.
        guard let fresh = RecipeWire.request(
            for: request, kind: kind, courses: menuSet.courses, days: menuSet.days, requestID: UUID(), excluding: refusedNames,
            withOptional: options.sendAllItems && credits.storage.serverTakesOptional,
            multiChoice: credits.storage.serverTakesMultiChoice,
            menuDays: credits.serverTakesMenuDays,
            // The taste profile only to a server that reads it; otherwise the request goes without.
            taste: tasteToSend,
            // The staples only to a server that reads them; otherwise the request goes without.
            withStaples: credits.storage.serverTakesStaples,
            // Pictures only at the level the costs price and the pack covers, which the button shows.
            image: kind == .recipe ? pictureChoice.sent : .none
        ) else {
            failure = .dietsNeedServerUpdate
            return
        }
        // Danish only to a server that writes it.
        if fresh.config.language == RecipeLanguage.da.rawValue, !credits.storage.serverTakesDanish {
            failure = .languageNeedsServerUpdate
            return
        }
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
        writingMenuDays = wire.days ?? MenuSet.defaultDays
        writingPictures = wire.image
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
                    if let response { self.collectEarlier(earlier, response, await Self.arrivedPictures(response, wire: earlier)) }
                    collecting = false
                    credits.storage.pendingGeneration = wire
                }
                guard let self else { return }
                guard let response = try await self.settle(wire, start: start, mayStart: true) else {
                    self.stopServerWrite(with: .serverFailed(wire.kind, sameRequest: true))
                    return
                }
                self.finishServerWrite(wire, response, await Self.arrivedPictures(response, wire: wire))
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
        // A recipe with pictures takes up to ~110 s for the dish and ~160 s with steps when the
        // picture model is busy (server reservations 115 s / 165 s): asked after as long as a menu,
        // 270 s, well past either.
        let deadline = Date().addingTimeInterval(wire.kind == .menu || wire.image != nil ? 270 : 150)
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
    private func collectEarlier(_ earlier: GenerateRequest, _ response: GenerateResponse, _ arrived: ArrivedPictures) {
        credits.apply(response)
        credits.callSucceeded()
        switch Self.outcome(of: response, for: earlier, costs: credits.costs) {
        case .saved(let content):
            // Never shown: unread until opened; its picture lines wait for that, in this run.
            let (entry, notice) = Self.savedEntry(content, arrived: arrived, response: response, wire: earlier)
            cachePictures(entry)
            if let notice { pictureNotices[entry.id] = notice }
            savedStore.save(entry)
            saved = savedStore.all()
            updateOptions { $0.unreadSaved = RecipeUnread.afterSave($0.unreadSaved, id: entry.id, shown: false) }
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

    private func finishServerWrite(_ wire: GenerateRequest, _ response: GenerateResponse, _ arrived: ArrivedPictures) {
        writeTask = nil
        phase = .idle
        serverBusyRetrying = false
        writingPictures = nil
        credits.apply(response)
        credits.callSucceeded()
        let outcome = Self.outcome(of: response, for: wire, costs: credits.costs)
        if outcome.clearsRequest { credits.storage.pendingGeneration = nil }
        switch outcome {
        case .saved(let content):
            let (entry, notice) = Self.savedEntry(content, arrived: arrived, response: response, wire: wire)
            cachePictures(entry)
            // The picture lines only on the result shown as it arrives (kept while it is open).
            if let notice { pictureNotices[entry.id] = notice }
            // Shown if the writing screen is on screen; otherwise unread, and said to be saved.
            if saveArrived(entry) {
                ReviewPrompter.shared.recipeArrived()
                failure = .arrived(balance: response.balance)
            } else {
                // Kept for when it is opened from Saved recipes in this run, as on Android.
                failure = .earlierArrived(wire.kind)
            }
        case .failure(let failure):
            self.failure = failure
        }
    }

    /// A reply's pictures, checked (`RecipePictures.accepted` / `forSteps`) and with the dish's
    /// thumbnail drawn, off the main actor. Nothing for a request without pictures.
    struct ArrivedPictures: Sendable {
        var dish: RecipePictureBytes?
        var steps: [RecipeStepPictureBytes]
        var thumbnail: Data?

        static let none = ArrivedPictures(dish: nil, steps: [], thumbnail: nil)
    }

    nonisolated static func arrivedPictures(_ response: GenerateResponse, wire: GenerateRequest) async -> ArrivedPictures {
        guard wire.kind == .recipe, let level = wire.image, response.status == .done, let recipe = response.recipe else { return .none }
        let dish = response.image?.value
        let steps = level == .steps ? RecipePictures.forSteps(response.stepImages?.value ?? [], stepCount: recipe.steps.count) : []
        let thumbnail = await Task.detached(priority: .userInitiated) { dish.flatMap { RecipePictureThumbnail.make(from: $0.data) } }.value
        return ArrivedPictures(dish: dish, steps: steps, thumbnail: thumbnail)
    }

    /// The saved entry for a server result, with its pictures (sizes in the payload, bytes beside
    /// it) and the picture lines it arrived with (`RecipePictures.notice`).
    static func savedEntry(_ content: SavedRecipe.Content, arrived: ArrivedPictures, response: GenerateResponse, wire: GenerateRequest) -> (entry: SavedRecipe, notice: RecipePictureNotice?) {
        var content = content
        var pictures: SavedRecipePictures?
        if case .recipe(var recipe) = content, wire.image != nil {
            recipe.picture = arrived.dish.map { RecipePictureSize(width: $0.width, height: $0.height) }
            recipe.stepPictures = arrived.steps.map { RecipeStepPictureSize(step: $0.step, width: $0.picture.width, height: $0.picture.height) }
            content = .recipe(recipe)
            let kept = SavedRecipePictures(
                dish: arrived.dish?.data,
                steps: Dictionary(arrived.steps.map { ($0.step, $0.picture.data) }, uniquingKeysWith: { first, _ in first })
            )
            pictures = kept.isEmpty ? nil : kept
        }
        let notice = RecipePictures.notice(
            asked: wire.kind == .recipe ? wire.image : nil,
            imageFailed: response.imageFailed?.value ?? false,
            stepImagesFailed: response.stepImagesFailed?.value ?? false,
            dishKept: arrived.dish != nil
        )
        return (SavedRecipe(content: content, thumbnail: arrived.thumbnail, pictures: pictures), notice)
    }

    private func cachePictures(_ entry: SavedRecipe) {
        guard let pictures = entry.pictures else { return }
        pictureCache[entry.id] = pictures
        pictureCacheOrder.removeAll { $0 == entry.id }
        pictureCacheOrder.append(entry.id)
        while pictureCacheOrder.count > 3 {
            pictureCache[pictureCacheOrder.removeFirst()] = nil
        }
    }

    /// The pictures kept with a saved recipe: from this run's arrivals, or read off the main actor.
    func pictures(for id: String) async -> SavedRecipePictures? {
        if let cached = pictureCache[id] { return cached }
        return await savedStore.loadPictures(id: id)
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
        // The multi-choice config refused as invalid: the server was rolled back to one that
        // reads only the legacy config. The next try sends the legacy values (or says it can't);
        // the next answer with a balance says again what the server reads.
        if serverError == .invalidRequest, wire.config.isMultiChoice { credits.storage.serverTakesMultiChoice = false }
        // Likewise Danish refused as invalid: a server rolled back to one from before it.
        if serverError == .invalidRequest, wire.config.language == RecipeLanguage.da.rawValue { credits.storage.serverTakesDanish = false }
        // And a menu set (days, or one course a day): back to the 3-day menu of two or three.
        if serverError == .invalidRequest, MenuSet.needsMenuDays(wire) { credits.setServerTakesMenuDays(false) }
        // And a taste profile: the next request goes without it.
        if TasteProfile.clearsFlag(after: serverError, request: wire) { credits.storage.serverTakesTaste = false }
        // And staples: the next request goes without them, and the balance says again what the server reads.
        if RecipeWire.clearsStaplesFlag(after: serverError, request: wire) {
            credits.storage.serverTakesStaples = false
            let credits = credits
            Task { await credits.refreshBalance() }
        }
        // And pictures: the next request goes without them until a reply says acceptsRecipeImage.
        if RecipeWire.clearsPictureFlag(after: serverError, request: wire) {
            credits.setServerTakesRecipeImage(false)
            let credits = credits
            Task { await credits.refreshBalance() }
        }
        // The level isn't made now: the table is asked for again, which withdraws it.
        if serverError == .imageUnavailable {
            let credits = credits
            Task { await credits.refreshBalance() }
        }
        if serverError == .insufficientCredits {
            if credits.canBuy { showsBuySheet = true }
            return
        }
        let kind = kind ?? wire.kind
        guard var failure = Self.failure(
            for: serverError, kind: kind, takenIfCharged: credits.costs.cost(of: wire), days: wire.days ?? MenuSet.defaultDays
        ) else { return }
        if retriesFresh, case .serverFailed = failure { failure = .serverFailed(kind, sameRequest: false) }
        self.failure = failure
    }

    /// The snackbar a failed `generateRecipe` call raises, or nil where a lasting condition's
    /// callout (or the pack sheet, for INSUFFICIENT_CREDITS) says it instead. `takenIfCharged` is
    /// what the request takes, for an unknown refusal that says it was charged.
    /// `days`: the menu's day count, for NOT_ENOUGH_ITEMS' wording.
    static func failure(for error: RecipeServerError, kind: RecipeKind, takenIfCharged: Int, days: Int = MenuSet.defaultDays) -> RecipeFailure? {
        switch error {
        case .insufficientCredits, .offline, .generationDisabled, .accountClosed:
            return nil
        case .busy: return .busy
        case .rateLimited: return .rateLimited
        case .userDailyLimit: return .userDailyLimit
        case .infraFailureLimit: return .infraFailureLimit
        case .dailyCap: return .dailyCap
        case .notEnoughItems: return .notEnoughItems(kind, days: kind == .menu ? days : MenuSet.defaultDays)
        case .imageUnavailable: return .picturesUnavailable
        case .server, .appCheckReplayed, .unknownRequest, .callableMissing, .serversBusy:
            // Nothing was decided on the server for this attempt: ask again under the same id.
            return .serverFailed(kind, sameRequest: true)
        case .requestIdReused, .unauthenticated, .invalidRequest, .recentSignInRequired, .mergeNotAllowed,
             .storeMismatch, .purchaseAccountMismatch, .refusedName, .allergenConsentRequired, .signInRequired, .reportLimit:
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
            // The past-date warning comes from what was sent and its dates on the day it was
            // asked for, never from the model's text: exactly the items a dish uses, by its
            // ingredients' item indices; without them (an older server) every ticked one.
            let tickedPast = RecipeWire.pastDate(of: wire.ingredients, today: wire.today)
            let optional = wire.optionalIngredients ?? []
            // Which ingredient is which staple, by its index into the staples as sent.
            let staples = wire.staples ?? []
            switch kind {
            case .recipe:
                // Nothing local comes from a reply: the request id is the one sent, pictures are added
                // by the app (`savedEntry`).
                if var recipe = response.recipe.map({ $0.withoutLocalFields() }).map(RecipeWire.markedServer).map({ $0.withStapleNames(staples) }) {
                    recipe.requestId = wire.requestId
                    recipe.pastDate = recipe.hasItemIndices
                        ? RecipeWire.pastDate(of: recipe, ticked: wire.ingredients, optional: optional, today: wire.today)
                        : tickedPast
                    return .saved(.recipe(recipe))
                }
            case .menu:
                if var menu = response.menu?.withStapleNames(staples), !menu.days.isEmpty {
                    for day in menu.days.indices {
                        for course in menu.days[day].courses.indices {
                            menu.days[day].courses[course].recipe = menu.days[day].courses[course].recipe.withoutLocalFields()
                            menu.days[day].courses[course].recipe.source = .server
                        }
                    }
                    menu.requestId = wire.requestId
                    if let exact = menu.withPastDateByIndex(ticked: wire.ingredients, optional: optional, today: wire.today) {
                        return .saved(.menu(exact))
                    }
                    let optionalPast = RecipeWire.pastDate(of: optional, today: wire.today)
                    return .saved(.menu(menu.withPastDate(ticked: tickedPast, optional: optionalPast)))
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

    func writeOnDevice(from items: [PantryItem], staples: [StapleRow] = []) {
        let request = request(for: items, staples: staples)
        guard !request.items.isEmpty, phase == .idle else { return }
        phase = .writingOnDevice
        let model = onDeviceModel
        // From the ticked items' own dates, never from the model's text.
        let pastDate = Self.pastDate(of: selectedItems(from: items))
        writeTask = Task { [weak self] in
            do {
                var recipe = try await model.write(request)
                recipe.pastDate = pastDate
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
        phase = .idle
        if saveArrived(SavedRecipe(content: .recipe(recipe))) {
            ReviewPrompter.shared.recipeArrived()
        } else {
            failure = .earlierArrived(.recipe)
        }
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

    /// Saved recipes' tabs now (`SavedList.tabs`): empty while only All would show.
    var savedTabs: [SavedTab] { SavedList.tabs(saved) }

    /// The rows `tab` lists, new ones first, then favourites, then the rest (`SavedList`).
    func savedRows(tab: SavedTab) -> [SavedRecipe] {
        SavedList.rows(SavedList.ordered(saved, unread: options.unreadSaved), tab: tab)
    }

    /// Marks a saved recipe or menu a favourite, or not.
    func setFavorite(id: String, _ isFavorite: Bool) {
        guard savedRecipe(id: id)?.isFavorite != isFavorite else { return }
        savedStore.setFavorite(id: id, isFavorite)
        saved = savedStore.all()
    }

    func savedRecipe(id: String) -> SavedRecipe? {
        saved.first { $0.id == id }
    }

    /// Returns what Undo puts back, its pictures included.
    func deleteSaved(id: String) -> SavedRecipe? {
        guard var entry = savedRecipe(id: id) else { return nil }
        entry.pictures = pictureCache[id] ?? savedStore.pictures(id: id)
        savedStore.delete(id: id)
        saved = savedStore.all()
        updateOptions { $0.unreadSaved = RecipeUnread.afterOpen($0.unreadSaved, id: id) }
        return entry
    }

    func restoreSaved(_ entry: SavedRecipe) {
        savedStore.save(entry)
        saved = savedStore.all()
    }

    /// Saved recipes' multi-select: deletes the saved recipes and menus among `ids` with one store
    /// call, like `deleteSaved(id:)` taking them out of the unread ones. Returns what one Undo
    /// puts back (`restoreSaved(_:)`); empty when none of them is saved. Android:
    /// `RecipeStateHolder.deleteSaved(ids)`.
    func deleteSaved(ids: Set<String>) -> [SavedRecipe] {
        let removed = SavedSelection.toDelete(saved, selected: ids).map { entry -> SavedRecipe in
            // Read before they go, so one Undo brings the pictures back too.
            var entry = entry
            entry.pictures = pictureCache[entry.id] ?? savedStore.pictures(id: entry.id)
            return entry
        }
        guard !removed.isEmpty else { return [] }
        let removedIDs = Set(removed.map(\.id))
        savedStore.delete(ids: removedIDs)
        saved = savedStore.all()
        updateOptions { $0.unreadSaved = RecipeUnread.afterDelete($0.unreadSaved, ids: removedIDs) }
        return removed
    }

    /// Undo of the multi-select Delete: everything it removed back at once.
    func restoreSaved(_ entries: [SavedRecipe]) {
        guard !entries.isEmpty else { return }
        savedStore.save(entries)
        saved = savedStore.all()
    }

    // MARK: Report this recipe

    /// What a saved result's foot shows (`RecipeReporting.foot`): never on the phone's recipes,
    /// rows saved before request ids were kept, installs without the paid route or without an
    /// account (none is made for it), or before a reply said `acceptsReports`.
    func reportFoot(for entry: SavedRecipe) -> RecipeReportFoot {
        RecipeReporting.foot(
            source: entry.source,
            requestId: entry.requestId,
            available: credits.isPaidRouteAvailable,
            hasAccount: credits.account.uid != nil,
            serverTakesReports: credits.serverTakesReports,
            reported: options.reportedRequests
        )
    }

    /// "Report this recipe / menu": the sheet, sending nothing yet. `isMenu`: the menu as a whole
    /// (a dish opened from it reports as a recipe, with the menu's request id).
    func openReport(for entry: SavedRecipe, isMenu: Bool) {
        guard reportFoot(for: entry) == .available, let requestId = entry.requestId else { return }
        reportSheet = RecipeReportSheet(
            requestId: requestId,
            isMenu: isMenu,
            hasPictures: !isMenu && (entry.recipe?.hasPictures ?? false),
            reportId: UUID().uuidString.lowercased()
        )
    }

    func chooseReportReason(_ reason: RecipeReportReason) {
        guard var sheet = reportSheet, sheet.status != .sending, sheet.reasons.contains(reason) else { return }
        sheet.reason = reason
        sheet.reasonMissing = false
        reportSheet = sheet
    }

    func setReportNote(_ note: String) {
        guard var sheet = reportSheet, sheet.status != .sending else { return }
        let cut = RecipeReporting.cutNote(note)
        guard cut != sheet.note else { return }
        sheet.note = cut
        reportSheet = sheet
    }

    /// Cancel, or the sheet swiped away. A report already on its way still lands.
    func dismissReport() {
        reportSheet = nil
    }

    /// Send report, and its Try again: the same report id every time, so the server stores it
    /// once. Without a reason, "Choose a reason." and nothing is sent. Never makes an account.
    func sendReport() {
        guard var sheet = reportSheet, sheet.status != .sending, sheet.status != .limit, sheet.status != .unknown else { return }
        guard let reason = sheet.reason else {
            sheet.reasonMissing = true
            reportSheet = sheet
            return
        }
        #if DEBUG
        // A demo shows the states; nothing is ever sent from one.
        if credits.demoMode { return }
        #endif
        guard credits.account.uid != nil else { return }
        sheet.status = .sending
        sheet.reasonMissing = false
        reportSheet = sheet
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        let request = ReportRecipeRequest(
            reportId: sheet.reportId,
            requestId: sheet.requestId,
            reason: reason.rawValue,
            note: RecipeReporting.noteToSend(sheet.note),
            app: .init(version: RecipeReporting.wireVersion(version), language: RecipeLanguage.app.rawValue)
        )
        let backend = credits.backend
        let reportId = sheet.reportId
        let requestId = sheet.requestId
        Task { [weak self] in
            do {
                try await backend.reportRecipe(request)
                self?.reportReceived(reportId: reportId, requestId: requestId)
            } catch {
                self?.reportFailed(reportId: reportId, error)
            }
        }
    }

    private func reportReceived(reportId: String, requestId: String) {
        updateOptions { $0.reportedRequests = RecipeReporting.remembering($0.reportedRequests, requestId) }
        if reportSheet?.reportId == reportId { reportSheet = nil }
        // Owner, 2026-11-17: a sent report is a problem moment for the rating prompt.
        ReviewPrompter.shared.reportSent()
        failure = .reportSent
    }

    private func reportFailed(reportId: String, _ error: Error) {
        let serverError = error as? RecipeServerError ?? .server
        if serverError == .accountClosed { credits.note(serverError) }
        guard var sheet = reportSheet, sheet.reportId == reportId else { return }
        switch serverError {
        case .reportLimit: sheet.status = .limit
        // Not a result the server can take a report on: said plainly, never offered again.
        case .unknownRequest: sheet.status = .unknown
        default: sheet.status = .failed
        }
        reportSheet = sheet
    }

    #if DEBUG
    /// `-demoReport sheet|noReason|sending|failed|limit|unknown|sent` on a demo result: the report sheet in
    /// that state (`sent`: the foot reads "You've reported this"). Nothing is sent.
    func showReportDemo(_ state: String, entryID: String, isMenu: Bool) {
        guard let entry = savedRecipe(id: entryID), let requestId = entry.requestId else { return }
        if state == "sent" {
            updateOptions { $0.reportedRequests = RecipeReporting.remembering($0.reportedRequests, requestId) }
            return
        }
        openReport(for: entry, isMenu: isMenu)
        guard var sheet = reportSheet else { return }
        switch state {
        case "noReason": sheet.reasonMissing = true
        case "sending": sheet.reason = .unsafe; sheet.status = .sending
        case "failed": sheet.reason = .unsafe; sheet.note = "The chicken is only cooked for 3 minutes."; sheet.status = .failed
        case "limit": sheet.reason = .other; sheet.status = .limit
        case "unknown": sheet.reason = .other; sheet.status = .unknown
        default: sheet.reason = sheet.hasPictures ? .picture : .unsafe
        }
        reportSheet = sheet
    }

    /// `-demoRecipeState writingDish|writingSteps`: the writing screen of a recipe with pictures.
    func showDemoWritingPictures(_ level: RecipePictureLevel) {
        phase = .writingOnServer(.recipe)
        writingPictures = level
    }

    /// `-demoRoute recipeResult` with `-demoRecipePictures dish|steps|failed|stepsFailed`: a
    /// recipe from a pack with generated sample pictures (nothing fetched or sent), saved, and for
    /// the failed ones the lines a result arriving like that shows. Returns its id.
    func saveDemoPictureRecipe(_ kind: String, given: Recipe? = nil) -> String {
        var recipe = given ?? Recipe(
            title: "Crispy salmon with spinach and yoghurt sauce",
            summary: "Uses the salmon, spinach and yoghurt before the weekend.",
            minutes: 25,
            servings: config.servings,
            difficulty: .medium,
            ingredients: [
                RecipeIngredient(amount: "2", name: "Salmon fillet", fromPantry: true),
                RecipeIngredient(amount: "100 g", name: "Baby spinach", fromPantry: true),
                RecipeIngredient(amount: "150 g", name: "Greek yoghurt", fromPantry: true),
                RecipeIngredient(amount: "1", name: "Lemon", fromPantry: false),
            ],
            steps: [
                "Pat the salmon dry and season the skin with salt.",
                "Lay it skin side down in a hot oiled pan and press it flat for the first 30 seconds, then leave it until the skin is deep golden and the flesh has turned pale two thirds of the way up.",
                "Turn it over for a minute, then rest it on a plate.",
                "Wilt the spinach in the same pan until it has just collapsed but is still bright green.",
                "Stir lemon juice into the yoghurt and serve it beside the salmon and spinach.",
            ],
            tip: "A dry skin is what makes it crisp.",
            source: .server
        )
        recipe.source = .server
        recipe.requestId = UUID().uuidString.lowercased()
        let dish = kind == "failed" ? nil : RecipePictureSample.make(seed: 0)
        let stepIndices = kind == "steps" || kind == "stepsFailed" || kind == "failed" ? (kind == "stepsFailed" ? [1] : [1, 3]) : []
        let steps = stepIndices.compactMap { index in RecipePictureSample.make(seed: index).map { (index, $0) } }
        recipe.picture = dish.map { _ in RecipePictureSize(width: RecipePictureSample.width, height: RecipePictureSample.height) }
        recipe.stepPictures = steps.map { RecipeStepPictureSize(step: $0.0, width: RecipePictureSample.width, height: RecipePictureSample.height) }
        let pictures = SavedRecipePictures(dish: dish, steps: Dictionary(uniqueKeysWithValues: steps))
        let entry = SavedRecipe(content: .recipe(recipe), thumbnail: dish.flatMap(RecipePictureThumbnail.make(from:)), pictures: pictures.isEmpty ? nil : pictures)
        savedStore.save(entry)
        cachePictures(entry)
        saved = savedStore.all()
        return entry.id
    }

    /// The lines of a result that arrived with fewer pictures (`-demoRecipePictures failed|stepsFailed`).
    func showDemoPictureNotice(_ kind: String, entryID: String) {
        switch kind {
        case "failed": pictureNotices[entryID] = RecipePictureNotice(dishFailed: true, stepsFailed: false)
        case "stepsFailed": pictureNotices[entryID] = RecipePictureNotice(dishFailed: false, stepsFailed: true)
        default: break
        }
    }

    /// `-demoRecipeState busy`: the writing screen while every server instance is busy and the
    /// request is sent again. Nothing is sent.
    func showDemoServerBusy() {
        phase = .writingOnServer(mode == .menu ? .menu : .recipe)
        writingMenuDays = menuSet.days
        serverBusyRetrying = true
    }

    /// `-demoRecipeUnread YES`: a recipe and a menu saved as if they arrived while the writing
    /// screen was away, so the header's badge and the Saved list's dots show.
    func showDemoUnread() {
        let ids = [saveDemoRecipe(), saveDemoMenu()]
        updateOptions { $0.unreadSaved.formUnion(ids) }
    }

    /// `-demoSavedTabs YES`: Saved recipes with its tabs and favourites to look at: recipes for
    /// breakfast and dinner, one from before meals came, a menu, a favourite dinner and an unread
    /// breakfast, the newest saved last.
    func showDemoSavedTabs() {
        func recipe(_ title: String, _ minutes: Int, _ meal: RecipeMealType?) -> Recipe {
            var recipe = Recipe(
                title: title,
                summary: "",
                minutes: minutes,
                servings: config.servings,
                difficulty: .easy,
                ingredients: [RecipeIngredient(amount: "100 g", name: "Baby spinach", fromPantry: true)],
                steps: ["Prepare and serve."],
                source: .server
            )
            recipe.meal = meal
            return recipe
        }
        let now = Date()
        let rows: [(Recipe, Bool)] = [
            (recipe("Leftover vegetable frittata", 25, nil), false),
            (recipe("Creamy salmon pasta", 30, .dinner), true),
            (recipe("Chicken and rice traybake", 45, .dinner), false),
            (recipe("Overnight oats with berries", 5, .breakfast), false),
        ]
        var ids: [String] = []
        for (offset, row) in rows.enumerated() {
            let entry = SavedRecipe(createdAt: now.addingTimeInterval(Double(offset - rows.count) * 3600), content: .recipe(row.0), isFavorite: row.1)
            savedStore.save(entry)
            ids.append(entry.id)
        }
        _ = saveDemoMenu()
        saved = savedStore.all()
        if let newest = ids.last { updateOptions { $0.unreadSaved.insert(newest) } }
    }

    /// `-demoRoute recipeResult`: a recipe as the phone's model would write one, saved so the
    /// result and Saved recipes screens can be seen where the model can't run, as in the
    /// simulator. `given` is the screenshots' own recipe (`-demoRecipe <file>`), in the pantry's
    /// language. Returns its id.
    func saveDemoRecipe(_ given: Recipe? = nil) -> String {
        let recipe = given ?? Recipe(
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

    /// `-demoRoute recipeMenu`: a menu as the server would write one, of the form's days and
    /// courses (`-demoRecipeDays`, `-demoRecipeCourses`), saved; `given` as for `saveDemoRecipe`.
    func saveDemoMenu(_ given: RecipeMenu? = nil) -> String {
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
        let set = menuSet
        let days = (0..<set.days).map { offset -> RecipeMenuDay in
            let date = calendar.date(byAdding: .day, value: offset, to: Date()) ?? Date()
            let o = offset % 4
            let starter = RecipeMenuCourse(course: .starter, recipe: dish(["Spinach and yoghurt soup", "Cucumber and yoghurt salad", "Salmon rillettes on toast", "Tomato bruschetta"][o], 20))
            let main = RecipeMenuCourse(course: .main, recipe: dish(["Eggs on garlic sourdough toast", "Creamy milk-poached salmon", "Pan-fried salmon with greens", "Cheese omelette"][o], 25))
            let dessert = RecipeMenuCourse(course: .dessert, recipe: dish(["Yoghurt with honey and toasted crumbs", "Vanilla milk pudding", "Strawberries with sweet cream", "Baked apples"][o], 10))
            return RecipeMenuDay(
                date: RecipeWire.isoDay(date),
                uses: [["bread", "spinach", "yoghurt"], ["yoghurt", "milk"], ["salmon", "strawberries"], ["eggs", "cheese"]][o],
                courses: set.courses == 1 ? [main] : set.courses == 2 ? [starter, main] : [starter, main, dessert]
            )
        }
        var menu = given ?? RecipeMenu(days: days)
        // As from our server, so its foot offers the report in a demo.
        menu.requestId = menu.requestId ?? UUID().uuidString.lowercased()
        let entry = SavedRecipe(content: .menu(menu))
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
                // Lower-cased names, as Android's `sortName`, so both tick the same three.
                : $0.name.lowercased() < $1.name.lowercased()
        }
    }

    /// The three soonest items.
    static func defaultSelection(_ items: [PantryItem]) -> [String] {
        soonestFirst(items).prefix(defaultTickCount).map(\.id)
    }

    /// The rows on the tab: the five soonest, and anything else that is ticked, soonest first.
    /// As Android's `RecipeUiState.shownRows`: a large pantry, or one with many items long past
    /// their date, keeps the form short; the rest is behind "Add other items".
    static func shownItems(_ items: [PantryItem], selected: Set<String>) -> [PantryItem] {
        soonestFirst(items).enumerated()
            .filter { index, item in index < shownCount || selected.contains(item.id) }
            .map(\.element)
    }

    /// "Add other items" lists what the tab doesn't.
    static func otherItems(_ items: [PantryItem], selected: Set<String>) -> [PantryItem] {
        let shown = Set(shownItems(items, selected: selected).map(\.id))
        return soonestFirst(items.filter { !shown.contains($0.id) })
    }
}
