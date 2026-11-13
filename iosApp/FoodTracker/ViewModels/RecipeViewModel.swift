import Foundation
import SwiftUI

/// The routes the Recipe tab offers right now, worked out from what this phone and this build
/// have. Only the free ones exist until the paid step adds the server.
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
}

/// What just went wrong, for one snackbar.
enum RecipeFailure: Equatable {
    case onDeviceFailed
    case onDeviceRefused
}

/// The screens pushed from the Recipe tab.
enum RecipeDestination: Hashable {
    case result(id: String)
    case saved
    case settings
}

/// The Recipe tab's state: the configuration, which items are ticked, the routes, the one write
/// in flight, and saved recipes. Separate from `NativePantryViewModel`, which it only reads
/// items from, and built on the protocols in `RecipeServices.swift` so the paid step can plug
/// its backend and credits in.
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

    /// Whether the person has changed the ticks since they were picked for them. Until then the
    /// soonest items follow the pantry as it changes.
    private(set) var selectionIsCustom = false

    private let configStore: RecipeConfigStore
    private let savedStore: SavedRecipeStore
    private let onDeviceModel: OnDeviceRecipeModel
    let backend: RecipeBackend
    let credits: CreditsGateway
    private var writeTask: Task<Void, Never>?

    /// How many of the soonest items are ticked for the person.
    static let defaultTickCount = 3
    /// The tab always lists at least this many items, more when more are near their date.
    static let minimumShownCount = 5

    init(
        configStore: RecipeConfigStore = UserDefaultsRecipeConfigStore(),
        savedStore: SavedRecipeStore? = nil,
        onDeviceModel: OnDeviceRecipeModel = FoundationModelsRecipeModel(),
        backend: RecipeBackend = UnavailableRecipeBackend(),
        credits: CreditsGateway? = nil
    ) {
        self.configStore = configStore
        self.savedStore = savedStore ?? SwiftDataSavedRecipeStore()
        self.onDeviceModel = onDeviceModel
        self.backend = backend
        self.credits = credits ?? UnavailableCreditsGateway()
        config = configStore.load()
        saved = self.savedStore.all()
        refreshAvailability()
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

    /// What Saved recipes lists. Menus come from the server only, which this build doesn't
    /// have, so it has no screen for one yet.
    var savedRecipes: [SavedRecipe] { saved.filter { $0.kind == .recipe } }

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
