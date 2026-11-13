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
    /// Failed and counted: an input-caused failure past the day's free ones.
    case serverRefused(RecipeKind)
    case busy
    case rateLimited
    case dailyCap
    /// A recipe or menu arrived; the count after it.
    case arrived(balance: Int)
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
    @Published var failure: RecipeFailure?
    @Published var mode: RecipeMode = .recipe
    @Published private(set) var menuCourses: Int
    /// Opens the pack sheet: Buy recipes, the count in the bar, or a generation that found the
    /// pack empty.
    @Published var showsBuySheet = false

    /// Whether the person has changed the ticks since they were picked for them. Until then the
    /// soonest items follow the pantry as it changes.
    private(set) var selectionIsCustom = false

    private let configStore: RecipeConfigStore
    private let savedStore: SavedRecipeStore
    private let onDeviceModel: OnDeviceRecipeModel
    let credits: RecipeCredits
    private var writeTask: Task<Void, Never>?
    private var creditsChanges: AnyCancellable?

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
        menuCourses = credits.storage.menuCourses
        saved = self.savedStore.all()
        refreshAvailability()
        creditsChanges = credits.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
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
            allergens: Array(SettingsStore.shared.settings.allergens)
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

    private func serverCanWrite(_ kind: RecipeKind) -> Bool {
        credits.isPaidRouteAvailable && !credits.serverPaused && (credits.balance ?? 0) >= kind.cost
    }

    func writeOnServer(from items: [PantryItem]) {
        let kind: RecipeKind = mode == .menu ? .menu : .recipe
        let request = request(for: items)
        guard !request.items.isEmpty, phase == .idle else { return }
        let wire = RecipeWire.request(for: request, kind: kind, courses: menuCourses, requestID: UUID())
        guard !wire.ingredients.isEmpty else {
            // Every ticked name was made of characters the server refuses.
            failure = .serverFailed(kind, sameRequest: false)
            return
        }
        credits.storage.pendingGeneration = wire
        runServerWrite(wire)
    }

    /// Sends the interrupted request again under its own id: "Try again" after a lost
    /// connection or a server error, and at launch.
    func resumeServerWrite() {
        guard phase == .idle, let wire = credits.storage.pendingGeneration else { return }
        runServerWrite(wire)
    }

    var hasInterruptedServerWrite: Bool { credits.storage.pendingGeneration != nil }

    private func runServerWrite(_ wire: GenerateRequest) {
        phase = .writingOnServer(wire.kind)
        let backend = credits.backend
        let credits = credits
        writeTask = Task { [weak self] in
            // IN_PROGRESS is asked again every 3 s, up to 150 s for a recipe and 270 s for a
            // menu (as Android); past that it is a failure retried under the same id.
            let deadline = Date().addingTimeInterval(wire.kind == .menu ? 270 : 150)
            do {
                try await credits.ensureUser()
                while true {
                    let response = try await backend.generate(wire)
                    if response.status == .inProgress, Date() < deadline {
                        credits.apply(balance: response.balance)
                        try await Task.sleep(for: .seconds(3))
                        continue
                    }
                    self?.finishServerWrite(wire, response)
                    return
                }
            } catch is CancellationError {
                self?.phase = .idle
            } catch {
                self?.failServerWrite(wire, error)
            }
        }
    }

    private func finishServerWrite(_ wire: GenerateRequest, _ response: GenerateResponse) {
        writeTask = nil
        phase = .idle
        credits.apply(balance: response.balance)
        credits.callSucceeded()
        let outcome = Self.outcome(of: response, kind: wire.kind)
        if outcome.clearsRequest { credits.storage.pendingGeneration = nil }
        switch outcome {
        case .saved(let content):
            let entry = SavedRecipe(content: content)
            savedStore.save(entry)
            saved = savedStore.all()
            path = [.result(id: entry.id)]
            failure = .arrived(balance: response.balance)
        case .failure(let failure):
            self.failure = failure
        }
    }

    private func failServerWrite(_ wire: GenerateRequest, _ error: Error) {
        writeTask = nil
        phase = .idle
        credits.note(error)
        let serverError = error as? RecipeServerError ?? .server
        if !serverError.isRetryableWithSameID { credits.storage.pendingGeneration = nil }
        switch serverError {
        case .insufficientCredits:
            if credits.canBuy { showsBuySheet = true }
        case .busy:
            failure = .busy
        case .rateLimited:
            failure = .rateLimited
        case .dailyCap:
            failure = .dailyCap
        case .offline, .generationDisabled, .accountClosed:
            // Lasting conditions: the tab's callouts say so.
            break
        case .server:
            failure = .serverFailed(wire.kind, sameRequest: true)
        case .unauthenticated, .invalidRequest, .recentSignInRequired, .mergeNotAllowed:
            failure = .serverFailed(wire.kind, sameRequest: false)
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
    static func outcome(of response: GenerateResponse, kind: RecipeKind) -> ServerOutcome {
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
            return .failure(response.charged ? .serverRefused(kind) : .serverFailed(kind, sameRequest: false))
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
